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
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
    private lateinit var tvLine: TextView
    private lateinit var profileSpin: Spinner
    private lateinit var profileName: EditText
    private lateinit var glossaryEdit: EditText
    private var pendingPs5 = false
    private var profileList: List<GameProfile> = emptyList()

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

        // ---- PS5 tek dokunus ----
        root.addView(header("🎮 PS5 (Remote Play ile)", pad))
        root.addView(Button(this).apply {
            text = "▶  PS5 modunu başlat"
            isAllCaps = false
            textSize = 19f
            setOnClickListener { startCapture(ps5 = true) }
        })
        root.addView(TextView(this).apply {
            textSize = 13f
            text = "Ekran yakalamayı başlatır, PS Remote Play'i açar ve canlı çeviriyi kendiliğinden açar. " +
                "Çeviriler TV'deki Oyun Çevirici'de oyunun üstünde görünür. İlk seferde butona basılı tutup 'Bölge seç' ile altyazı alanını çiz."
        })
        root.addView(button("📜  Altyazı geçmişi") { startActivity(Intent(this, HistoryActivity::class.java)) })

        // ---- Oyun profili ----
        root.addView(header("🗂️ Oyun profili ve isim sözlüğü", pad))
        profileSpin = Spinner(this)
        root.addView(profileSpin)
        profileName = EditText(this).apply {
            hint = "Profil adı (örn. RDR2)"
            isSingleLine = true
        }
        root.addView(profileName)
        glossaryEdit = EditText(this).apply {
            hint = "İsim sözlüğü – her satıra bir tane:\nArthur Morgan\nDead Eye = Ölü Göz"
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            setText(Prefs.glossary(this@MainActivity))
        }
        root.addView(glossaryEdit)
        root.addView(TextView(this).apply {
            textSize = 13f
            text = "Sadece isim yazarsan çevrilmez, \"A = B\" yazarsan A her zaman B olarak çevrilir. Sözlük Gemini ile çalışır. " +
                "Profil; dili, altyazı bölgesini ve sözlüğü birlikte saklar."
        })
        val prow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        prow.addView(button("💾 Kaydet") { saveProfile() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        prow.addView(button("🗑️ Sil") { deleteProfile() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(prow)
        refreshProfiles()

        // ---- Konsol + TV ----
        root.addView(header("🎮 Konsol + TV (kamera modu)", pad))
        root.addView(Button(this).apply {
            text = "📷  Kamera modunu aç"
            isAllCaps = false
            textSize = 18f
            setOnClickListener { startActivity(Intent(this@MainActivity, CameraActivity::class.java)) }
        })
        tvLine = TextView(this).apply { textSize = 14f }
        root.addView(tvLine)
        root.addView(CheckBox(this).apply {
            text = "Çeviriyi TV'deki Oyun Çevirici'ye gönder (oyunun üstünde gösterir)"
            isChecked = Prefs.tvSend(this@MainActivity)
            setOnCheckedChangeListener { _, b ->
                Prefs.setTvSend(this@MainActivity, b)
                TvLink.restart(this@MainActivity)
            }
        })
        val hostEdit = EditText(this).apply {
            hint = "TV adresi (boş = otomatik bul, örn. 192.168.1.40)"
            setText(Prefs.tvHost(this@MainActivity))
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        root.addView(hostEdit)
        val sizes = listOf("Küçük" to 24, "Orta" to 30, "Büyük" to 38, "Çok büyük" to 46)
        val sizeSpin = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, sizes.map { "TV yazı boyutu: ${it.first}" })
            setSelection(sizes.indexOfFirst { it.second == Prefs.tvSize(this@MainActivity) }.coerceAtLeast(1))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) =
                    Prefs.setTvSize(this@MainActivity, sizes[pos].second)
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        root.addView(sizeSpin)
        val posSpin = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("TV'de konum: Altta", "TV'de konum: Üstte"))
            setSelection(if (Prefs.tvPos(this@MainActivity) == "top") 1 else 0)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) =
                    Prefs.setTvPos(this@MainActivity, if (pos == 1) "top" else "bottom")
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        root.addView(posSpin)
        val bgs = listOf("Arka plan: Açık" to 0x66, "Arka plan: Orta" to 0xAA, "Arka plan: Koyu" to 0xCC, "Arka plan: Tam koyu" to 0xF0)
        root.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, bgs.map { it.first })
            setSelection(bgs.indexOfFirst { it.second == Prefs.tvBg(this@MainActivity) }.coerceAtLeast(2))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) =
                    Prefs.setTvBg(this@MainActivity, bgs[pos].second)
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        })
        val margins = listOf("Kenardan uzaklık: Az" to 16, "Kenardan uzaklık: Orta" to 40, "Kenardan uzaklık: Çok" to 90)
        root.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, margins.map { it.first })
            setSelection(margins.indexOfFirst { it.second == Prefs.tvMargin(this@MainActivity) }.coerceAtLeast(1))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) =
                    Prefs.setTvMargin(this@MainActivity, margins[pos].second)
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        })
        root.addView(CheckBox(this).apply {
            text = "Kalın yazı"
            isChecked = Prefs.tvBold(this@MainActivity)
            setOnCheckedChangeListener { _, b -> Prefs.setTvBold(this@MainActivity, b) }
        })
        root.addView(CheckBox(this).apply {
            text = "Orijinal (İngilizce) metni de altında küçük göster"
            isChecked = Prefs.tvOrig(this@MainActivity)
            setOnCheckedChangeListener { _, b -> Prefs.setTvOrig(this@MainActivity, b) }
        })
        root.addView(button("TV adresini kaydet ve deneme altyazısı gönder") {
            if (hostEdit.text.toString().trim() != Prefs.tvHost(this)) {
                Prefs.setTvHost(this, hostEdit.text.toString())
                TvLink.restart(this)
            } else {
                TvLink.start(this)
            }
            TvLink.sendSub(this, "Deneme altyazısı: telefon bağlı ✓")
            tvLine.postDelayed({ TvLink.sendClear() }, 6000)
        })

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

        root.addView(CheckBox(this).apply {
            text = "Hızlı önizleme: önce telefonun çevirisini hemen göster, Gemini'ninki gelince değiştir (dil paketi indirilmiş olmalı)"
            isChecked = Prefs.preview(this@MainActivity)
            setOnCheckedChangeListener { _, b -> Prefs.setPreview(this@MainActivity, b) }
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

        root.addView(header("📱 Telefonda oynanan oyunlar (ekran yakalama)", pad))
        root.addView(button("1) Dil paketini indir (hızlı çeviri için, ilk seferde)") { downloadModel() })
        root.addView(button("2) Diğer uygulamaların üstünde gösterme izni ver") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        })
        root.addView(button("3) Çeviriciyi başlat") { startCapture(false) })
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
        TvLink.onStatus = { if (::tvLine.isInitialized) tvLine.text = TvLink.status(this) }
        TvLink.start(this)
        tvLine.text = TvLink.status(this)
        updateStatus()
    }

    override fun onPause() {
        super.onPause()
        if (::glossaryEdit.isInitialized) Prefs.setGlossary(this, glossaryEdit.text.toString())
        TvLink.onStatus = null
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
            key, Prefs.geminiModel(this), "İngilizce", "",
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
    private fun startCapture(ps5: Boolean = false) {
        pendingPs5 = ps5
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Önce 2. adımdaki izni ver", Toast.LENGTH_LONG).show()
            return
        }
        if (OverlayService.running) {
            if (ps5) openRemotePlay() else Toast.makeText(this, "Zaten çalışıyor", Toast.LENGTH_SHORT).show()
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
            .putExtra(OverlayService.EXTRA_PS5, pendingPs5)
        startForegroundService(i)
        if (pendingPs5) {
            status.postDelayed({ openRemotePlay() }, 700)
        } else {
            Toast.makeText(this, "Hazır! Oyunu aç ve mavi butona dokun.", Toast.LENGTH_LONG).show()
            moveTaskToBack(true)
        }
    }

    private fun openRemotePlay() {
        val li = packageManager.getLaunchIntentForPackage(REMOTE_PLAY_PKG)
        if (li == null) {
            Toast.makeText(this, "PS Remote Play yüklü değil. Play Store'dan kur, sonra tekrar dene.", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$REMOTE_PLAY_PKG")))
            } catch (_: Exception) {
            }
            return
        }
        Toast.makeText(this, "PS5 modu açık. Canlı çeviri birkaç saniye içinde başlar.", Toast.LENGTH_LONG).show()
        startActivity(li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---------------- Profiller ----------------

    private fun refreshProfiles() {
        profileList = Prefs.profiles(this)
        val names = listOf("— Profil yok —") + profileList.map { it.name }
        profileSpin.onItemSelectedListener = null
        profileSpin.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        val active = Prefs.activeProfile(this)
        val idx = profileList.indexOfFirst { it.name == active }
        profileSpin.setSelection(if (idx >= 0) idx + 1 else 0, false)
        profileName.setText(active)
        profileSpin.post {
            profileSpin.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val prof = if (pos == 0) null else profileList.getOrNull(pos - 1)
                    Prefs.applyProfile(this@MainActivity, prof)
                    profileName.setText(prof?.name ?: "")
                    glossaryEdit.setText(Prefs.glossary(this@MainActivity))
                    spinner.setSelection(LANGS.indexOfFirst { it.first == Prefs.lang(this@MainActivity) }.coerceAtLeast(0))
                    updateStatus()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
    }

    private fun saveProfile() {
        val name = profileName.text.toString().trim()
        Prefs.setGlossary(this, glossaryEdit.text.toString())
        if (name.isEmpty()) {
            Toast.makeText(this, "Sözlük kaydedildi. Profil için bir ad yaz.", Toast.LENGTH_SHORT).show()
            return
        }
        Prefs.saveCurrentAsProfile(this, name)
        refreshProfiles()
        Toast.makeText(this, "Profil kaydedildi: $name", Toast.LENGTH_SHORT).show()
    }

    private fun deleteProfile() {
        val name = Prefs.activeProfile(this)
        if (name.isEmpty()) {
            Toast.makeText(this, "Silinecek profil seçili değil", Toast.LENGTH_SHORT).show()
            return
        }
        Prefs.deleteProfile(this, name)
        refreshProfiles()
        Toast.makeText(this, "Silindi: $name", Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REMOTE_PLAY_PKG = "com.playstation.remoteplay"
        private const val REQ_CAPTURE = 1
    }
}
