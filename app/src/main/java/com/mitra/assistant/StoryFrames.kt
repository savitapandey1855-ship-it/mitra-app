package com.mitra.assistant

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.provider.MediaStore

/** 16:9 story frames (gradient + stylish caption). */
object StoryFrames {

    private val palettes = arrayOf(
        intArrayOf(0xFF141E30.toInt(), 0xFF243B55.toInt()),
        intArrayOf(0xFF2B1055.toInt(), 0xFF7597DE.toInt()),
        intArrayOf(0xFF0F2027.toInt(), 0xFF203A43.toInt()),
        intArrayOf(0xFF3A1C71.toInt(), 0xFFD76D77.toInt()),
        intArrayOf(0xFF134E5E.toInt(), 0xFF71B280.toInt())
    )

    fun buildFrame(line: String, index: Int): Bitmap {
        val w = 1280
        val h = 720
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        val pal = palettes[index % palettes.size]
        val bgP = Paint()
        bgP.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(), pal[0], pal[1], Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgP)

        val tp = Paint(Paint.ANTI_ALIAS_FLAG)
        tp.color = Color.WHITE
        tp.textSize = h * 0.085f
        tp.textAlign = Paint.Align.CENTER
        tp.typeface = Typeface.DEFAULT_BOLD
        tp.setShadowLayer(12f, 0f, 5f, 0xFF000000.toInt())

        val maxW = w * 0.84f
        val rows = ArrayList<String>()
        var cur = ""
        for (word in line.split(" ")) {
            val test = if (cur.isEmpty()) word else "$cur $word"
            if (tp.measureText(test) > maxW) {
                if (cur.isNotEmpty()) rows.add(cur)
                cur = word
            } else cur = test
        }
        if (cur.isNotEmpty()) rows.add(cur)

        val lineH = tp.textSize * 1.35f
        val firstY = h * 0.52f - (rows.size - 1) * lineH / 2f
        val top = firstY - tp.textSize
        val bottom = firstY + (rows.size - 1) * lineH + tp.textSize * 0.5f

        val barP = Paint(Paint.ANTI_ALIAS_FLAG)
        barP.color = 0x66000000
        c.drawRoundRect(RectF(w * 0.06f, top, w * 0.94f, bottom), 26f, 26f, barP)

        val accP = Paint(Paint.ANTI_ALIAS_FLAG)
        accP.color = 0xFF22D3EE.toInt()
        c.drawRoundRect(RectF(w * 0.06f, top, w * 0.075f, bottom), 10f, 10f, accP)

        for (i in 0 until rows.size) {
            c.drawText(rows[i], w / 2f, firstY + i * lineH, tp)
        }
        return bmp
    }

    fun saveFrames(ctx: Context, lines: List<String>) {
        for (i in 0 until lines.size) {
            try {
                val bmp = buildFrame(lines[i], i)
                val values = ContentValues()
                values.put(MediaStore.Images.Media.DISPLAY_NAME, "story_" + System.currentTimeMillis() + "_" + i + ".png")
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= 29) {
                    values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/BubluStudio")
                }
                val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    val os = ctx.contentResolver.openOutputStream(uri)
                    if (os != null) {
                        bmp.compress(Bitmap.CompressFormat.PNG, 92, os)
                        os.close()
                    }
                }
            } catch (e: Exception) {}
        }
    }
}
