package com.mitra.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Story Studio ka scene: background + character + caption. */
class SceneView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var bg: Bitmap? = null
    var character: Bitmap? = null
    var line: String = ""
    var captionColor: Int = Color.WHITE

    /** 0 = left edge, 0.5 = centre, 1 = right edge */
    var charX: Float = 0.5f
    var charScale: Float = 0.55f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // background
        val b = bg
        if (b != null) canvas.drawBitmap(b, null, Rect(0, 0, width, height), paint)
        else canvas.drawColor(0xFF0B1030.toInt())

        // character
        val c = character
        if (c != null) {
            val targetH = height * charScale
            val ratio = c.width.toFloat() / c.height.toFloat()
            val targetW = targetH * ratio
            val cx = (width - targetW / 2f) + (charX - 0.5f) * (width - targetW)
            val cy = height - targetH * 0.92f
            canvas.drawBitmap(c, null, RectF(cx, cy, cx + targetW, cy + targetH), paint)
        }

        // caption
        if (line.isNotBlank()) {
            textPaint.color = captionColor
            textPaint.textSize = height * 0.045f
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.setShadowLayer(8f, 0f, 4f, 0xDD000000.toInt())
            val maxWidth = width * 0.86f
            val words = line.split(" ")
            val lines = ArrayList<String>()
            var cur = ""
            for (w in words) {
                val test = if (cur.isEmpty()) w else "$cur $w"
                if (textPaint.measureText(test) > maxWidth) { lines.add(cur); cur = w } else cur = test
            }
            if (cur.isNotEmpty()) lines.add(cur)
            val startY = height * 0.88f - (lines.size - 1) * textPaint.textSize * 1.25f
            for ((i, l) in lines.withIndex()) {
                canvas.drawText(l, width / 2f, startY + i * textPaint.textSize * 1.25f, textPaint)
            }
            textPaint.clearShadowLayer()
        }
    }
}
