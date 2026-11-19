package com.huseyin.oyuncevirici

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.nl.translate.Translator
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import java.util.Locale

class OverlayService : Service() {

    companion object {
        const val ACTION_STOP = "com.huseyin.oyuncevirici.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "cevirici"

        @Volatile
        var running = false
    }

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var bgThread: HandlerThread? = null
    private var bg: Handler? = null

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val lock = Any()
    private var latest: Image? = null
    private var sw = 0
    private var sh = 0
    private var dpi = 0

    private var fab: TextView? = null
    private var fabLp: WindowManager.LayoutParams? = null
    private var resultView: View? = null
    private var menuView: View? = null
    private var regionView: View? = null
    private var subtitleView: TextView? = null

    private lateinit var engine: LiveEngine
    private var engineReady = false
    private var fabBg: GradientDrawable? = null

    private var busy = false
    private var auto = false

    private val density get() = resources.displayMetrics.density

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
        if (projection != null) return START_NOT_STICKY

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data = intent?.let { dataExtra(it) }
        startAsForeground()
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = try {
            mpm.getMediaProjection(code, data)
        } catch (e: Exception) {
            null
        }
        if (p == null) {
            toast("Ekran yakalama başlatılamadı")
            stopSelf()
            return START_NOT_STICKY
        }
        projection = p

