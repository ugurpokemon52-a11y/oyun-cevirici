package com.huseyin.oyuncevirici

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** TV tarafi: telefondan gelen altyazilari oyun goruntusunun ustunde gosterir. */
class TvService : Service() {

    companion object {
        const val ACTION_STOP = "com.huseyin.oyuncevirici.TV_STOP"
        const val ACTION_TEST = "com.huseyin.oyuncevirici.TV_TEST"
        private const val CHANNEL = "tv_alici"

        @Volatile var running = false
        val clients = AtomicInteger(0)
        var onStatus: (() -> Unit)? = null
    }

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var server: ServerSocket? = null
    private var nsd: NsdManager? = null
    private var regListener: NsdManager.RegistrationListener? = null
    private var sub: TextView? = null
    private var subLp: WindowManager.LayoutParams? = null
    private val autoHide = Runnable { hideSub() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        if (server == null) startServer()
        running = true
        notifyStatus()
        if (intent?.action == ACTION_TEST) {
            showSub("Deneme altyazısı: Oyun Çevirici TV hazır ✓", 30, "bottom")
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "TV alıcı", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Oyun Çevirici TV alıcısı açık")
            .setContentText("Telefondan gelen çeviriler ekranda gösterilir")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(2, n)
        }
    }

    // ---------------- Ag ----------------

    private fun startServer() {
        thread(name = "tv-sunucu", isDaemon = true) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(TvLink.PORT))
                server = ss
                main.post { registerNsd(ss.localPort) }
                while (!ss.isClosed) {
                    val s = ss.accept()
                    thread(name = "tv-istemci", isDaemon = true) { handle(s) }
                }
            } catch (e: Exception) {
                // kapatildi
            }
        }
    }

    private fun handle(s: Socket) {
        clients.incrementAndGet()
        notifyStatus()
        try {
            s.tcpNoDelay = true
            val r = s.getInputStream().bufferedReader(Charsets.UTF_8)
            while (true) {
                val line = r.readLine() ?: break
                val o = try { JSONObject(line) } catch (e: Exception) { continue }
                main.post { apply(o) }
            }
        } catch (_: Exception) {
        } finally {
            try { s.close() } catch (_: Exception) {}
            val left = clients.decrementAndGet()
            notifyStatus()
            if (left <= 0) main.post { hideSub() }
        }
    }

    private fun apply(o: JSONObject) {
        when (o.optString("t")) {
            "sub" -> showSub(o.optString("text"), o.optInt("size", 30), o.optString("pos", "bottom"))
            "clear" -> hideSub()
        }
    }

    private fun registerNsd(listenPort: Int) {
        val m = getSystemService(NsdManager::class.java) ?: return
        nsd = m
        val info = NsdServiceInfo().apply {
            serviceName = "OyunCevirici-" + Build.MODEL.replace(Regex("[^A-Za-z0-9-]"), "").take(30)
            serviceType = TvLink.SERVICE_TYPE
            setPort(listenPort)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        regListener = l
        try {
            m.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (_: Exception) {
        }
    }

    // ---------------- Ekranda gosterme ----------------

    private fun showSub(text: String, sizeSp: Int, pos: String) {
        if (text.isBlank()) {
            hideSub(); return
        }
        if (!Settings.canDrawOverlays(this)) return
        val d = resources.displayMetrics.density
        val grav = (if (pos == "top") Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
        var tv = sub
        if (tv == null) {
            tv = TextView(this).apply {
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setShadowLayer(4f, 0f, 0f, Color.BLACK)
                background = GradientDrawable().apply {
                    cornerRadius = 10 * d
                    setColor(0xCC000000.toInt())
                }
                val p = (14 * d).toInt()
                setPadding(p * 2, p, p * 2, p)
                maxWidth = (resources.displayMetrics.widthPixels * 0.85f).toInt()
            }
            val lp = WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = grav
            lp.y = (40 * d).toInt()
            try {
                wm.addView(tv, lp)
            } catch (e: Exception) {
                return
            }
            sub = tv
            subLp = lp
        } else {
            val lp = subLp
            if (lp != null && lp.gravity != grav) {
                lp.gravity = grav
                try { wm.updateViewLayout(tv, lp) } catch (_: Exception) {}
            }
        }
        tv.textSize = sizeSp.coerceIn(16, 60).toFloat()
        tv.text = text
        // Telefon baglantisi kopar da "temizle" gelmezse ekranda kalmasin
        main.removeCallbacks(autoHide)
        main.postDelayed(autoHide, 45000)
    }

    private fun hideSub() {
        main.removeCallbacks(autoHide)
        sub?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        sub = null
        subLp = null
    }

    private fun notifyStatus() {
        main.post { onStatus?.invoke() }
    }

    override fun onDestroy() {
        running = false
        hideSub()
        regListener?.let { try { nsd?.unregisterService(it) } catch (_: Exception) {} }
        try { server?.close() } catch (_: Exception) {}
        server = null
        notifyStatus()
        super.onDestroy()
    }
}
