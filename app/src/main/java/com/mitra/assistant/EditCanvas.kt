package com.mitra.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/** Bublu Studio ka canvas - text, shapes, AI images, filters, effects, bg. */
class EditCanvas @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Item(
        val type: Int,
        var x: Float,
        var y: Float,
        var size: Float,
        var color: Int,
        var text: String,
        var bmp: Bitmap?
    )

    companion object {
        const val TEXT = 0
        const val CIRCLE = 1
        const val RECT = 2
        const val STAR = 3
        const val IMAGE = 4
        const val HEART = 5
    }

    val items = ArrayList<Item>()
    var bgColor: Int = 0xFF101733.toInt()
    var bgBmp: Bitmap? = null
    var drawBg = false
    var filter = 0      // 0 none, 1 warm, 2 cool, 3 mono, 4 vintage
    var effect = 0      // 0 none, 1 vignette, 2 glow, 3 scanlines

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var dragIdx = -1

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        when (e.action) {
            android.view.MotionEvent.ACTION_DOWN -> {
                dragIdx = hitTest(e.x, e.y)
                return true
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (dragIdx in items.indices) {
                    items[dragIdx].x = e.x
                    items[dragIdx].y = e.y
                    invalidate()
                }
                return true
            }
            android.view.MotionEvent.ACTION_UP -> {
                dragIdx = -1
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun hitTest(x: Float, y: Float): Int {
        for (i in items.indices.reversed()) {
            val it = items[i]
            val d = Math.hypot((x - it.x).toDouble(), (y - it.y).toDouble())
            if (d < it.size * 1.4) return i
        }
        return -1
    }

    fun addText(t: String, color: Int = Color.WHITE, size: Float = 48f, x: Float = -1f, y: Float = -1f) {
        items.add(Item(TEXT, if (x < 0) width / 2f else x, if (y < 0) height / 2f else y, size, color, t, null))
        invalidate()
    }

    fun addShape(kind: Int, color: Int, size: Float = 80f, x: Float = -1f, y: Float = -1f) {
        items.add(Item(kind, if (x < 0) width / 2f else x, if (y < 0) height / 2f else y, size, color, "", null))
        invalidate()
    }

    fun addImage(b: Bitmap, x: Float = -1f, y: Float = -1f, size: Float = 160f) {
        items.add(Item(IMAGE, if (x < 0) width / 2f else x, if (y < 0) height / 2f else y, size, Color.WHITE, "", b))
        invalidate()
    }

    fun undo() { if (items.isNotEmpty()) { items.removeAt(items.size - 1); invalidate() } }
    fun clearAll() { items.clear(); invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (drawBg) {
            val b = bgBmp
            if (b != null) canvas.drawBitmap(b, null, Rect(0, 0, width, height), paint)
            else canvas.drawColor(bgColor)
        }

        for (it in items) {
            paint.reset()
            paint.isAntiAlias = true
            paint.color = it.color
            when (it.type) {
                TEXT -> {
                    paint.textSize = it.size
                    paint.textAlign = Paint.Align.CENTER
                    paint.style = Paint.Style.FILL
                    paint.setShadowLayer(6f, 0f, 3f, 0xAA000000.toInt())
                    canvas.drawText(it.text, it.x, it.y, paint)
                    paint.clearShadowLayer()
                }
                CIRCLE -> canvas.drawCircle(it.x, it.y, it.size, paint)
                RECT -> canvas.drawRect(it.x - it.size, it.y - it.size / 2, it.x + it.size, it.y + it.size / 2, paint)
                STAR -> canvas.drawPath(starPath(it.x, it.y, it.size), paint)
                HEART -> canvas.drawPath(heartPath(it.x, it.y, it.size), paint)
                IMAGE -> it.bmp?.let { b ->
                    val r = RectF(it.x - it.size, it.y - it.size, it.x + it.size, it.y + it.size)
                    canvas.drawBitmap(b, null, r, paint)
                }
            }
        }

        // effects
        when (effect) {
            1 -> {
                val g = RadialGradient(
                    width / 2f, height / 2f, width * 0.75f,
                    intArrayOf(Color.TRANSPARENT, 0xCC000000.toInt()), null, Shader.TileMode.CLAMP
                )
                paint.reset(); paint.shader = g
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            }
            2 -> {
                paint.reset(); paint.style = Paint.Style.STROKE
                paint.strokeWidth = 14f
                paint.shader = LinearGradient(
                    0f, 0f, width.toFloat(), height.toFloat(),
                    intArrayOf(0xFF6C4BFF.toInt(), 0xFF22D3EE.toInt(), 0xFFFF4D9D.toInt()), null,
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(7f, 7f, width - 7f, height - 7f, paint)
            }
            3 -> {
                paint.reset(); paint.color = 0x33000000
                var y = 0f
                while (y < height) { canvas.drawRect(0f, y, width.toFloat(), y + 2f, paint); y += 6f }
            }
        }

        // colour filter tint
        val tint = when (filter) {
            1 -> 0x33FF9A3C
            2 -> 0x3322D3EE
            3 -> 0x55000000
            4 -> 0x33FFD28A
            else -> 0
        }
        if (tint != 0) {
            paint.reset(); paint.color = tint
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
        if (filter == 3) {
            // mono-ish overlay
            paint.reset(); paint.color = 0x22000000
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
    }

    private fun starPath(cx: Float, cy: Float, r: Float): Path {
        val p = Path()
        for (i in 0 until 10) {
            val rad = if (i % 2 == 0) r else r * 0.45f
            val a = Math.toRadians((i * 36 - 90).toDouble())
            val x = cx + (rad * Math.cos(a)).toFloat()
            val y = cy + (rad * Math.sin(a)).toFloat()
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        return p
    }

    private fun heartPath(cx: Float, cy: Float, r: Float): Path {
        val p = Path()
        p.moveTo(cx, cy + r * 0.8f)
        p.cubicTo(cx - r * 1.6f, cy - r * 0.2f, cx - r * 0.6f, cy - r * 1.3f, cx, cy - r * 0.35f)
        p.cubicTo(cx + r * 0.6f, cy - r * 1.3f, cx + r * 1.6f, cy - r * 0.2f, cx, cy + r * 0.8f)
        p.close()
        return p
    }
}
