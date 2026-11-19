package com.huseyin.oyuncevirici

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.common.model.DownloadConditions

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var spinner: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "Oyundaki yazıların dili:"
            textSize = 16f
        })

        spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, LANGS.map { it.second }
        )
        spinner.setSelection(LANGS.indexOfFirst { it.first == Prefs.lang(this) }.coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                Prefs.setLang(this@MainActivity, LANGS[pos].first)
                updateStatus()
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        root.addView(spinner)

        root.addView(button("1) Dil paketini indir (ilk seferde, internet gerekir)") { downloadModel() })
        root.addView(button("2) Diğer uygulamaların üstünde gösterme izni ver") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        })
        root.addView(button("3) Çeviriciyi başlat") { startCapture() })
        root.addView(button("Durdur") {
            if (OverlayService.running) {
                startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
            }
            root.postDelayed({ updateStatus() }, 400)
        })

        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, pad, 0, 0)
        }
        root.addView(status)

        root.addView(TextView(this).apply {
            textSize = 14f
            setPadding(0, pad, 0, 0)
            text = """
                NASIL KULLANILIR
                • Başlat'a bas, açılan pencerede "Tüm ekran"ı seç.
                • Oyunu aç. Ekranda mavi "Çevir" butonu çıkar.
                • Butona dokun: ekrandaki yazılar Türkçe olarak yerlerinin üstünde gösterilir. Kapatmak için ekrana dokun.
                • Butonu sürükleyerek yerini değiştirebilirsin.
                • Butona basılı tut: menü açılır.
                   – Bölge seç: Sadece diyalog kutusunu çiz, daha hızlı ve temiz çevirir.
                   – Otomatik mod: Seçili bölgedeki yazı değiştikçe kendiliğinden çevirir, çeviri şerit olarak gösterilir.
                • Dili değiştirdiysen yeni dilin paketini de 1. adımla indir.
            """.trimIndent()
        })

        setContentView(ScrollView(this).apply { addView(root) })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    private fun updateStatus() {
        if (!::status.isInitialized) return
        val izin = if (Settings.canDrawOverlays(this)) "verildi ✓" else "VERİLMEDİ ✗"
        val calisma = if (OverlayService.running) "çalışıyor ✓" else "kapalı"
        val dil = LANGS.firstOrNull { it.first == Prefs.lang(this) }?.second ?: "?"
        val bolge = if (Prefs.region(this) == null) "tüm ekran" else "seçili bölge"
        status.text = "Dil: $dil → Türkçe\nÜstte gösterme izni: $izin\nÇevirici: $calisma\nÇeviri alanı: $bolge"
    }

    private fun downloadModel() {
        val lang = Prefs.lang(this)
        val t = Ml.translator(lang)
        status.text = "Dil paketi indiriliyor… (yaklaşık 30 MB, birkaç dakika sürebilir)"
        t.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                t.close()
                Toast.makeText(this, "Dil paketi hazır", Toast.LENGTH_SHORT).show()
                updateStatus()
            }
            .addOnFailureListener { e ->
                t.close()
                status.text = "İndirme hatası: ${e.message}\nİnternet bağlantını kontrol et."
            }
    }

    @Suppress("DEPRECATION")
    private fun startCapture() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Önce 2. adımdaki izni ver", Toast.LENGTH_LONG).show()
            return
        }
        if (OverlayService.running) {
            Toast.makeText(this, "Zaten çalışıyor", Toast.LENGTH_SHORT).show()
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Ekran izni verilmedi", Toast.LENGTH_SHORT).show()
            return
        }
        val i = Intent(this, OverlayService::class.java)
            .putExtra(OverlayService.EXTRA_CODE, resultCode)
            .putExtra(OverlayService.EXTRA_DATA, data)
        startForegroundService(i)
        Toast.makeText(this, "Hazır! Oyunu aç ve mavi butona dokun.", Toast.LENGTH_LONG).show()
        moveTaskToBack(true)
    }

    companion object {
        private const val REQ_CAPTURE = 1
    }
}
