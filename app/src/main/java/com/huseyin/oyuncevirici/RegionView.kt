package com.huseyin.oyuncevirici

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Tam ekran: kullanici parmagiyla cevrilecek dikdortgeni cizer. */
@SuppressLint("ViewConstructor")
class RegionView(ctx: Context, private val onDone: (Rect?) -> Unit) : View(ctx) {

    private val d = resources.displayMetrics.density
    private val dim = Paint().apply { color = 0x88000000.toInt() }
    private val fill = Paint().apply { color = 0x33FFEB3B }
    private val stroke = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 3 * d
    }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 18 * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }
    private var sx = 0f
    private var sy = 0f
    private var ex = 0f
    private var ey = 0f
    private var drawing = false

    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        if (drawing) {
            val l = min(sx, ex); val t = min(sy, ey); val r = max(sx, ex); val b = max(sy, ey)
            c.drawRect(l, t, r, b, fill)
            c.drawRect(l, t, r, b, stroke)
        } else {
            c.drawText("Çevrilecek alanı parmağınla çiz", width / 2f, height * 0.45f, txt)
            c.drawText("(örneğin diyalog kutusu)", width / 2f, height * 0.45f + 28 * d, txt)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                sx = e.x; sy = e.y; ex = e.x; ey = e.y; drawing = true; invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                ex = e.x; ey = e.y; invalidate()
            }
            MotionEvent.ACTION_UP -> {
                ex = e.x; ey = e.y
                val r = Rect(min(sx, ex).toInt(), min(sy, ey).toInt(), max(sx, ex).toInt(), max(sy, ey).toInt())
                onDone(if (r.width() > 40 && r.height() > 30) r else null)
            }
            MotionEvent.ACTION_CANCEL -> onDone(null)
        }
        return true
    }
}
