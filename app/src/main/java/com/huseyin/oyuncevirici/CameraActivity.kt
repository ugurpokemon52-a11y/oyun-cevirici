package com.huseyin.oyuncevirici

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import kotlin.math.max

/** Konsol + TV icin: telefon kamerasini TV'ye tut, yesil kutudaki altyaziyi canli cevir. */
class CameraActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var box: BoxView
    private lateinit var out: TextView
    private lateinit var tvStatus: TextView
    private lateinit var toggle: Button
    private lateinit var engine: LiveEngine
    private var camera: Camera? = null
    private val analysisExec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var userRunning = true
    @Volatile private var visible = false
    @Volatile private var lastAt = 0L
    @Volatile private var viewW = 0
    @Volatile private var viewH = 0

    private val statusCb: () -> Unit = { updateTvStatus() }

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else {
            Toast.makeText(this, "Kamera izni gerekli", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val d = resources.displayMetrics.density

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        root.addView(previewView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        previewView.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            viewW = v.width; viewH = v.height
        }

        box = BoxView(this, Prefs.camBox(this)) { r -> Prefs.setCamBox(this, r) }
        root.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Ust cubuk: baslat/durdur, yakinlastirma, TV durumu
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x99000000.toInt())
            val p = (6 * d).toInt()
            setPadding(p * 2, p, p * 2, p)
        }
        toggle = Button(this).apply {
            isAllCaps = false
            setOnClickListener { setRunning(!userRunning) }
        }
        bar.addView(toggle)
        bar.addView(TextView(this).apply {
            text = "  🔍"
            setTextColor(Color.WHITE)
            textSize = 16f
        })
        val zoom = SeekBar(this).apply {
            max = 100
            progress = (Prefs.camZoom(this@CameraActivity) * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    camera?.cameraControl?.setLinearZoom(p / 100f)
                    if (fromUser) Prefs.setCamZoom(this@CameraActivity, p / 100f)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        bar.addView(zoom, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tvStatus = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding((8 * d).toInt(), 0, 0, 0)
        }
        bar.addView(tvStatus)
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // Alt: ceviri
        out = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            setBackgroundColor(0xDD000000.toInt())
            val p = (12 * d).toInt()
            setPadding(p, p, p, p)
            visibility = View.GONE
        }
        root.addView(out, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        setContentView(root)
        hideSystemBars()

        engine = LiveEngine(
            this,
            onShow = { s -> out.text = s; out.visibility = View.VISIBLE },
            onClear = { out.text = ""; out.visibility = View.GONE },
        )
        if (Prefs.speak(this)) engine.initTts()

        TvLink.onStatus = statusCb
        TvLink.start(this)
        updateTvStatus()
        setRunning(true)

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else askCamera.launch(Manifest.permission.CAMERA)
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    private fun setRunning(r: Boolean) {
        userRunning = r
        toggle.text = if (r) "⏸ Durdur" else "▶ Başlat"
        if (!r) {
            engine.clear()
            engine.reset()
            engine.stopSpeaking()
        }
    }

    private fun updateTvStatus() {
        tvStatus.text = TvLink.status(this)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = try { future.get() } catch (e: Exception) {
                Toast.makeText(this, "Kamera açılamadı", Toast.LENGTH_LONG).show(); return@addListener
            }
            val rs = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                )
                .build()
            val preview = Preview.Builder().setResolutionSelector(rs).build()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(rs)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(analysisExec) { img -> analyze(img) }
            try {
                provider.unbindAll()
                val cam = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                camera = cam
                cam.cameraControl.setLinearZoom(Prefs.camZoom(this))
            } catch (e: Exception) {
                Toast.makeText(this, "Kamera başlatılamadı: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Analiz is parcaciginda */
    private fun analyze(img: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (!userRunning || !visible || engine.busy || now - lastAt < 600) return
            lastAt = now
            val raw = img.toBitmap()
            val rot = img.imageInfo.rotationDegrees
            val bmp = if (rot != 0) {
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
            } else raw
            val crop = cropToBox(bmp) ?: return
            main.post { if (userRunning && visible) engine.processLive(crop) }
        } catch (_: Exception) {
        } finally {
            img.close()
        }
    }

    /** Yesil kutuyu (ekran koordinati) kamera goruntusune (FILL_CENTER) cevirip kirp */
    private fun cropToBox(bmp: Bitmap): Bitmap? {
        val vw = viewW
        val vh = viewH
        if (vw == 0 || vh == 0) return null
        val s = max(vw.toFloat() / bmp.width, vh.toFloat() / bmp.height)
        val ox = (vw - bmp.width * s) / 2f
        val oy = (vh - bmp.height * s) / 2f
        val f = box.fractions()
        val l = ((f.left * vw - ox) / s).toInt().coerceIn(0, bmp.width - 2)
        val t = ((f.top * vh - oy) / s).toInt().coerceIn(0, bmp.height - 2)
        val r = ((f.right * vw - ox) / s).toInt().coerceIn(l + 1, bmp.width)
        val b = ((f.bottom * vh - oy) / s).toInt().coerceIn(t + 1, bmp.height)
        return Bitmap.createBitmap(bmp, l, t, r - l, b - t)
    }

    override fun onStart() {
        super.onStart()
        visible = true
    }

    override fun onStop() {
        super.onStop()
        visible = false
        engine.clear()
        engine.reset()
    }

    override fun onDestroy() {
        if (TvLink.onStatus === statusCb) TvLink.onStatus = null
        engine.close()
        analysisExec.shutdown()
        super.onDestroy()
    }
}
