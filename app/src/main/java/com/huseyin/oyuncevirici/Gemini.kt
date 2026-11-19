package com.huseyin.oyuncevirici

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Gemini ayarlari: anahtar, model, dil, sozluk, uslup */
data class GCfg(
    val key: String, val model: String, val langName: String,
    val glossary: String = "", val tone: String = ""
) {
    companion object {
        fun from(c: Context) = GCfg(
            Prefs.geminiKey(c), Prefs.geminiModel(c), Prefs.langName(c), Prefs.glossary(c), Prefs.tone(c)
        )
    }
}

/** Gorselden okunup cevrilmis blok: kutu goruntuye oranla (0..1) */
data class VisionBlock(val box: RectF, val text: String)

/** Gemini API ile yapay zeka destekli ceviri (REST, ek kutuphane yok). */
object Gemini {

    const val DEFAULT_MODEL = "gemini-3.5-flash-lite"

    /** Secim listesi (en hizli ustte). */
    val MODELS = listOf(
        "gemini-3.5-flash-lite" to "3.5 Flash-Lite (en hızlı, önerilen)",
        "gemini-3.1-flash-lite" to "3.1 Flash-Lite (hızlı)",
        "gemini-3.5-flash" to "3.5 Flash (daha kaliteli, biraz yavaş)",
        "gemini-3.6-flash" to "3.6 Flash (en kaliteli, yavaş)",
    )

    private val exec = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    private fun system(g: GCfg) = buildString {
        append(
            """
            Sen video oyunu altyazı çevirmenisin. Kullanıcı sana oyundan okunmuş ${g.langName} metin gönderir.
            Kurallar:
            - Metni doğal, akıcı ve konuşma diline uygun Türkçeye çevir.
            - Okuma (OCR) kaynaklı harf hatalarını bağlamdan anlayıp düzelt.
            - Karakter, yer ve eşya adlarını çevirme, olduğu gibi bırak.
            - Konuşan karakterin adı satır başındaysa (ör. "Arthur:") koru.
            - Sadece çeviriyi yaz. Açıklama, tırnak veya not ekleme.
            """.trimIndent()
        )
        if (g.tone.isNotBlank()) {
            append("\n\nOYUN VE ÜSLUP: ").append(g.tone.trim())
            append("\nÇeviride bu oyunun dünyasına ve karakterlerin konuşma tarzına uygun bir dil kullan.")
        }
        append(glossaryBlock(g.glossary))
    }

    /** Kullanicinin sozlugu: "kaynak = hedef" satirlari ya da cevrilmeyecek adlar */
    private fun glossaryBlock(g: String): String {
        val lines = g.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(80)
        if (lines.isEmpty()) return ""
        val sb = StringBuilder("\n\nSÖZLÜK (bu oyuna özel, kesinlikle uy):\n")
        for (l in lines) {
            val i = l.indexOf('=')
            if (i > 0) sb.append("- \"").append(l.substring(0, i).trim()).append("\" → \"")
                .append(l.substring(i + 1).trim()).append("\" olarak çevir\n")
            else sb.append("- \"").append(l).append("\" adını çevirme, aynen yaz\n")
        }
        return sb.toString()
    }

    /** Tek metin cevirisi. context: onceki satirlar (sadece baglam icin). */
    fun translate(g: GCfg, text: String, context: List<String>, cb: (String?, String?) -> Unit) {
        val user = buildString {
            if (context.isNotEmpty()) {
                append("Önceki altyazılar (sadece bağlam için, BUNLARI ÇEVİRME):\n")
                context.forEach { append("- ").append(it).append('\n') }
                append("\nÇevrilecek metin:\n")
            }
            append(text)
        }
        exec.execute {
            val r = runCatching { call(g, listOf(textPart(user)), json = false) }
            main.post {
                r.fold({ cb(it.trim(), null) }, { cb(null, it.message ?: "bilinmeyen hata") })
            }
        }
    }

    /** Birden fazla parcayi tek istekte cevir (ekrandaki ayri yazi bloklari icin). */
    fun translateList(g: GCfg, items: List<String>, cb: (List<String>?, String?) -> Unit) {
        val arr = JSONArray()
        items.forEach { arr.put(it) }
        val user = "Aşağıdaki JSON dizisindeki her öğeyi ayrı ayrı Türkçeye çevir. " +
            "Aynı sırada ve aynı sayıda öğe içeren bir JSON string dizisi döndür, başka hiçbir şey yazma.\n" + arr.toString()
        exec.execute {
            val r = runCatching {
                val out = call(g, listOf(textPart(user)), json = true)
                val ja = JSONArray(out.substring(out.indexOf('['), out.lastIndexOf(']') + 1))
                List(ja.length()) { ja.optString(it, "") }
            }
            main.post {
                r.fold({
                    if (it.size == items.size) cb(it, null) else cb(null, "yanıt eksik geldi")
                }, { cb(null, it.message ?: "bilinmeyen hata") })
            }
        }
    }

