package com.huseyin.oyuncevirici

import android.content.Context
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/** Oyundaki yazinin dili: kod -> Turkce ad */
val LANGS = listOf(
    "en" to "İngilizce",
    "ja" to "Japonca",
    "zh" to "Çince",
    "ko" to "Korece",
    "de" to "Almanca",
    "fr" to "Fransızca",
    "es" to "İspanyolca",
    "it" to "İtalyanca",
    "pt" to "Portekizce",
)

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("ayarlar", Context.MODE_PRIVATE)

    fun lang(c: Context): String = sp(c).getString("lang", "en") ?: "en"
    fun setLang(c: Context, v: String) = sp(c).edit().putString("lang", v).apply()

    fun langName(c: Context): String = LANGS.firstOrNull { it.first == lang(c) }?.second ?: "İngilizce"

    fun geminiKey(c: Context): String = sp(c).getString("gkey", "") ?: ""
    fun setGeminiKey(c: Context, v: String) = sp(c).edit().putString("gkey", v.trim()).apply()

    fun geminiModel(c: Context): String =
        (sp(c).getString("gmodel", "") ?: "").ifBlank { Gemini.DEFAULT_MODEL }
    fun setGeminiModel(c: Context, v: String) = sp(c).edit().putString("gmodel", v.trim()).apply()

    /** true: Gemini (yapay zeka), false: cihaz ici hizli ceviri */
    fun useGemini(c: Context): Boolean = sp(c).getBoolean("useGemini", false) && geminiKey(c).isNotBlank()
    fun useGeminiRaw(c: Context): Boolean = sp(c).getBoolean("useGemini", false)
    fun setUseGemini(c: Context, v: Boolean) = sp(c).edit().putBoolean("useGemini", v).apply()

    /** true: butona dokununca canli (surekli) ceviri acilir/kapanir */
    fun tapLive(c: Context): Boolean = sp(c).getBoolean("tapLive", true)
    fun setTapLive(c: Context, v: Boolean) = sp(c).edit().putBoolean("tapLive", v).apply()

    /** Kamera modundaki yesil kutu (ekran oranlari) */
    fun camBox(c: Context): RectF {
        val s = sp(c)
        return RectF(s.getFloat("cl", 0.08f), s.getFloat("ct", 0.68f), s.getFloat("cr", 0.92f), s.getFloat("cb", 0.92f))
    }
    fun setCamBox(c: Context, r: RectF) =
        sp(c).edit().putFloat("cl", r.left).putFloat("ct", r.top).putFloat("cr", r.right).putFloat("cb", r.bottom).apply()

    fun camZoom(c: Context): Float = sp(c).getFloat("czoom", 0f)
    fun setCamZoom(c: Context, v: Float) = sp(c).edit().putFloat("czoom", v).apply()

    /** Ceviriyi TV'deki aliciya gonder */
    fun tvSend(c: Context): Boolean = sp(c).getBoolean("tvSend", true)
    fun setTvSend(c: Context, v: Boolean) = sp(c).edit().putBoolean("tvSend", v).apply()

    /** Elle girilen TV adresi (bos = otomatik bul) */
    fun tvHost(c: Context): String = sp(c).getString("tvHost", "") ?: ""
    fun setTvHost(c: Context, v: String) = sp(c).edit().putString("tvHost", v.trim()).apply()

    fun tvSize(c: Context): Int = sp(c).getInt("tvSize", 22)
    fun setTvSize(c: Context, v: Int) = sp(c).edit().putInt("tvSize", v).apply()

    /** "bottom" veya "top" */
    fun tvPos(c: Context): String = sp(c).getString("tvPos", "bottom") ?: "bottom"
    fun setTvPos(c: Context, v: String) = sp(c).edit().putString("tvPos", v).apply()

    /** Gemini acikken once cihaz ici ceviriyi hemen goster, sonra Gemini ile duzelt */
    fun preview(c: Context): Boolean = sp(c).getBoolean("preview", true)
    fun setPreview(c: Context, v: Boolean) = sp(c).edit().putBoolean("preview", v).apply()

    /** Isim sozlugu: her satir "kaynak = hedef" ya da sadece cevrilmeyecek isim */
    fun glossary(c: Context): String = sp(c).getString("gloss", "") ?: ""
    fun setGlossary(c: Context, v: String) = sp(c).edit().putString("gloss", v).apply()

    /** Oyun turu / uslup notu (Gemini'ye baglam olarak gider) */
    fun tone(c: Context): String = sp(c).getString("tone", "") ?: ""
    fun setTone(c: Context, v: String) = sp(c).edit().putString("tone", v.trim()).apply()

    /** Canli taramada iki kare arasi bekleme (ms) */
    fun scanMs(c: Context): Int = sp(c).getInt("scanMs", 800)
    fun setScanMs(c: Context, v: Int) = sp(c).edit().putInt("scanMs", v).apply()

    /** Harf harf yazilan altyazilarda yazinin tamamlanmasini bekle */
    fun waitStable(c: Context): Boolean = sp(c).getBoolean("waitStable", true)
    fun setWaitStable(c: Context, v: Boolean) = sp(c).edit().putBoolean("waitStable", v).apply()

    /** Akilli ekranda goruntuyu dogrudan Gemini'ye gonder (OCR yerine) */
    fun visionSmart(c: Context): Boolean = sp(c).getBoolean("visionSmart", true)
    fun setVisionSmart(c: Context, v: Boolean) = sp(c).edit().putBoolean("visionSmart", v).apply()

    // TV gorunumu
    /** Altyazinin en fazla ekran genisliginin yuzde kaci olacagi */
    fun tvWidth(c: Context): Int = sp(c).getInt("tvWidth", 80)
    fun setTvWidth(c: Context, v: Int) = sp(c).edit().putInt("tvWidth", v).apply()

    fun tvBg(c: Context): Int = sp(c).getInt("tvBg", 0xCC)
    fun setTvBg(c: Context, v: Int) = sp(c).edit().putInt("tvBg", v).apply()
    fun tvMargin(c: Context): Int = sp(c).getInt("tvMargin", 40)
    fun setTvMargin(c: Context, v: Int) = sp(c).edit().putInt("tvMargin", v).apply()
    fun tvOrig(c: Context): Boolean = sp(c).getBoolean("tvOrig", false)
    fun setTvOrig(c: Context, v: Boolean) = sp(c).edit().putBoolean("tvOrig", v).apply()
    fun tvBold(c: Context): Boolean = sp(c).getBoolean("tvBold", false)
    fun setTvBold(c: Context, v: Boolean) = sp(c).edit().putBoolean("tvBold", v).apply()

    // ---------------- Oyun profilleri ----------------

    fun profiles(c: Context): MutableList<GameProfile> {
        val raw = sp(c).getString("profiles", "[]") ?: "[]"
        return try {
            val a = JSONArray(raw)
            MutableList(a.length()) { GameProfile.fromJson(a.getJSONObject(it)) }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun saveProfiles(c: Context, list: List<GameProfile>) {
        val a = JSONArray()
        list.forEach { a.put(it.toJson()) }
        sp(c).edit().putString("profiles", a.toString()).apply()
    }

    fun activeProfile(c: Context): String = sp(c).getString("activeProfile", "") ?: ""
    fun setActiveProfile(c: Context, v: String) = sp(c).edit().putString("activeProfile", v).apply()

    /** Profili sec: dil, bolge ve sozluk gecerli ayar olur */
    fun applyProfile(c: Context, p: GameProfile?) {
        if (p == null) {
            setActiveProfile(c, "")
            return
        }
        setLang(c, p.lang)
        setRegionRaw(c, p.region)
        setGlossary(c, p.glossary)
        setTone(c, p.tone)
        setActiveProfile(c, p.name)
    }

    /** Gecerli ayarlari bu adla profile kaydet (varsa ustune yaz) */
    fun saveCurrentAsProfile(c: Context, name: String) {
        val list = profiles(c)
        val p = GameProfile(name.trim(), lang(c), region(c), glossary(c), tone(c))
        val i = list.indexOfFirst { it.name == p.name }
        if (i >= 0) list[i] = p else list.add(p)
        saveProfiles(c, list)
        setActiveProfile(c, p.name)
    }

    fun deleteProfile(c: Context, name: String) {
        saveProfiles(c, profiles(c).filter { it.name != name })
        if (activeProfile(c) == name) setActiveProfile(c, "")
    }

    /** Ceviriyi Turkce sesli oku */
    fun speak(c: Context): Boolean = sp(c).getBoolean("speak", false)
    fun setSpeak(c: Context, v: Boolean) = sp(c).edit().putBoolean("speak", v).apply()

    /** Ceviri bolgesi, ekran oranlari olarak (0..1). null = tum ekran */
    fun region(c: Context): RectF? {
        val s = sp(c)
        if (!s.contains("rl")) return null
        return RectF(s.getFloat("rl", 0f), s.getFloat("rt", 0f), s.getFloat("rr", 1f), s.getFloat("rb", 1f))
    }

    fun setRegion(c: Context, r: RectF?) {
        setRegionRaw(c, r)
        // Aktif oyun profili varsa bolgeyi ona da kaydet
        val name = activeProfile(c)
        if (name.isNotEmpty()) {
            val list = profiles(c)
            val i = list.indexOfFirst { it.name == name }
            if (i >= 0) {
                list[i] = list[i].copy(region = r?.let { RectF(it) })
                saveProfiles(c, list)
            }
        }
    }

    private fun setRegionRaw(c: Context, r: RectF?) {
        val e = sp(c).edit()
        if (r == null) {
            e.remove("rl").remove("rt").remove("rr").remove("rb")
        } else {
            e.putFloat("rl", r.left).putFloat("rt", r.top).putFloat("rr", r.right).putFloat("rb", r.bottom)
        }
        e.apply()
    }
}

object Ml {
    fun recognizer(lang: String): TextRecognizer = when (lang) {
        "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        "zh" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        "ko" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    fun translator(lang: String): Translator {
        val src = TranslateLanguage.fromLanguageTag(lang) ?: TranslateLanguage.ENGLISH
        val opts = TranslatorOptions.Builder()
            .setSourceLanguage(src)
            .setTargetLanguage(TranslateLanguage.TURKISH)
            .build()
        return Translation.getClient(opts)
    }

    /** Satir sonlarini birlestir: Japonca/Cincede bosluksuz, digerlerinde bosluklu */
    fun clean(lang: String, s: String): String =
        if (lang == "ja" || lang == "zh") s.replace("\n", "") else s.replace("\n", " ")
}

data class GameProfile(
    val name: String, val lang: String, val region: RectF?, val glossary: String, val tone: String = ""
) {
    fun toJson(): JSONObject {
        val o = JSONObject().put("name", name).put("lang", lang).put("gloss", glossary).put("tone", tone)
        if (region != null) {
            o.put("rl", region.left.toDouble()).put("rt", region.top.toDouble())
                .put("rr", region.right.toDouble()).put("rb", region.bottom.toDouble())
        }
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): GameProfile {
            val r = if (o.has("rl")) RectF(
                o.getDouble("rl").toFloat(), o.getDouble("rt").toFloat(),
                o.getDouble("rr").toFloat(), o.getDouble("rb").toFloat()
            ) else null
            return GameProfile(
                o.optString("name"), o.optString("lang", "en"), r, o.optString("gloss", ""), o.optString("tone", "")
            )
        }
    }
}
