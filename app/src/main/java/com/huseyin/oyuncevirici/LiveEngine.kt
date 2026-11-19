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
    private val cache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 80
    }

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var lastAiErrorAt = 0L

    private var glossaryUsed = ""
    private var seqNo = 0
    private var finalSeq = -1

    fun ensureMl() {
        val l = Prefs.lang(ctx)
        val g = Prefs.glossary(ctx)
        if (g != glossaryUsed) {
            glossaryUsed = g
            cache.clear()
        }
        if (l == lang && recognizer != null && translator != null) return
        recognizer?.close()
        translator?.close()
        recognizer = Ml.recognizer(l)
        translator = Ml.translator(l)
        lang = l
        cache.clear()
    }

    fun recognizer(): TextRecognizer {
        ensureMl()
        return recognizer!!
    }

    fun reset() {
        lastShown = ""
        pending = ""
        emptyTicks = 0
    }

    /** Bir kareyi isle. Mesgulse kareyi atlar ve false dondurur. */
    fun processLive(bmp: Bitmap): Boolean {
        if (busy) return false
        busy = true
        ensureMl()
        val l = lang
        recognizer!!.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { t ->
                onText(t.textBlocks.joinToString("\n") { Ml.clean(l, it.text) }.trim())
            }
            .addOnFailureListener { busy = false }
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
        // Harf harf yazilan altyazilar icin: ayni metin iki kez gorulunce cevir
        if (!similar(n, norm(pending))) {
            pending = src
            busy = false
            return
        }
        lastShown = src
        pending = ""
        val seq = ++seqNo
        cache[n]?.let {
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
            Gemini.translate(
                Prefs.geminiKey(ctx), Prefs.geminiModel(ctx), Prefs.langName(ctx), Prefs.glossary(ctx),
                src, history.toList()
            ) { out, err ->
                if (out != null) {
                    cache[n] = out
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
                    cache[n] = out
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
            Gemini.translateList(Prefs.geminiKey(ctx), Prefs.geminiModel(ctx), Prefs.langName(ctx), Prefs.glossary(ctx), srcs) { list, err ->
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
        if (showing) TvLink.sendClear()
        tts?.shutdown()
        tts = null
        recognizer?.close()
        translator?.close()
        recognizer = null
        translator = null
    }
}