    /**
     * Akilli ekran: ekran goruntusunu dogrudan Gemini'ye gonder; Gemini yazilari kendisi okur,
     * cevirir ve her blogun yerini dondurur. Suslu fontlarda OCR'dan cok daha iyi.
     */
    fun translateImage(g: GCfg, bmp: Bitmap, cb: (List<VisionBlock>?, String?) -> Unit) {
        exec.execute {
            val r = runCatching {
                val scale = minOf(1f, 1280f / maxOf(bmp.width, bmp.height))
                val small = if (scale < 1f) Bitmap.createScaledBitmap(
                    bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true
                ) else bmp
                val bos = ByteArrayOutputStream()
                small.compress(Bitmap.CompressFormat.JPEG, 82, bos)
                val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
                val prompt = """
                    Bu bir video oyunu ekran görüntüsü. Ekrandaki okunabilir her metin bloğunu (diyalog, menü, eşya,
                    görev, not, mektup, buton yazısı) bul ve Türkçeye çevir. Birbirine ait satırları tek blokta birleştir.
                    Sadece sayı, simge veya tek harften oluşan yazıları atla.
                    Şu biçimde bir JSON dizisi döndür, başka hiçbir şey yazma:
                    [{"box_2d":[ymin,xmin,ymax,xmax],"tr":"Türkçe çeviri"}]
                    box_2d değerleri 0-1000 arası, görüntüye oranla.
                """.trimIndent()
                val img = JSONObject().put(
                    "inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", b64)
                )
                val out = call(g, listOf(img, textPart(prompt)), json = true)
                val ja = JSONArray(out.substring(out.indexOf('['), out.lastIndexOf(']') + 1))
                val list = ArrayList<VisionBlock>()
                for (i in 0 until ja.length()) {
                    val o = ja.optJSONObject(i) ?: continue
                    val b = o.optJSONArray("box_2d") ?: continue
                    val tr = o.optString("tr").trim()
                    if (b.length() < 4 || tr.isEmpty()) continue
                    val ymin = (b.optDouble(0) / 1000.0).toFloat().coerceIn(0f, 1f)
                    val xmin = (b.optDouble(1) / 1000.0).toFloat().coerceIn(0f, 1f)
                    val ymax = (b.optDouble(2) / 1000.0).toFloat().coerceIn(0f, 1f)
                    val xmax = (b.optDouble(3) / 1000.0).toFloat().coerceIn(0f, 1f)
                    if (xmax <= xmin || ymax <= ymin) continue
                    list.add(VisionBlock(RectF(xmin, ymin, xmax, ymax), tr))
                }
                list
            }
            main.post {
                r.fold({ cb(it, null) }, { cb(null, it.message ?: "bilinmeyen hata") })
            }
        }
    }

    private fun textPart(t: String) = JSONObject().put("text", t)

    private fun call(g: GCfg, parts: List<JSONObject>, json: Boolean): String {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/${g.model.trim()}:generateContent")
        val pa = JSONArray()
        parts.forEach { pa.put(it) }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(textPart(system(g)))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", pa)))
            .put("generationConfig", JSONObject().apply {
                put("temperature", 0.3)
                if (json) put("responseMimeType", "application/json")
            })

        val c = url.openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 10000
            c.readTimeout = 45000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.setRequestProperty("x-goog-api-key", g.key.trim())
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val resp = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(resp).getJSONObject("error").getString("message") }
                    .getOrDefault("HTTP $code")
                throw Exception(
                    when (code) {
                        400, 403 -> "API anahtarı geçersiz ya da yetkisiz: $msg"
                        404 -> "Model bulunamadı (${g.model}). Listeden başka bir model seç."
                        429 -> "Gemini kotası doldu, biraz bekle."
                        else -> msg
                    }
                )
            }
            val partsOut = JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            val sb = StringBuilder()
            for (i in 0 until partsOut.length()) {
                val p = partsOut.getJSONObject(i)
                if (p.optBoolean("thought", false)) continue
                sb.append(p.optString("text", ""))
            }
            if (sb.isBlank()) throw Exception("Gemini boş yanıt verdi")
            return sb.toString()
        } finally {
            c.disconnect()
        }
    }
}
