package com.huseyin.oyuncevirici

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
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

        // ---- Ceviri motoru ----
        root.addView(header("Çeviri türü", pad))
        val group = RadioGroup(this)
        val rbFast = RadioButton(this).apply { id = View.generateViewId(); text = "Hızlı (telefonda, internetsiz)" }
        val rbAi = RadioButton(this).apply { id = View.generateViewId(); text = "Yapay zekâ – Gemini (daha kaliteli, internet gerekir)" }
        group.addView(rbFast)
        group.addView(rbAi)
        group.check(if (Prefs.useGeminiRaw(this)) rbAi.id else rbFast.id)
        group.setOnCheckedChangeListener { _, checked ->
            Prefs.setUseGemini(this, checked == rbAi.id)
            updateStatus()
        }
        root.addView(group)

        val keyEdit = EditText(this).apply {
            hint = "Gemini API anahtarı (AIza…)"
            setText(Prefs.geminiKey(this@MainActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        val modelEdit = EditText(this).apply {
            hint = "Model (boş bırakırsan: ${Gemini.DEFAULT_MODEL})"
            setText(Prefs.geminiModel(this@MainActivity))
            isSingleLine = true
        }
        root.addView(keyEdit)
        root.addView(modelEdit)
        root.addView(button("Anahtarı kaydet ve dene") {
            Prefs.setGeminiKey(this, keyEdit.text.toString())
            Prefs.setGeminiModel(this, modelEdit.text.toString())
            modelEdit.setText(Prefs.geminiModel(this))
            testGemini()
        })
        root.addView(TextView(this).apply {
            textSize = 13f
            text = "Ücretsiz anahtar: aistudio.google.com/apikey adresinden \"Create API key\". " +
                "Yazı değişmedikçe tekrar istek gönderilmez, kota az harcanır."
        })

        // ---- Calisma sekli ----
        root.addView(header("Çalışma şekli", pad))
        root.addView(CheckBox(this).apply {
            text = "Butona dokununca CANLI çeviri başlasın (yazı değiştikçe kendiliğinden çevirir, tekrar dokununca durur)"
            isChecked = Prefs.tapLive(this@MainActivity)
            setOnCheckedChangeListener { _, b -> Prefs.setTapLive(this@MainActivity, b) }
        })
        root.addView(CheckBox(this).apply {
            text = "Çeviriyi Türkçe sesli oku"
            isChecked = Prefs.speak(this@MainActivity)
            setOnCheckedChangeListener { _, b -> Prefs.setSpeak(this@MainActivity, b) }
        })

        root.addView(header("Başlatma", pad))
        root.addView(button("1) Dil paketini indir (hızlı çeviri için, ilk seferde)") { downloadModel() })
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
                • İlk iş: butona BASILI TUT → "Bölge seç" → altyazının çıktığı alanı parmağınla çiz. Bunu oyun başına bir kez yapman yeter.
                • Butona dokun: CANLI çeviri başlar (buton yeşil olur). Altyazı değiştikçe Türkçesi ekranın üstünde/altında bir şeritte çıkar. Tekrar dokununca durur.
                • Menüdeki "Tek seferlik çevir" ekrandaki bütün yazıları (menü, envanter vb.) yerlerinin üstünde çevirir. Kapatmak için ekrana dokun.
                • Butonu sürükleyerek yerini değiştirebilirsin.
                • Hızlı çeviride dili değiştirirsen yeni dilin paketini 1. adımla indir.
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

    private fun header(t: String, pad: Int): TextView = TextView(this).apply {
        text = t
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, pad, 0, pad / 3)
    }

    private fun updateStatus() {
        if (!::status.isInitialized) return
        val izin = if (Settings.canDrawOverlays(this)) "verildi ✓" else "VERİLMEDİ ✗"
        val calisma = if (OverlayService.running) "çalışıyor ✓" else "kapalı"
        val dil = Prefs.langName(this)
        val bolge = if (Prefs.region(this) == null) "tüm ekran" else "seçili bölge"
        val motor = when {
            Prefs.useGemini(this) -> "Yapay zekâ (${Prefs.geminiModel(this)})"
            Prefs.useGeminiRaw(this) -> "Yapay zekâ seçili ama ANAHTAR YOK → hızlı çeviri kullanılacak"
            else -> "Hızlı (telefonda)"
        }
        status.text = "Dil: $dil → Türkçe\nÇeviri: $motor\nÜstte gösterme izni: $izin\nÇevirici: $calisma\nÇeviri alanı: $bolge"
    }

    private fun testGemini() {
        val key = Prefs.geminiKey(this)
        if (key.isBlank()) {
            Toast.makeText(this, "Önce anahtarı yapıştır", Toast.LENGTH_SHORT).show()
            return
        }
        status.text = "Gemini deneniyor…"
        Gemini.translate(
            key, Prefs.geminiModel(this), "İngilizce",
            "Stay close, partner. The bandits are hiding behind those rocks.", emptyList()
        ) { out, err ->
            if (out != null) {
                Toast.makeText(this, "Çalışıyor ✓", Toast.LENGTH_SHORT).show()
                updateStatus()
                status.append("\n\nDeneme çevirisi:\n$out")
            } else {
                status.text = "Gemini hatası: $err"
            }
        }
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
