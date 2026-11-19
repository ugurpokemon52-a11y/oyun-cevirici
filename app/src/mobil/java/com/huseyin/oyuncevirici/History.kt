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

