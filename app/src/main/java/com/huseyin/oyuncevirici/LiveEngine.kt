package com.huseyin.oyuncevirici

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.widget.Toast
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognizer
import java.util.Locale
import java.io.File
import java.util.concurrent.Executors
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Ortak canli ceviri motoru: OCR -> degisiklik/sabitlik kontrolu -> ceviri (Gemini veya cihaz ici)
 * -> gosterme (+ sesli okuma + TV'ye gonderme). Tum metotlar ana is parcaciginda cagrilmali.
 */
class LiveEngine(
    private val ctx: Context,
    private val onShow: (String) -> Unit,
    private val onClear: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: TextRecognizer? = null
    private var translator: Translator? = null
    var lang = ""
        private set

    @Volatile
    var busy = false
        private set

    private var lastShown = ""
    private var pending = ""
    private var emptyTicks = 0
    private var showing = false
    private val history = ArrayDeque<String>()

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var lastAiErrorAt = 0L

    private var seqNo = 0
    private var finalSeq = -1

    // Kare farki: goruntu degismediyse OCR'i tekrar calistirma
    private var lastSig: IntArray? = null
    private var lastOcr: String? = null

    fun ensureMl() {
        val l = Prefs.lang(ctx)
        // Profil/dil/sozluk/uslup basina ayri kalici onbellek
        DiskCache.use(
            ctx, Prefs.activeProfile(ctx) + "|" + l + "|" + Prefs.glossary(ctx).hashCode() + "|" + Prefs.tone(ctx).hashCode()
        )
        if (l == lang && recognizer != null && translator != null) return
        recognizer?.close()
        translator?.close()
        recognizer = Ml.recognizer(l)
        translator = Ml.translator(l)
        lang = l
        lastOcr = null
        lastSig = null
    }

    /** Onbellekten: Gemini aciksa sadece Gemini cevirileri kabul edilir */
    private fun cached(n: String): String? {
        val (isAi, text) = DiskCache.get(n) ?: return null
        return if (!Prefs.useGemini(ctx) || isAi) text else null
    }

    fun recognizer(): TextRecognizer {
        ensureMl()
        return recognizer!!
    }

    fun reset() {
        lastShown = ""
        pending = ""
        emptyTicks = 0
        lastSig = null
        lastOcr = null
    }

    /** Bir kareyi isle. Mesgulse kareyi atlar ve false dondurur. */
    fun processLive(bmp: Bitmap): Boolean {
        if (busy) return false
        busy = true
        ensureMl()
        val sig = signature(bmp)
        val prev = lastSig
        lastSig = sig
        val prevText = lastOcr
        if (prev != null && prevText != null && sameFrame(prev, sig)) {
            // Goruntu ayni: OCR'a gerek yok, onceki sonucu tekrar kullan (pil ve isinma tasarrufu)
            onText(prevText)
            return true
        }
        val l = lang
        recognizer!!.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { t ->
                val txt = t.textBlocks.joinToString("\n") { Ml.clean(l, it.text) }.trim()
                lastOcr = txt
                onText(txt)
            }
            .addOnFailureListener { busy = false; lastSig = null }
        return true
    }

    /** Kucultulmus gri tonlu imza (en fazla 96 x 54 hucre) */
    private fun signature(b: Bitmap): IntArray {
        val w = 96
        val h = (96f * b.height / b.width).toInt().coerceIn(6, 54)
        val small = Bitmap.createScaledBitmap(b, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== b) small.recycle()
        for (i in px.indices) {
            val c = px[i]
            px[i] = (((c shr 16) and 0xFF) * 3 + ((c shr 8) and 0xFF) * 6 + (c and 0xFF)) / 10
        }
        return px
    }

    private fun sameFrame(a: IntArray, b: IntArray): Boolean {
        if (a.size != b.size) return false
        var changed = 0
        for (i in a.indices) if (abs(a[i] - b[i]) > 14) {
            changed++
            if (changed >= 3) return false
        }
        return true
    }

    private fun onText(src: String) {
        val n = norm(src)
        // Yazi yok: birkac tur bos kalirsa altyaziyi kaldir
        if (n.length < 2) {
            busy = false
            emptyTicks++
            if (emptyTicks >= 4 && showing) {
                clear()
                lastShown = ""
                pending = ""
            }
            return
        }
        emptyTicks = 0
        if (similar(n, norm(lastShown))) {
            busy = false
            return
        }
        // Harf harf yazilan altyazilar icin: ayni metin iki kez gorulunce cevir (ayardan kapatilabilir)
        if (Prefs.waitStable(ctx) && !similar(n, norm(pending))) {
            pending = src
            busy = false
            return
        }
        lastShown = src
        pending = ""
        val seq = ++seqNo
        cached(n)?.let {
            busy = false
            show(it, src, final = true)
            finalSeq = seq
            return
        }

        if (Prefs.useGemini(ctx)) {
            // 1) Hizli onizleme: cihaz ici ceviri hemen ekrana
            var previewText: String? = null
            if (Prefs.preview(ctx)) {
                localQuiet(src) { quick ->
                    if (quick != null && seq == seqNo && finalSeq != seq) {
                        previewText = quick
                        show(quick, src, final = false)
                    }
                }
            }
            // 2) Gemini cevirisi gelince onizlemenin yerine gecer
            Gemini.translate(GCfg.from(ctx), src, history.toList()) { out, err ->
                if (out != null) {
                    DiskCache.put(ctx, n, out, ai = true)
                    remember(src)
                    if (seq == seqNo) {
                        finalSeq = seq
                        show(out, src, final = true)
                    }
                } else {
                    aiError(err)
                    if (seq != seqNo) return@translate
                    val p = previewText
                    if (p != null) {
                        finalSeq = seq
                        show(p, src, final = true)
                    } else {
                        localOne(src) { o, _ ->
                            if (o != null && seq == seqNo) {
                                finalSeq = seq
                                show(o, src, final = true)
                            }
                        }
                    }
                }
            }
            // Gemini beklenirken yeni kareler islenebilsin
            busy = false
        } else {
            localOne(src) { out, _ ->
                busy = false
                if (out != null) {
                    DiskCache.put(ctx, n, out, ai = false)
                    remember(src)
                    finalSeq = seq
                    show(out, src, final = true)
                }
            }
        }
    }

    private fun remember(src: String) {
        history.addLast(src)
        while (history.size > 3) history.removeFirst()
    }

    /** final=false: hizli onizleme (sesli okunmaz, gecmise yazilmaz) */
    fun show(s: String, src: String, final: Boolean) {
        showing = true
        onShow(s)
        TvLink.sendSub(ctx, s, src)
        if (final) {
            if (Prefs.speak(ctx)) speak(s)
            History.add(ctx, src, s)
        }
    }

    fun clear() {
        showing = false
        onClear()
        TvLink.sendClear()
    }

    // ---------------- Ceviri ----------------

    private fun aiError(err: String?) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAiErrorAt > 15000) {
            lastAiErrorAt = now
            toast("Gemini hatası: $err\nBu sefer hızlı çeviri kullanıldı.")
        }
    }

    fun translateMany(srcs: List<String>, cb: (List<String>?, String?) -> Unit) {
        if (Prefs.useGemini(ctx)) {
            Gemini.translateList(GCfg.from(ctx), srcs) { list, err ->
                if (list != null) cb(list, null) else {
                    aiError(err)
                    localMany(srcs, cb)
                }
            }
        } else {
            localMany(srcs, cb)
        }
    }

    private fun localMany(srcs: List<String>, cb: (List<String>?, String?) -> Unit) {
        ensureMl()
        val tr = translator!!
        tr.downloadModelIfNeeded()
            .onSuccessTask { _ -> Tasks.whenAllSuccess<String>(srcs.map { s -> tr.translate(s) }) }
            .addOnSuccessListener { cb(it, null) }
            .addOnFailureListener { e ->
                cb(null, "${e.message}\nHızlı çeviri için uygulamada dil paketini indir.")
            }
    }

    /** Onizleme icin: hata olursa sessizce null (dil paketi yoksa kullaniciyi rahatsiz etmez) */
    private fun localQuiet(src: String, cb: (String?) -> Unit) {
        ensureMl()
        val tr = translator!!
        tr.downloadModelIfNeeded()
            .onSuccessTask { _ -> tr.translate(src) }
            .addOnSuccessListener { cb(it) }
            .addOnFailureListener { cb(null) }
    }

    private fun localOne(src: String, cb: (String?, String?) -> Unit) {
        ensureMl()
        val tr = translator!!
        tr.downloadModelIfNeeded()
            .onSuccessTask { _ -> tr.translate(src) }
            .addOnSuccessListener { cb(it, null) }
            .addOnFailureListener { e ->
                val now = SystemClock.elapsedRealtime()
                if (now - lastAiErrorAt > 15000) {
                    lastAiErrorAt = now
                    toast("Çeviri yapılamadı: ${e.message}\nDil paketini indir ya da Gemini anahtarı gir.")
                }
                cb(null, e.message)
            }
    }

    // ---------------- Karsilastirma ----------------

    private fun norm(s: String): String {
        val sb = StringBuilder()
        for (ch in s.lowercase()) if (ch.isLetterOrDigit()) sb.append(ch)
        return if (sb.length > 400) sb.substring(0, 400) else sb.toString()
    }

    /** OCR kucuk farklar uretebilir (ozellikle kamerada); %85 ve ustu benzerse ayni metin say */
    private fun similar(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.isEmpty() || b.isEmpty()) return false
        val n = a.length
        val m = b.length
        if (abs(n - m) > max(n, m) * 0.15) return false
        var prev = IntArray(m + 1) { it }
        var cur = IntArray(m + 1)
        for (i in 1..n) {
            cur[0] = i
            for (j in 1..m) {
                val c = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + c)
            }
            val t = prev; prev = cur; cur = t
        }
        return 1.0 - prev[m].toDouble() / max(n, m) >= 0.85
    }

    // ---------------- Sesli okuma ----------------

    fun initTts() {
        if (tts != null) return
        tts = TextToSpeech(ctx.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale("tr", "TR")) ?: TextToSpeech.LANG_NOT_SUPPORTED
                ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                if (!ttsReady) toast("Telefonda Türkçe ses paketi yok. Ayarlar → Metin okuma'dan Türkçe sesi indir.")
            } else {
                toast("Sesli okuma başlatılamadı")
            }
        }
    }

    fun stopSpeaking() {
        tts?.stop()
    }

    private fun speak(s: String) {
        if (tts == null) initTts()
        if (ttsReady) tts?.speak(s, TextToSpeech.QUEUE_FLUSH, null, "cevirici")
    }

    private fun toast(s: String) {
        main.post { Toast.makeText(ctx.applicationContext, s, Toast.LENGTH_LONG).show() }
    }

    fun close() {
        DiskCache.flush(ctx)
        if (showing) TvLink.sendClear()
        tts?.shutdown()
        tts = null
        recognizer?.close()
        translator?.close()
        recognizer = null
        translator = null
    }
}

