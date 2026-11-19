package com.huseyin.oyuncevirici

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * TV alicisi: ayar yok. Acilinca aliciyi baslatir ve sadece durumu gosterir.
 * Tum ayarlar telefondaki uygulamadan yapilir.
 */
class TvActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var permBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (32 * d).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad * 2, pad, pad * 2, pad)
            setBackgroundColor(0xFF101820.toInt())
        }
        root.addView(TextView(this).apply {
            text = "Oyun Çevirici TV"
            textSize = 30f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        status = TextView(this).apply {
            textSize = 20f
            setTextColor(0xFFB3E5FC.toInt())
            gravity = Gravity.CENTER
            setPadding(0, pad / 2, 0, pad / 2)
        }
        root.addView(status)

        // Sadece izin yoksa gorunur
        permBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val permBtn = Button(this).apply {
            text = "Ekranda gösterme iznini ver"
            isAllCaps = false
            textSize = 20f
            setOnClickListener { openOverlayPermission() }
        }
        permBox.addView(permBtn)
        permBox.addView(TextView(this).apply {
            textSize = 15f
            setTextColor(0xFFDDDDDD.toInt())
            gravity = Gravity.CENTER
            setPadding(0, pad / 3, 0, 0)
            text = "Düğme çalışmazsa: Ayarlar → Uygulamalar → Özel uygulama erişimi → " +
                "\"Diğer uygulamaların üzerinde göster\" → Oyun Çevirici TV"
        })
        root.addView(permBox)

        root.addView(TextView(this).apply {
            textSize = 16f
            setTextColor(0xFF9E9E9E.toInt())
            gravity = Gravity.CENTER
            setPadding(0, pad, 0, 0)
            text = "Bu ekranı kapatabilirsin, alıcı arka planda çalışır.\nTüm ayarlar telefondaki Oyun Çevirici'den yapılır."
        })

        setContentView(root)
        permBtn.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        TvService.onStatus = { updateStatus() }
        if (Settings.canDrawOverlays(this) && !TvService.running) {
            startForegroundService(Intent(this, TvService::class.java))
        }
        updateStatus()
        status.postDelayed({ updateStatus() }, 600)
    }

    override fun onPause() {
        super.onPause()
        TvService.onStatus = null
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
    }

    private fun ip(): String = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress ?: "?"
    } catch (e: Exception) {
        "?"
    }

    private fun updateStatus() {
        if (!::status.isInitialized) return
        val izin = Settings.canDrawOverlays(this)
        permBox.visibility = if (izin) View.GONE else View.VISIBLE
        status.text = when {
            !izin -> "Çevirilerin oyunun üstünde görünmesi için bir kez izin vermen gerekiyor."
            !TvService.running -> "Alıcı başlatılıyor…"
            TvService.clients.get() > 0 -> "✓ Telefon bağlı – çeviriler oyunun üstünde görünecek"
            else -> "✓ Hazır – telefon bekleniyor\n(TV adresi: ${ip()})"
        }
    }
}
