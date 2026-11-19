package com.huseyin.oyuncevirici

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import kotlin.math.abs

/** Telefon ve TV arasindaki ag ayarlari (iki uygulamada ortak) */
object Net {
    const val SERVICE_TYPE = "_oyuncevirici._tcp"
    const val PORT = 47321
}

/** Altyazi bicimlendirme: "Isim: konusma" satirlarinda ismi renkli ve kalin yapar. */
object SubFormat {
    private val palette = intArrayOf(
        0xFFFFD54F.toInt(), 0xFF4FC3F7.toInt(), 0xFFA5D6A7.toInt(),
        0xFFFF8A80.toInt(), 0xFFCE93D8.toInt(), 0xFFFFAB91.toInt(), 0xFF80CBC4.toInt()
    )

    // En fazla 3 kelimelik, 28 karakterlik isim ve ardindan ":"
    private val speaker = Regex("^\\s*([\\p{L}][\\p{L}\\p{N}.'\\-]*(?: [\\p{L}\\p{N}.'\\-]+){0,2}):\\s+")

    // Acik zemin icin koyu tonlar (gecmis ekrani)
    private val paletteLight = intArrayOf(
        0xFFE65100.toInt(), 0xFF0277BD.toInt(), 0xFF2E7D32.toInt(),
        0xFFC62828.toInt(), 0xFF6A1B9A.toInt(), 0xFF4E342E.toInt(), 0xFF00695C.toInt()
    )

    fun color(name: String, light: Boolean = false): Int {
        val p = if (light) paletteLight else palette
        return p[abs(name.lowercase().hashCode()) % p.size]
    }

    fun styled(text: String, original: String? = null, light: Boolean = false): CharSequence {
        val sb = SpannableStringBuilder()
        text.split("\n").forEachIndexed { i, line ->
            if (i > 0) sb.append("\n")
            val m = speaker.find(line)
            if (m != null && m.groupValues[1].length <= 28) {
                val name = m.groupValues[1]
                val start = sb.length
                sb.append(name).append(":")
                sb.setSpan(ForegroundColorSpan(color(name, light)), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.append(" ").append(line.substring(m.range.last + 1))
            } else {
                sb.append(line)
            }
        }
        if (!original.isNullOrBlank()) {
            sb.append("\n")
            val start = sb.length
            sb.append(original.replace("\n", " "))
            sb.setSpan(RelativeSizeSpan(0.62f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(0xFFBDBDBD.toInt()), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }
}
