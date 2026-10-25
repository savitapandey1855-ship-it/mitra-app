package com.mitra.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.animation.ValueAnimator
import android.view.View

/** Bublu ka chehra - aankhon ke pupils sach me ghoomte hain aur palk bhi jhapakti hai. */
class CharacterView(context: Context) : View(context) {

    private var mood = 0        // 0 idle, 1 listen, 2 think, 3 happy, 4 sad, 5 sleep, 6 wake, 7 laugh
    private var design = 0
    private val palettes = arrayOf(
        intArrayOf(0xFFBFF3FF.toInt(), 0xFF5B8BFF.toInt(), 0xFF6A3BE0.toInt()),
        intArrayOf(0xFFDDFBFF.toInt(), 0xFF28C6FF.toInt(), 0xFF0B49C8.toInt()),
        intArrayOf(0xFFFFE3F7.toInt(), 0xFFC56BFF.toInt(), 0xFF6A1FB0.toInt()),
        intArrayOf(0xFFE4FFE9.toInt(), 0xFF3ED98A.toInt(), 0xFF0B7A45.toInt())
    )
    private var lookX = 0f      // -1..1
    private var lookY = 0f
    private var blink = 0f      // 0 open .. 1 closed

    private val h = Handler(Looper.getMainLooper())
    private var running = false

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val darkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF20264A.toInt() }
    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val hiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26000000 }
    private val mouthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF20264A.toInt(); style = Paint.Style.STROKE; strokeWidth = 4.5f; strokeCap = Paint.Cap.ROUND
    }

    private var grad: RadialGradient? = null

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            animateLook((Math.random() * 2 - 1).toFloat(), (Math.random() * 1.6 - 0.8).toFloat())
            if (Math.random() < 0.6) blinkOnce()
            h.postDelayed(this, (1100 + Math.random() * 2200).toLong())
        }
    }

    fun startEyes() { running = true; h.removeCallbacks(tick); h.postDelayed(tick, 900) }
    fun stopEyes() { running = false; h.removeCallbacks(tick) }

    fun setMoodId(resId: Int) {
        mood = when (resId) {
            R.drawable.char_listen -> 1
            R.drawable.char_think -> 2
            R.drawable.char_happy -> 3
            R.drawable.char_sad -> 4
            R.drawable.char_sleep -> 5
            R.drawable.char_wake -> 6
            R.drawable.char_laugh -> 7
            else -> 0
        }
        invalidate()
    }

    fun setDesign(d: Int) { design = d.coerceIn(0, palettes.size - 1); grad = null; invalidate() }

    private fun animateLook(tx: Float, ty: Float) {
        val sx = lookX
        val sy = lookY
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 260
        a.addUpdateListener { va ->
            val f = va.animatedValue as Float
            lookX = sx + (tx - sx) * f
            lookY = sy + (ty - sy) * f
            invalidate()
        }
        a.start()
    }

    private fun blinkOnce() {
        val a = ValueAnimator.ofFloat(0f, 1f, 0f)
        a.duration = 180
        a.addUpdateListener { va -> blink = va.animatedValue as Float; invalidate() }
        a.start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = width.toFloat()
        if (s <= 0f) return
        canvas.save()
        canvas.scale(s / 120f, s / 120f)

        canvas.drawOval(30f, 104f, 90f, 118f, shadowPaint)

        val pal = palettes[design.coerceIn(0, palettes.size - 1)]
        if (grad == null) grad = RadialGradient(
            42f, 38f, 78f, pal, floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        bodyPaint.shader = grad
        canvas.drawCircle(60f, 58f, 46f, bodyPaint)
        bodyPaint.shader = null

        canvas.drawOval(24f, 23f, 56f, 45f, hiPaint)

        val lx = lookX * 3.2f
        val ly = lookY * 2.6f
        val eyeRy = 7f * (1f - blink * 0.92f)
        drawEye(canvas, 45f + lx, 56f + ly, 7f, eyeRy)
        drawEye(canvas, 75f + lx, 56f + ly, 7f, eyeRy)

        val path = Path()
        when (mood) {
            1, 6 -> canvas.drawOval(54f, 70f, 66f, 79f, darkPaint)
            2, 5 -> canvas.drawLine(52f, 74f, 68f, 74f, mouthPaint)
            4 -> { path.moveTo(50f, 78f); path.quadTo(60f, 70f, 70f, 78f); canvas.drawPath(path, mouthPaint) }
            7 -> { path.moveTo(46f, 70f); path.quadTo(60f, 84f, 74f, 70f); canvas.drawPath(path, mouthPaint) }
            else -> { path.moveTo(50f, 72f); path.quadTo(60f, 80f, 70f, 72f); canvas.drawPath(path, mouthPaint) }
        }
        canvas.restore()
    }

    private fun drawEye(canvas: Canvas, cx: Float, cy: Float, rx: Float, ry: Float) {
        val rr = ry.coerceAtLeast(0.9f)
        canvas.drawOval(cx - rx, cy - rr, cx + rx, cy + rr, darkPaint)
        if (rr > 3f) canvas.drawCircle(cx - 2f, cy - 3f, 2.5f, whitePaint)
    }
}
