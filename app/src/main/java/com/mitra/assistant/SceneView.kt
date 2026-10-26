package com.mitra.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/** Story scene: 16:9 gradient background + stylish caption. */
class SceneView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var line: String = ""
    var captionColor: Int = 0xFFFFFFFF.toInt()
    var captionAlpha: Int = 255
    var paletteIndex: Int = 0

    private val palettes = arrayOf(
        intArrayOf(0xFF141E30.toInt(), 0xFF243B55.toInt()),
        intArrayOf(0xFF2B1055.toInt(), 0xFF7597DE.toInt()),
        intArrayOf(0xFF0F2027.toInt(), 0xFF203A43.toInt()),
        intArrayOf(0xFF3A1C71.toInt(), 0xFFD76D77.toInt()),
        intArrayOf(0xFF134E5E.toInt(), 0xFF71B280.toInt())
    )

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val target = (w * 9f / 16f).toInt()
        setMeasuredDimension(w, if (target in 1..h) target else h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val pal = palettes[paletteIndex % palettes.size]
        bgPaint.shader = android.graphics.LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(), pal[0], pal[1],
            android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        if (line.isBlank()) return

        textPaint.color = captionColor
        textPaint.textSize = height * 0.085f
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.DEFAULT_BOLD
        textPaint.alpha = captionAlpha
        textPaint.setShadowLayer(12f, 0f, 5f, 0xFF000000.toInt())

        val maxWidth = width * 0.84f
        val rows = ArrayList<String>()
        var cur = ""
        for (word in line.split(" ")) {
            val test = if (cur.isEmpty()) word else "$cur $word"
            if (textPaint.measureText(test) > maxWidth) {
                if (cur.isNotEmpty()) rows.add(cur)
                cur = word
            } else cur = test
        }
        if (cur.isNotEmpty()) rows.add(cur)

        val lineH = textPaint.textSize * 1.35f
        val centerY = height * 0.52f
        val firstY = centerY - (rows.size - 1) * lineH / 2f

        barPaint.color = 0x66000000
        barPaint.alpha = captionAlpha * 150 / 255
        val top = firstY - textPaint.textSize
        val bottom = firstY + (rows.size - 1) * lineH + textPaint.textSize * 0.5f
        canvas.drawRoundRect(
            RectF(width * 0.06f, top, width * 0.94f, bottom),
            height * 0.035f, height * 0.035f, barPaint
        )

        textPaint.clearShadowLayer()
        textPaint.setShadowLayer(12f, 0f, 5f, 0xFF000000.toInt())
        for ((i, row) in rows.withIndex()) {
            canvas.drawText(row, width / 2f, firstY + i * lineH, textPaint)
        }
        textPaint.clearShadowLayer()
        textPaint.alpha = 255
    }
}
