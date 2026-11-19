package com.huseyin.oyuncevirici

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Gemini API ile yapay zeka destekli ceviri (REST, ek kutuphane yok). */
object Gemini {

    const val DEFAULT_MODEL = "gemini-3.5-flash-lite"

    private val exec = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    private fun system(langName: String, glossary: String) = """
        Sen video oyunu altyazı çevirmenisin. Kullanıcı sana oyun ekranından OCR ile okunmuş $langName metin gönderir.
        Kurallar:
        - Metni doğal, akıcı ve konuşma diline uygun Türkçeye çevir.
        - OCR'dan kaynaklanan harf hatalarını bağlamdan anlayıp düzelt.
        - Karakter, yer ve eşya adlarını çevirme, olduğu gibi bırak.
        - Konuşan karakterin adı satır başındaysa (ör. "Arthur:") koru.
        - Sadece çeviriyi yaz. Açıklama, tırnak veya not ekleme.
    """.trimIndent() + glossaryBlock(glossary)

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
    fun translate(
        key: String, model: String, langName: String, glossary: String, text: String, context: List<String>,
        cb: (String?, String?) -> Unit
    ) {
        val user = buildString {
            if (context.isNotEmpty()) {
                append("Önceki altyazılar (sadece bağlam için, BUNLARI ÇEVİRME):\n")
                context.forEach { append("- ").append(it).append('\n') }
                append("\nÇevrilecek metin:\n")
            }
            append(text)
        }
        exec.execute {
            val r = runCatching { call(key, model, system(langName, glossary), user, json = false) }
            main.post {
                r.fold({ cb(it.trim(), null) }, { cb(null, it.message ?: "bilinmeyen hata") })
            }
        }
    }

    /** Birden fazla parcayi tek istekte cevir (ekrandaki ayri yazi bloklari icin). */
    fun translateList(
        key: String, model: String, langName: String, glossary: String, items: List<String>,
        cb: (List<String>?, String?) -> Unit
    ) {
        val arr = JSONArray()
        items.forEach { arr.put(it) }
        val user = "Aşağıdaki JSON dizisindeki her öğeyi ayrı ayrı Türkçeye çevir. " +
            "Aynı sırada ve aynı sayıda öğe içeren bir JSON string dizisi döndür, başka hiçbir şey yazma.\n" + arr.toString()
        exec.execute {
            val r = runCatching {
                val out = call(key, model, system(langName, glossary), user, json = true)
                val start = out.indexOf('[')
                val end = out.lastIndexOf(']')
                val ja = JSONArray(out.substring(start, end + 1))
                List(ja.length()) { ja.optString(it, "") }
            }
            main.post {
                r.fold({
                    if (it.size == items.size) cb(it, null) else cb(null, "yanıt eksik geldi")
                }, { cb(null, it.message ?: "bilinmeyen hata") })
            }
        }
    }

    private fun call(key: String, model: String, system: String, user: String, json: Boolean): String {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/${model.trim()}:generateContent")
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put(
                "contents", JSONArray().put(
                    JSONObject().put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", user)))
                )
            )
            .put("generationConfig", JSONObject().apply {
                put("temperature", 0.3)
                if (json) put("responseMimeType", "application/json")
            })

        val c = url.openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 10000
            c.readTimeout = 30000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.setRequestProperty("x-goog-api-key", key.trim())
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
                        404 -> "Model bulunamadı ($model). Model adını kontrol et."
                        429 -> "Gemini kotası doldu, biraz bekle."
                        else -> msg
                    }
                )
            }
            val parts = JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
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
