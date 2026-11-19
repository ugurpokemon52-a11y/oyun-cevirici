package com.huseyin.oyuncevirici

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs

data class HistItem(val time: Long, val profile: String, val src: String, val out: String)

/** Cevrilen altyazilarin gecmisi (son 500 satir, telefonda dosyada saklanir). */
object History {
    private const val MAX = 500
    private val items = ArrayList<HistItem>()
    private var loaded = false
    private val exec = Executors.newSingleThreadExecutor()

    private fun file(c: Context) = File(c.filesDir, "gecmis.json")

    @Synchronized
    private fun load(c: Context) {
        if (loaded) return
        loaded = true
        try {
            val f = file(c)
            if (!f.exists()) return
            val a = JSONArray(f.readText())
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                items.add(HistItem(o.optLong("t"), o.optString("p"), o.optString("s"), o.optString("o")))
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun add(c: Context, src: String, out: String) {
        load(c)
        val last = items.lastOrNull()
        if (last != null && last.out == out) return
        items.add(HistItem(System.currentTimeMillis(), Prefs.activeProfile(c), src, out))
        while (items.size > MAX) items.removeAt(0)
        save(c)
    }

    @Synchronized
    fun all(c: Context): List<HistItem> {
        load(c)
        return items.reversed()
    }

    @Synchronized
    fun clear(c: Context) {
        load(c)
        items.clear()
        save(c)
    }

    private fun save(c: Context) {
        val a = JSONArray()
        items.forEach {
            a.put(JSONObject().put("t", it.time).put("p", it.profile).put("s", it.src).put("o", it.out))
        }
        val text = a.toString()
        val f = file(c.applicationContext)
        exec.execute { try { f.writeText(text) } catch (_: Exception) {} }
    }
}

/** Altyazi bicimlendirme: "Isim: konusma" satirlarinda ismi renkli ve kalin yapar. */
object SubFormat {
    private val palette = intArrayOf(
        0xFFFFD54F.toInt(), 0xFF4FC3F7.toInt(), 0xFFA5D6A7.toInt(),
        0xFFFF8A80.toInt(), 0xFFCE93D8.toInt(), 0xFFFFAB91.toInt(), 0xFF80CBC4.toInt()
    )

    // En fazla 3 kelimelik, 28 karakterlik isim ve ardindan ":"
    private val speaker = Regex("^\\s*([\\p{L}][\\p{L}\\p{N}.'\\-]*(?: [\\p{L}\\p{N}.'\\-]+){0,2}):\\s+")

    fun color(name: String): Int = palette[abs(name.lowercase().hashCode()) % palette.size]

    fun styled(text: String, original: String? = null): CharSequence {
        val sb = SpannableStringBuilder()
        text.split("\n").forEachIndexed { i, line ->
            if (i > 0) sb.append("\n")
            val m = speaker.find(line)
            if (m != null && m.groupValues[1].length <= 28) {
                val name = m.groupValues[1]
                val start = sb.length
                sb.append(name).append(":")
                sb.setSpan(ForegroundColorSpan(color(name)), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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
