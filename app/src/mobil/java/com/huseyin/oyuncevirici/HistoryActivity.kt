package com.huseyin.oyuncevirici

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Cevrilen altyazilarin listesi (en yenisi ustte). */
class HistoryActivity : Activity() {

    private var items: List<HistItem> = emptyList()
    private lateinit var adapter: BaseAdapter
    private val fmt = SimpleDateFormat("dd.MM HH:mm", Locale("tr", "TR"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Altyazı geçmişi"
        val d = resources.displayMetrics.density
        val p = (12 * d).toInt()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(p, p / 2, p, 0)
        }
        bar.addView(Button(this).apply {
            text = "Tümünü kopyala"
            isAllCaps = false
            setOnClickListener { copyAll() }
        })
        bar.addView(Button(this).apply {
            text = "Temizle"
            isAllCaps = false
            setOnClickListener {
                History.clear(this@HistoryActivity)
                refresh()
            }
        })
        root.addView(bar)

        val list = ListView(this)
        adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(position: Int) = items[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val tv = (convertView as? TextView) ?: TextView(this@HistoryActivity).apply {
                    setPadding(p, p, p, p)
                    textSize = 16f
                }
                val it = items[position]
                val sb = SpannableStringBuilder()
                sb.append(SubFormat.styled(it.out, light = true))
                sb.append("\n")
                val s = sb.length
                sb.append(it.src.replace("\n", " "))
                val meta = "\n" + fmt.format(Date(it.time)) + (if (it.profile.isNotEmpty()) " • ${it.profile}" else "")
                sb.append(meta)
                sb.setSpan(RelativeSizeSpan(0.8f), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(0xFF757575.toInt()), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                tv.text = sb
                return tv
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            copy(items[pos].out)
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(TextView(this).apply {
            text = "Bir satıra dokunursan çevirisi kopyalanır."
            textSize = 13f
            setPadding(p, p / 2, p, p / 2)
        })
        setContentView(root)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        items = History.all(this)
        adapter.notifyDataSetChanged()
        if (items.isEmpty()) Toast.makeText(this, "Henüz çeviri yok", Toast.LENGTH_SHORT).show()
    }

    private fun copy(s: String) {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("ceviri", s))
        Toast.makeText(this, "Kopyalandı", Toast.LENGTH_SHORT).show()
    }

    private fun copyAll() {
        val text = items.reversed().joinToString("\n\n") { "${it.src}\n→ ${it.out}" }
        copy(text)
    }
}
