package com.huseyin.oyuncevirici

import android.content.Context
import android.graphics.RectF
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

    fun tvSize(c: Context): Int = sp(c).getInt("tvSize", 30)
    fun setTvSize(c: Context, v: Int) = sp(c).edit().putInt("tvSize", v).apply()

    /** "bottom" veya "top" */
    fun tvPos(c: Context): String = sp(c).getString("tvPos", "bottom") ?: "bottom"
    fun setTvPos(c: Context, v: String) = sp(c).edit().putString("tvPos", v).apply()

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
