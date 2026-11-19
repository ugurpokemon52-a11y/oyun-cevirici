package com.huseyin.oyuncevirici

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface

/** Android TV acilis ekrani: aliciyi baslatir, durumu gosterir. Kumanda ile kullanilir. */
class TvActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (32 * d).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad * 2, pad, pad * 2, pad)
            setBackgroundColor(0xFF101820.toInt())
        }
        root.addView(TextView(this).apply {
            text = "Oyun Çevirici – TV alıcısı"
            textSize = 30f
            setTextColor(Color.WHITE)
        })
        status = TextView(this).apply {
            textSize = 20f
            setTextColor(0xFFB3E5FC.toInt())
            setPadding(0, pad / 2, 0, pad / 2)
        }
        root.addView(status)

        val startBtn = button("▶  Alıcıyı başlat") { startReceiver(null) }
        root.addView(startBtn)
        root.addView(button("🔓  Diğer uygulamaların üstünde gösterme izni") { openOverlayPermission() })
        root.addView(button("💬  Deneme altyazısı göster") { startReceiver(TvService.ACTION_TEST) })
        root.addView(button("■  Durdur") {
            startService(Intent(this, TvService::class.java).setAction(TvService.ACTION_STOP))
        })

        root.addView(TextView(this).apply {
            textSize = 17f
            setTextColor(0xFFDDDDDD.toInt())
            setPadding(0, pad / 2, 0, 0)
            text = """
                NASIL KULLANILIR
                1. Bu ekranda izin "verildi" görünmeli ve alıcı açık olmalı.
                2. Telefonda Oyun Çevirici → "Kamera modu"nu aç. Telefon bu TV'yi aynı Wi-Fi'da kendiliğinden bulur.
                3. Konsolun HDMI girişine geç ve oyunu oyna. Çeviriler oyunun üstünde çıkar.
                Alıcı arka planda çalışmaya devam eder, bu ekranı kapatabilirsin.

                İzin düğmesi çalışmazsa: Ayarlar → Uygulamalar → Özel uygulama erişimi → "Diğer uygulamaların üzerinde göster" → Oyun Çevirici.
                O menü de yoksa bilgisayardan bir kez:
                adb shell appops set com.huseyin.oyuncevirici SYSTEM_ALERT_WINDOW allow
            """.trimIndent()
        })

        setContentView(ScrollView(this).apply { addView(root) })
        startBtn.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        TvService.onStatus = { updateStatus() }
        if (Settings.canDrawOverlays(this) && !TvService.running) startReceiver(null)
        updateStatus()
    }

    override fun onPause() {
        super.onPause()
        TvService.onStatus = null
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 20f
        setOnClickListener { onClick() }
    }

    private fun startReceiver(action: String?) {
        val i = Intent(this, TvService::class.java)
        if (action != null) i.action = action
        startForegroundService(i)
        status.postDelayed({ updateStatus() }, 500)
    }

    private fun openOverlayPermission() {
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
        )
        for (i in intents) {
            try {
                startActivity(i); return
            } catch (_: Exception) {
            }
        }
        status.text = "Bu TV izin ekranını açmıyor. Alttaki adb komutunu kullan."
    }

    private fun ip(): String {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress ?: "bulunamadı"
        } catch (e: Exception) {
            "bulunamadı"
        }
    }

    private fun updateStatus() {
        if (!::status.isInitialized) return
        val izin = if (Settings.canDrawOverlays(this)) "verildi ✓" else "VERİLMEDİ ✗ (çeviri ekranda görünmez)"
        val alici = if (TvService.running) "açık ✓" else "kapalı"
        val n = TvService.clients.get()
        val tel = if (n > 0) "bağlı ✓" else "bekleniyor…"
        status.text = "Üstte gösterme izni: $izin\nAlıcı: $alici\nTelefon: $tel\nTV adresi: ${ip()}  (otomatik bulunamazsa telefona bunu yaz)"
    }
}
