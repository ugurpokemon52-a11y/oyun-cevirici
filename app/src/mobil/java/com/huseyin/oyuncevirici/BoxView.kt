package com.huseyin.oyuncevirici

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/** Kamera goruntusu ustunde yesil kutu: icinden surukle = tasi, sag alt koseden surukle = boyutlandir. */
@SuppressLint("ViewConstructor")
class BoxView(ctx: Context, initial: RectF, private val onChanged: (RectF) -> Unit) : View(ctx) {

    @Volatile
    private var f = RectF(initial)

    fun fractions(): RectF = RectF(f)

    private val d = resources.displayMetrics.density
    private val dim = Paint().apply { color = 0x66000000 }
    private val stroke = Paint().apply {
        color = 0xFF00E676.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3 * d
    }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF00E676.toInt() }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14 * resources.displayMetrics.scaledDensity
        setShadowLayer(3f, 0f, 0f, Color.BLACK)
    }

    private var mode = 0 // 0 yok, 1 tasi, 2 boyutlandir
    private var lx = 0f
    private var ly = 0f

    private fun px(): RectF = RectF(f.left * width, f.top * height, f.right * width, f.bottom * height)

    override fun onDraw(c: Canvas) {
        val r = px()
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawRect(0f, 0f, w, r.top, dim)
        c.drawRect(0f, r.bottom, w, h, dim)
        c.drawRect(0f, r.top, r.left, r.bottom, dim)
        c.drawRect(r.right, r.top, w, r.bottom, dim)
        c.drawRect(r, stroke)
        c.drawCircle(r.right, r.bottom, 12 * d, handle)
        val hint = "Altyazıyı yeşil kutuya sığdır • Köşeden boyutlandır"
        val ty = if (r.top > 30 * d) r.top - 8 * d else r.bottom + 20 * d
        c.drawText(hint, r.left, ty, txt)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val r = px()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lx = e.x; ly = e.y
                mode = when {
                    hypot(e.x - r.right, e.y - r.bottom) < 40 * d -> 2
                    r.contains(e.x, e.y) -> 1
                    else -> 0
                }
                return mode != 0
            }
            MotionEvent.ACTION_MOVE -> {
                if (width == 0 || height == 0) return true
                val dx = (e.x - lx) / width
                val dy = (e.y - ly) / height
                lx = e.x; ly = e.y
                val n = RectF(f)
                if (mode == 1) {
                    val bw = n.width(); val bh = n.height()
                    n.left = (n.left + dx).coerceIn(0f, 1f - bw)
                    n.top = (n.top + dy).coerceIn(0f, 1f - bh)
                    n.right = n.left + bw
                    n.bottom = n.top + bh
                } else if (mode == 2) {
                    n.right = (n.right + dx).coerceIn(n.left + 0.1f, 1f)
                    n.bottom = (n.bottom + dy).coerceIn(n.top + 0.05f, 1f)
                }
                f = n
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode != 0) onChanged(RectF(f))
                mode = 0
            }
        }
        return true
    }
}
