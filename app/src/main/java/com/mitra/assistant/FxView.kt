package com.mitra.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Screen pe rangeen effects (particles, confetti, ripple, hearts) - sirf dikhte hain, bolte nahi. */
class FxView(context: Context) : View(context) {

    private class P(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        var life: Float, var maxLife: Float, var color: Int, var size: Float, var kind: Int
    )

    private val parts = ArrayList<P>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var last = 0L
    private val palette = intArrayOf(
        0xFFFF5252.toInt(), 0xFFFFD740.toInt(), 0xFF40C4FF.toInt(),
        0xFF69F0AE.toInt(), 0xFFE040FB.toInt(), 0xFFFFAB40.toInt(),
        0xFF18FFFF.toInt(), 0xFFFF80AB.toInt()
    )

    private fun col() = palette[(Math.random() * palette.size).toInt()]

    fun burst(cx: Float, cy: Float, n: Int) {
        for (i in 0 until n) {
            val a = Math.random() * 2 * Math.PI
            val sp = (3 + Math.random() * 8).toFloat()
            parts.add(
                P(cx, cy, (Math.cos(a) * sp).toFloat(), (Math.sin(a) * sp).toFloat(),
                    0f, (35 + Math.random() * 40).toFloat(), col(), (3 + Math.random() * 5).toFloat(), 0)
            )
        }
        invalidate()
    }

    fun confetti(w: Int, h: Int, n: Int) {
        for (i in 0 until n) {
            parts.add(
                P((Math.random() * w).toFloat(), (-Math.random() * h * 0.3f).toFloat(),
                    ((Math.random() - 0.5) * 2).toFloat(), (2 + Math.random() * 4).toFloat(),
                    0f, (90 + Math.random() * 70).toFloat(), col(), (4 + Math.random() * 6).toFloat(), 1)
            )
        }
        invalidate()
    }

    fun ripple(cx: Float, cy: Float, color: Int) {
        parts.add(P(cx, cy, 0f, 0f, 0f, 45f, color, 10f, 2))
        parts.add(P(cx, cy, 0f, 0f, -14f, 45f, color, 10f, 2))
        invalidate()
    }

    fun sparkles(cx: Float, cy: Float, n: Int) {
        for (i in 0 until n) {
            val a = Math.random() * 2 * Math.PI
            val r = (20 + Math.random() * 60).toFloat()
            parts.add(
                P(cx + (Math.cos(a) * r).toFloat(), cy + (Math.sin(a) * r).toFloat(),
                    0f, (-0.5 - Math.random() * 1.5).toFloat(),
                    0f, (40 + Math.random() * 30).toFloat(), col(), (2 + Math.random() * 3).toFloat(), 3)
            )
        }
        invalidate()
    }

    fun hearts(cx: Float, cy: Float, n: Int) {
        for (i in 0 until n) {
            parts.add(
                P(cx + ((Math.random() - 0.5) * 60).toFloat(), cy,
                    ((Math.random() - 0.5) * 1.5).toFloat(), (-1 - Math.random() * 2).toFloat(),
                    0f, (60 + Math.random() * 40).toFloat(), 0xFFFF4081.toInt(), (6 + Math.random() * 5).toFloat(), 4)
            )
        }
        invalidate()
    }

    fun firework(w: Int, h: Int) {
        burst((Math.random() * w).toFloat(), (h * (0.2 + Math.random() * 0.4)).toFloat(), 34)
    }

    /** Bublu ke paas hi confetti (poori screen pe nahi). */
    fun confettiAround(cx: Float, cy: Float, n: Int) {
        for (i in 0 until n) {
            val a = Math.random() * 2 * Math.PI
            val r = (40 + Math.random() * 160).toFloat()
            parts.add(
                P(cx + (Math.cos(a) * r).toFloat(), cy + (Math.sin(a) * r * 0.6f).toFloat(),
                    0f, (1.5 + Math.random() * 2).toFloat(), 0f, (70 + Math.random() * 50).toFloat(),
                    col(), (4 + Math.random() * 5).toFloat(), 1)
            )
        }
        invalidate()
    }

    fun fireworkAt(cx: Float, cy: Float) { burst(cx, cy, 34) }

    /** Titliyan - Bublu ke aas paas udti hain. */
    fun butterflies(cx: Float, cy: Float, n: Int) {
        for (i in 0 until n) {
            parts.add(
                P(cx + ((Math.random() - 0.5) * 120).toFloat(), cy + ((Math.random() - 0.5) * 80).toFloat(),
                    0f, (-0.6f - Math.random().toFloat() * 0.6f), 0f, (130 + Math.random() * 60).toFloat(),
                    col(), (7 + Math.random() * 4).toFloat(), 5)
            )
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = System.currentTimeMillis()
        val dt = if (last == 0L) 1f else ((now - last) / 16f).coerceIn(0.5f, 3f)
        last = now
        val it = parts.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.life += dt
            if (p.life >= p.maxLife) { it.remove(); continue }
            p.x += p.vx * dt
            p.y += p.vy * dt
            if (p.kind == 0 || p.kind == 1) p.vy += 0.22f * dt
            if (p.kind == 5) p.x += Math.sin(p.life * 0.18).toFloat() * 2.0f * dt
            val alpha = (255 * (1 - p.life / p.maxLife)).toInt().coerceIn(0, 255)
            paint.color = p.color
            paint.alpha = alpha
            paint.style = Paint.Style.FILL
            when (p.kind) {
                0 -> canvas.drawCircle(p.x, p.y, p.size, paint)
                1 -> canvas.drawRect(p.x, p.y, p.x + p.size, p.y + p.size * 2, paint)
                2 -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 5f
                    canvas.drawCircle(p.x, p.y, p.size + p.life * 2.2f, paint)
                }
                3 -> {
                    canvas.drawCircle(p.x, p.y, p.size, paint)
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 2f
                    canvas.drawCircle(p.x, p.y, p.size * 2.2f, paint)
                }
                5 -> {
                    val flap = Math.abs(Math.sin(p.life * 0.5)).toFloat()
                    val w = p.size * (0.5f + 0.5f * flap)
                    canvas.drawOval(p.x - w, p.y - p.size * 0.8f, p.x, p.y + p.size * 0.8f, paint)
                    canvas.drawOval(p.x, p.y - p.size * 0.8f, p.x + w, p.y + p.size * 0.8f, paint)
                    paint.color = 0xFF2A2A44.toInt()
                    canvas.drawCircle(p.x, p.y, p.size * 0.22f, paint)
                }
                4 -> {
                    canvas.drawCircle(p.x - p.size * 0.5f, p.y, p.size * 0.6f, paint)
                    canvas.drawCircle(p.x + p.size * 0.5f, p.y, p.size * 0.6f, paint)
                    val path = Path()
                    path.moveTo(p.x - p.size, p.y + p.size * 0.2f)
                    path.lineTo(p.x + p.size, p.y + p.size * 0.2f)
                    path.lineTo(p.x, p.y + p.size * 1.6f)
                    path.close()
                    canvas.drawPath(path, paint)
                }
            }
        }
        if (parts.isNotEmpty()) postInvalidateOnAnimation()
    }
}
