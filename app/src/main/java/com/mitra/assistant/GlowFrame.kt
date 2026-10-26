package com.mitra.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View

/** Curved input box ka border - blue lights ghoomti rehti hain. */
class GlowFrame @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var angle = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val anim = android.animation.ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 2400
        repeatCount = android.animation.ValueAnimator.INFINITE
        addUpdateListener {
            angle = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        anim.start()
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val inset = dp(2f)
        val r = RectF(inset, inset, width - inset, height - inset)
        val rad = height / 2f

        val colors = intArrayOf(
            0x0022D3EE, 0xFF22D3EE.toInt(), 0xFF6EE7FF.toInt(),
            0x0022D3EE, 0x00000000
        )
        val pos = floatArrayOf(0f, 0.12f, 0.28f, 0.42f, 1f)

        // soft glow (3 layers)
        for (i in 0 until 3) {
            paint.strokeWidth = dp(2f) + i * dp(4f)
            paint.alpha = 70 - i * 22
            val g = SweepGradient(width / 2f, height / 2f, colors, pos)
            val m = Matrix()
            m.setRotate(angle, width / 2f, height / 2f)
            g.setLocalMatrix(m)
            paint.shader = g
            canvas.drawRoundRect(r, rad, rad, paint)
        }

        // bright core line
        paint.strokeWidth = dp(2.4f)
        paint.alpha = 255
        val g2 = SweepGradient(width / 2f, height / 2f, colors, pos)
        val m2 = Matrix()
        m2.setRotate(angle, width / 2f, height / 2f)
        g2.setLocalMatrix(m2)
        paint.shader = g2
        canvas.drawRoundRect(r, rad, rad, paint)

        paint.shader = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        try { anim.cancel() } catch (e: Exception) {}
    }
}
