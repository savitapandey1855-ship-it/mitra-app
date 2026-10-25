package com.mitra.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/** Chhoti voice-wave animation (mic level ke hisaab se hilti hai). */
class WaveView(context: Context) : View(context) {
    private val bars = 5
    private var level = 0.55f
    private var running = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }

    fun start() { running = true; invalidate() }
    fun stop() { running = false; invalidate() }
    fun setLevel(l: Float) { level = l.coerceIn(0.15f, 1f) }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!running) return
        val w = width.toFloat()
        val h = height.toFloat()
        val bw = w / (bars * 2f - 1f)
        val t = System.currentTimeMillis() / 1000.0
        for (i in 0 until bars) {
            val amp = (0.30 + 0.70 * Math.abs(Math.sin(t * 7.0 + i * 0.9))) * level
            var bh = (h * amp).toFloat()
            if (bh < 6f) bh = 6f
            val left = i * bw * 2f
            val top = (h - bh) / 2f
            canvas.drawRoundRect(left, top, left + bw, top + bh, bw / 2f, bw / 2f, paint)
        }
        postInvalidateOnAnimation()
    }
}