/** Kalici ceviri onbellegi: profil basina dosya, en fazla 3000 satir. */
private object DiskCache {
    private const val MAX = 3000
    private var key = ""
    private val map = LinkedHashMap<String, String>(256, 0.75f, true)
    private var dirty = 0
    private val exec = Executors.newSingleThreadExecutor()

    private fun file(c: Context, k: String) =
        File(c.filesDir, "onbellek_" + Integer.toHexString(k.hashCode()) + ".json")

    @Synchronized
    fun use(c: Context, k: String) {
        if (k == key) return
        flush(c)
        key = k
        map.clear()
        try {
            val f = file(c, k)
            if (f.exists()) {
                val o = JSONObject(f.readText())
                val it = o.keys()
                while (it.hasNext()) {
                    val n = it.next()
                    map[n] = o.getString(n)
                }
            }
        } catch (_: Exception) {
        }
    }

    /** (yapay zeka cevirisi mi, metin) */
    @Synchronized
    fun get(n: String): Pair<Boolean, String>? {
        val v = map[n] ?: return null
        return v.startsWith("g|") to v.substring(2)
    }

    @Synchronized
    fun put(c: Context, n: String, out: String, ai: Boolean) {
        val old = map[n]
        if (!ai && old != null && old.startsWith("g|")) return // Gemini cevirisini yerel ceviriyle ezme
        map[n] = (if (ai) "g|" else "l|") + out
        while (map.size > MAX) {
            val first = map.keys.iterator().next()
            map.remove(first)
        }
        if (++dirty >= 5) flush(c)
    }

    @Synchronized
    fun flush(c: Context) {
        if (dirty == 0 || key.isEmpty()) return
        dirty = 0
        val o = JSONObject()
        for ((k, v) in map) o.put(k, v)
        val text = o.toString()
        val f = file(c.applicationContext, key)
        exec.execute { try { f.writeText(text) } catch (_: Exception) {} }
    }
}