        val t = HandlerThread("yakala").also { it.start() }
        bgThread = t
        bg = Handler(t.looper)

        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { stopSelf() }
            }
        }, main)

        readMetrics()
        val r = newReader()
        reader = r
        display = p.createVirtualDisplay(
            "cevirici", sw, sh, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, bg
        )

        engine = LiveEngine(this, onShow = { s -> showSubtitle(s) }, onClear = { removeSubtitle() })
        engineReady = true
        if (Prefs.speak(this)) engine.initTts()
        TvLink.start(this)

        showFab()
        running = true
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun dataExtra(i: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else i.getParcelableExtra(EXTRA_DATA)

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Oyun Çevirici", NotificationManager.IMPORTANCE_LOW)
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Oyun Çevirici çalışıyor")
            .setContentText("Dokun: çevir • Basılı tut: menü")
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null as Icon?, "Durdur", stopPi).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    // ---------------- Ekran yakalama ----------------

    @Suppress("DEPRECATION")
    private fun readMetrics() {
        val dm = getSystemService(DisplayManager::class.java)
        val disp = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val m = DisplayMetrics()
        disp.getRealMetrics(m)
        sw = m.widthPixels
        sh = m.heightPixels
        dpi = m.densityDpi
    }

    private fun newReader(): ImageReader {
        val r = ImageReader.newInstance(sw, sh, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ rd ->
            val img = try {
                rd.acquireLatestImage()
            } catch (e: Exception) {
                null
            }
            if (img != null) {
                synchronized(lock) {
                    latest?.close()
                    latest = img
                }
            }
        }, bg)
        return r
    }

    /** Arka plan is parcaciginda cagrilir */
    private fun grabBitmap(): Bitmap? {
        synchronized(lock) {
            val img = latest ?: return null
            return try {
                val plane = img.planes[0]
                val buf = plane.buffer
                buf.rewind()
                val ps = plane.pixelStride
                val rs = plane.rowStride
                val w = img.width
                val h = img.height
                val full = Bitmap.createBitmap(rs / ps, h, Bitmap.Config.ARGB_8888)
                full.copyPixelsFromBuffer(buf)
                if (full.width == w) full
                else Bitmap.createBitmap(full, 0, 0, w, h).also { full.recycle() }
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun cropRegion(b: Bitmap): Pair<Bitmap, Point> {
        val r = Prefs.region(this) ?: return b to Point(0, 0)
        val l = (r.left * b.width).toInt().coerceIn(0, b.width - 2)
        val t = (r.top * b.height).toInt().coerceIn(0, b.height - 2)
        val w = ((r.right - r.left) * b.width).toInt().coerceIn(1, b.width - l)
        val h = ((r.bottom - r.top) * b.height).toInt().coerceIn(1, b.height - t)
        return Bitmap.createBitmap(b, l, t, w, h) to Point(l, t)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val ow = sw
        val oh = sh
        readMetrics()
        if ((sw != ow || sh != oh) && display != null) {
            val nw = sw
            val nh = sh
            val nd = dpi
            bg?.post {
                val old = reader
                old?.setOnImageAvailableListener(null, null)
                synchronized(lock) {
                    latest?.close()
                    latest = null
                }
                val r = newReader()
                reader = r
                display?.resize(nw, nh, nd)
                display?.surface = r.surface
                old?.close()
            }
            hideResults()
            hideMenu()
            fabLp?.let { lp ->
                val size = (56 * density).toInt()
                lp.x = lp.x.coerceIn(0, max(0, sw - size))
                lp.y = lp.y.coerceIn(0, max(0, sh - size))
                fab?.let { f -> try { wm.updateViewLayout(f, lp) } catch (_: Exception) {} }
            }
        }
    }

    // ---------------- Tek seferlik ceviri ----------------

    private fun translateOnce() {
        if (busy) return
        val hadOverlay = resultView != null
        hideResults()
        hideMenu()
        busy = true
        setFabBusy(true)
        bg?.postDelayed({
            val full = grabBitmap()
            main.post {
                if (full == null) {
                    done()
                    toast("Ekran görüntüsü alınamadı, bir saniye sonra tekrar dene")
                    return@post
                }
                val (bmp, origin) = cropRegion(full)
                engine.recognizer().process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener { text -> translateBlocks(text, origin) }
                    .addOnFailureListener { e ->
                        done()
                        toast("Yazı okunamadı: ${e.message}")
                    }
            }
        }, if (hadOverlay) 250L else 60L)
    }

    private fun translateBlocks(text: Text, origin: Point) {
        val blocks = text.textBlocks.filter { it.boundingBox != null && it.text.isNotBlank() }
        if (blocks.isEmpty()) {
            done()
            toast("Ekranda yazı bulunamadı")
            return
        }
        val lang = engine.lang
        engine.translateMany(blocks.map { Ml.clean(lang, it.text) }) { list, err ->
            done()
            if (list == null) {
                toast("Çeviri hatası: $err")
            } else {
                showResults(blocks, list, origin)
            }
        }
    }

    private fun done() {
        busy = false
        setFabBusy(false)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showResults(blocks: List<Text.TextBlock>, tr: List<String>, origin: Point) {
        hideResults()
        val root = FrameLayout(this)
        root.setOnClickListener { hideResults() }
        val lp = overlayParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, touchable = true)
        try {
            wm.addView(root, lp)
        } catch (e: Exception) {
            toast("Ekranda gösterilemedi: ${e.message}")
            return
        }
        resultView = root
        val d = density
        root.post {
            val loc = IntArray(2)
            root.getLocationOnScreen(loc)
            blocks.forEachIndexed { i, b ->
                val box = b.boundingBox ?: return@forEachIndexed
                val tv = TextView(this).apply {
                    text = tr.getOrElse(i) { "" }
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply {
                        cornerRadius = 6 * d
                        setColor(0xEE1A1A1A.toInt())
                        setStroke((1 * d).toInt(), 0xFF64B5F6.toInt())
                    }
                    val p = (5 * d).toInt()
                    setPadding(p, p / 2, p, p / 2)
                    val lineH = box.height().toFloat() / max(1, b.lines.size)
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, (lineH * 0.72f).coerceIn(12 * d, 22 * d))
                }
                val w = min(max(box.width(), (160 * d).toInt()), root.width)
                val flp = FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT)
                flp.leftMargin = (box.left + origin.x - loc[0]).coerceIn(0, max(0, root.width - w))
                flp.topMargin = (box.top + origin.y - loc[1]).coerceIn(0, max(0, root.height - (30 * d).toInt()))
                root.addView(tv, flp)
            }
        }
    }

    private fun hideResults() {
        resultView?.let { safeRemove(it) }
        resultView = null
    }

    // ---------------- Canli ceviri ----------------

    private val autoTick = object : Runnable {
        override fun run() {
            if (!auto) return
            autoStep()
            main.postDelayed(this, 800)
        }
    }

    private fun toggleAuto() {
        hideMenu()
        auto = !auto
        if (auto) {
            engine.reset()
            hideResults()
            if (Prefs.region(this) == null) {
                toast("Canlı çeviri açık. İpucu: Butona basılı tut → 'Bölge seç' ile sadece altyazı alanını seçersen çok daha iyi çalışır.")
            } else {
                toast("Canlı çeviri açık")
            }
            main.post(autoTick)
        } else {
            main.removeCallbacks(autoTick)
            engine.clear()
            engine.stopSpeaking()
            toast("Canlı çeviri durdu")
        }
        updateFabLabel()
    }

    private fun autoStep() {
        if (engine.busy || busy) return
        bg?.post {
            val full = grabBitmap()
            main.post {
                if (full == null || !auto) return@post
                val (bmp, _) = cropRegion(full)
                engine.processLive(bmp)
            }
        }
    }

    private fun showSubtitle(s: String) {
        val d = density
        if (subtitleView == null) {
            val tv = TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 19f
                gravity = Gravity.CENTER
                setBackgroundColor(0xE6000000.toInt())
                val p = (12 * d).toInt()
                setPadding(p, p, p, p)
            }
            val lp = overlayParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                touchable = false, secure = true
            )
            // Android 12+: dokunmalarin oyuna gecmesi icin saydamlik en fazla 0.8 olmali
            lp.alpha = 0.8f
            val r = Prefs.region(this)
            // Seritin cevrilen bolgeyi kapatmamasi icin karsi tarafa koy
            lp.gravity = if (r != null && r.centerY() < 0.5f) Gravity.BOTTOM else Gravity.TOP
            try {
                wm.addView(tv, lp)
            } catch (e: Exception) {
                return
            }
            subtitleView = tv
        }
        subtitleView?.text = s
    }

    private fun removeSubtitle() {
        subtitleView?.let { safeRemove(it) }
        subtitleView = null
    }

    // ---------------- Yuzen buton ve menu ----------------

    @SuppressLint("ClickableViewAccessibility")
    private fun showFab() {
        val d = density
        val size = (56 * d).toInt()
        val tv = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 12f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xDD1565C0.toInt())
                setStroke((2 * d).toInt(), Color.WHITE)
            }.also { fabBg = it }
        }
        // FLAG_SECURE: buton ekran goruntusunde siyah gorunur, yazisi cevrilmez
        val lp = overlayParams(size, size, touchable = true, secure = true)
        lp.x = sw - size - (8 * d).toInt()
        lp.y = sh / 3

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var longFired = false
        val longPress = Runnable {
            if (!moved) {
                longFired = true
                showMenu()
            }
        }
        tv.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    moved = false; longFired = false
                    main.postDelayed(longPress, 550)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && !longFired && (abs(dx) > 12 * d || abs(dy) > 12 * d)) {
                        moved = true
                        main.removeCallbacks(longPress)
                    }
                    if (moved) {
                        lp.x = (startX + dx.toInt()).coerceIn(0, max(0, sw - size))
                        lp.y = (startY + dy.toInt()).coerceIn(0, max(0, sh - size))
                        try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    if (!moved && !longFired) {
                        if (Prefs.tapLive(this)) toggleAuto() else translateOnce()
                    }
                }
                MotionEvent.ACTION_CANCEL -> main.removeCallbacks(longPress)
            }
            true
        }
        try {
            wm.addView(tv, lp)
        } catch (e: Exception) {
            toast("Buton gösterilemedi. 'Üstte gösterme' iznini kontrol et.")
            stopSelf()
            return
        }
        fab = tv
        fabLp = lp
        updateFabLabel()
    }

    private fun updateFabLabel() {
        fab?.text = when {
            auto -> "Canlı"
            busy -> "…"
            else -> "Çevir"
        }
        fabBg?.setColor(if (auto) 0xDD2E7D32.toInt() else 0xDD1565C0.toInt())
    }

    private fun setFabBusy(b: Boolean) {
        busy = b
        updateFabLabel()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showMenu() {
        hideMenu()
        hideResults()
        val d = density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 12 * d
                setColor(0xF2212121.toInt())
            }
            val p = (8 * d).toInt()
            setPadding(p, p, p, p)
        }
        fun item(label: String, action: () -> Unit) {
            box.addView(TextView(this).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 16f
                val p = (12 * d).toInt()
                setPadding(p, p, p * 2, p)
                setOnClickListener { action() }
            })
        }
        item("✏️  Bölge seç (altyazı alanı)") { startRegionSelect() }
        item("📋  Tek seferlik çevir (tüm yazılar yerinde)") {
            hideMenu()
            translateOnce()
        }
        item("🖥️  Tüm ekranı çevir") {
            Prefs.setRegion(this, null)
            hideMenu()
            removeSubtitle()
            toast("Artık tüm ekran çevrilecek")
        }
        item(if (auto) "⏸️  Canlı çeviriyi durdur" else "▶️  Canlı çeviriyi başlat") { toggleAuto() }
        item(if (Prefs.speak(this)) "🔇  Sesli okumayı kapat" else "🔊  Sesli okumayı aç") {
            val on = !Prefs.speak(this)
            Prefs.setSpeak(this, on)
            if (on) engine.initTts() else engine.stopSpeaking()
            hideMenu()
            toast(if (on) "Sesli okuma açık" else "Sesli okuma kapalı")
        }
        item("❌  Çeviriciyi kapat") { stopSelf() }
        item("↩️  Vazgeç") { hideMenu() }

        val lp = overlayParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            touchable = true
        )
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        lp.gravity = Gravity.CENTER
        box.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                hideMenu(); true
            } else false
        }
        try {
            wm.addView(box, lp)
            menuView = box
        } catch (_: Exception) {
        }
    }

    private fun hideMenu() {
        menuView?.let { safeRemove(it) }
        menuView = null
    }

    private fun startRegionSelect() {
        hideMenu()
        hideResults()
        removeRegion()
        lateinit var v: RegionView
        v = RegionView(this) { rect ->
            if (rect != null) {
                val loc = IntArray(2)
                v.getLocationOnScreen(loc)
                val l = (rect.left + loc[0]).toFloat() / sw
                val t = (rect.top + loc[1]).toFloat() / sh
                val r = (rect.right + loc[0]).toFloat() / sw
                val b = (rect.bottom + loc[1]).toFloat() / sh
                Prefs.setRegion(this, RectF(l.coerceIn(0f, 1f), t.coerceIn(0f, 1f), r.coerceIn(0f, 1f), b.coerceIn(0f, 1f)))
                removeSubtitle()
                toast("Bölge kaydedildi. Artık sadece burası çevrilecek.")
            } else {
                toast("Bölge seçimi iptal")
            }
            main.post { removeRegion() }
        }
        val lp = overlayParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, touchable = true)
        try {
            wm.addView(v, lp)
            regionView = v
        } catch (_: Exception) {
        }
    }

    private fun removeRegion() {
        regionView?.let { safeRemove(it) }
        regionView = null
    }

    // ---------------- Yardimcilar ----------------

    private fun overlayParams(
        w: Int, h: Int, touchable: Boolean, secure: Boolean = false
    ): WindowManager.LayoutParams {
        var f = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (!touchable) f = f or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (secure) f = f or WindowManager.LayoutParams.FLAG_SECURE
        val lp = WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, f, PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return lp
    }

    private fun safeRemove(v: View) {
        try {
            wm.removeView(v)
        } catch (_: Exception) {
        }
    }

    private fun toast(s: String) {
        main.post { Toast.makeText(applicationContext, s, Toast.LENGTH_LONG).show() }
    }

    override fun onDestroy() {
        running = false
        auto = false
        main.removeCallbacksAndMessages(null)
        hideResults()
        hideMenu()
        removeRegion()
        removeSubtitle()
        fab?.let { safeRemove(it) }
        fab = null
        try { display?.release() } catch (_: Exception) {}
        display = null
        reader?.setOnImageAvailableListener(null, null)
        synchronized(lock) {
            latest?.close()
            latest = null
        }
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        val p = projection
        projection = null
        try { p?.stop() } catch (_: Exception) {}
        if (engineReady) engine.close()
        bgThread?.quitSafely()
        super.onDestroy()
    }
}
