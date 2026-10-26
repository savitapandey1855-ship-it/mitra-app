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
    var captionAlpha: Int = 255

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

        // stylish caption
        if (line.isNotBlank()) {
            textPaint.color = captionColor
            textPaint.textSize = height * 0.048f
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            val maxWidth = width * 0.82f
            val words = line.split(" ")
            val lines = ArrayList<String>()
            var cur = ""
            for (w in words) {
                val test = if (cur.isEmpty()) w else "$cur $w"
                if (textPaint.measureText(test) > maxWidth) { lines.add(cur); cur = w } else cur = test
            }
            if (cur.isNotEmpty()) lines.add(cur)

            val lineH = textPaint.textSize * 1.3f
            val blockH = lineH * lines.size + height * 0.045f
            val baseY = height * 0.90f
            val top = baseY - blockH + height * 0.02f

            // rounded translucent bar
            val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            barPaint.color = 0xB3000000.toInt()
            barPaint.alpha = captionAlpha * 180 / 255
            canvas.drawRoundRect(
                RectF(width * 0.06f, top, width * 0.94f, baseY + height * 0.015f),
                height * 0.03f, height * 0.03f, barPaint
            )
            // accent line on the left
            val accPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            accPaint.color = 0xFF22D3EE.toInt()
            accPaint.alpha = captionAlpha
            canvas.drawRoundRect(
                RectF(width * 0.06f, top, width * 0.075f, baseY + height * 0.015f),
                height * 0.02f, height * 0.02f, accPaint
            )

            textPaint.alpha = captionAlpha
            textPaint.setShadowLayer(10f, 0f, 4f, 0xFF000000.toInt())
            val startY = baseY - (lines.size - 1) * lineH
            for ((i, l) in lines.withIndex()) {
                canvas.drawText(l, width / 2f, startY + i * lineH, textPaint)
            }
            textPaint.clearShadowLayer()
            textPaint.alpha = 255
        }
    }
}
