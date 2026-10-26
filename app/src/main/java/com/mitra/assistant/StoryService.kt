package com.mitra.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Story banane ka kaam background me.
 * Sirf narration lines (Hindi) - koi background image nahi, isliye fast aur reliable.
 */
class StoryService : Service() {

    private val chId = "bublu_story"
    private var lastErr = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val topic = intent?.getStringExtra("topic") ?: "a friendly robot"
        val minutes = intent?.getIntExtra("minutes", 2) ?: 2
        KeyPool.init(this)

        startForeground(41, notif("Story likh raha hoon..."))
        val prefs = getSharedPreferences("mitra", MODE_PRIVATE)
        prefs.edit().putString("story_progress", "Story likh raha hoon...").apply()

        Thread {
            try {
                val sceneCount = (minutes * 4).coerceIn(6, 40)
                val prompt = "Write a cartoon story for kids about: $topic. " +
                    "Give exactly $sceneCount short narration lines in HINDI (Devanagari script only, no English letters), " +
                    "each line max 14 words, in story order. " +
                    "Reply ONLY as a JSON array of strings, like: [\"पहली लाइन\",\"दूसरी लाइन\"]"
                var raw = brain(prompt)
                var lines = parseLines(raw)
                if (lines.isEmpty()) {
                    raw = brain("$prompt\nSTRICT: only the JSON array, nothing else.")
                    lines = parseLines(raw)
                }
                if (lines.isEmpty() && !raw.isNullOrBlank()) {
                    lines = parseLines(raw)
                }
                if (lines.isEmpty()) {
                    prefs.edit().putString(
                        "story_progress",
                        "FAIL: ${lastErr.ifBlank { "story nahi bani" }}"
                    ).apply()
                    updateNotif("Story nahi ban payi")
                    stopSelf()
                    return@Thread
                }

                prefs.edit().putString("story_progress", "Scenes bana raha hoon...").apply()
                val arr = JSONArray()
                for ((i, line) in lines.withIndex()) {
                    arr.put(JSONObject().put("line", line).put("idx", i))
                    if (i % 3 == 0) {
                        prefs.edit().putString("story_progress", "Scene ${i + 1}/${lines.size}").apply()
                        updateNotif("Scene ${i + 1}/${lines.size}")
                    }
                    saveFrame(line, i)
                }
                prefs.edit()
                    .putString("story_json", arr.toString())
                    .putString("story_progress", "DONE")
                    .apply()
                updateNotif("Story ready! Bublu Story Studio kholo 🎬")
            } catch (e: Exception) {
                prefs.edit().putString("story_progress", "FAIL: ${e.message}").apply()
                updateNotif("Story fail ho gayi")
            }
            stopSelf()
        }.start()

        return START_NOT_STICKY
    }

    private fun brain(prompt: String): String? {
        val gk = KeyPool.current("gemini")
        val sk = KeyPool.current("sarvam")
        if (gk == null && sk == null) {
            lastErr = "Koi API key nahi mili (admin panel me daalo)"
            return null
        }
        if (gk != null) {
            val (ok, t) = Gemini.ask(gk, prompt)
            if (ok && t.isNotBlank()) return t
            lastErr = "Gemini: $t"
        }
        if (sk != null) {
            val (ok, t) = SarvamChat.ask(sk, prompt)
            if (ok && t.isNotBlank()) return t
            lastErr = "$lastErr | Sarvam: $t".trim()
        }
        return null
    }

    /** JSON ho ya kachra - sirf saaf narration lines nikaalo. */
    private fun parseLines(raw: String?): List<String> {
        if (raw == null) return emptyList()
        val out = ArrayList<String>()

        val s = raw.indexOf('[')
        val e = raw.lastIndexOf(']')
        if (s >= 0 && e > s) {
            try {
                val a = JSONArray(raw.substring(s, e + 1))
                for (i in 0 until a.length()) {
                    when (val v = a.opt(i)) {
                        is String -> out.add(v)
                        is JSONObject -> out.add(v.optString("line", ""))
                    }
                }
            } catch (ex: Exception) {}
        }

        if (out.isEmpty()) {
            Regex("line\\s*[:=]\\s*\"?([^\",}\\]]+)").findAll(raw).forEach {
                out.add(it.groupValues[1])
            }
        }
        if (out.isEmpty()) {
            raw.split("\n", "।").forEach { out.add(it) }
        }
        return out.map { cleanLine(it) }.filter { it.length > 6 }.take(40)
    }

    private fun cleanLine(s: String): String {
        var t = s
        t = t.replace(Regex("(?i)\\b(bg|char|line|image|background)\\s*[:=]\\s*"), " ")
        t = t.replace("\"", " ").replace("*", " ").replace("#", " ").replace("`", " ")
            .replace("[", " ").replace("]", " ").replace("{", " ").replace("}", " ")
        t = t.replace(Regex("\\s+"), " ").trim()
        return t.trim(',', '.', ':', ';', ' ', '-')
    }

    /** 16:9 frame - gradient bg + bada stylish caption. */
    private fun saveFrame(line: String, index: Int) {
        try {
            val w = 1280
            val h = 720
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)

            val palette = arrayOf(
                intArrayOf(0xFF141E30.toInt(), 0xFF243B55.toInt()),
                intArrayOf(0xFF2B1055.toInt(), 0xFF7597DE.toInt()),
                intArrayOf(0xFF0F2027.toInt(), 0xFF203A43.toInt()),
                intArrayOf(0xFF3A1C71.toInt(), 0xFFD76D77.toInt()),
                intArrayOf(0xFF134E5E.toInt(), 0xFF71B280.toInt())
            )
            val pal = palette[index % palette.size]
            val bgP = Paint()
            bgP.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), pal[0], pal[1], Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgP)

            // caption bar
            val tp = Paint(Paint.ANTI_ALIAS_FLAG)
            tp.color = Color.WHITE
            tp.textSize = h * 0.075f
            tp.textAlign = Paint.Align.CENTER
            tp.typeface = Typeface.DEFAULT_BOLD
            tp.setShadowLayer(12f, 0f, 5f, 0xFF000000.toInt())

            val maxW = w * 0.84f
            val rows = ArrayList<String>()
            var curS = ""
            for (wd in line.split(" ")) {
                val test = if (curS.isEmpty()) wd else "$curS $wd"
                if (tp.measureText(test) > maxW) { rows.add(curS); curS = wd } else curS = test
            }
            if (curS.isNotEmpty()) rows.add(curS)

            val lineH = tp.textSize * 1.35f
            val baseY = h * 0.55f + (rows.size - 1) * lineH / 2f

            val barP = Paint(Paint.ANTI_ALIAS_FLAG)
            barP.color = 0x66000000
            val top = baseY - (rows.size - 1) * lineH - tp.textSize
            c.drawRoundRect(RectF(w * 0.06f, top, w * 0.94f, baseY + tp.textSize * 0.6f), 28f, 28f, barP)

            val accP = Paint(Paint.ANTI_ALIAS_FLAG)
            accP.color = 0xFF22D3EE.toInt()
            c.drawRoundRect(RectF(w * 0.06f, top, w * 0.075f, baseY + tp.textSize * 0.6f), 12f, 12f, accP)

            val startY = baseY - (rows.size - 1) * lineH
            for ((ri, rw) in rows.withIndex()) c.drawText(rw, w / 2f, startY + ri * lineH, tp)

            val values = android.content.ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "story_${System.currentTimeMillis()}_$index.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= 29)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/BubluStudio")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                contentResolver.openOutputStream(it)?.use { os ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 92, os)
                }
            }
        } catch (e: Exception) {}
    }

    private fun notif(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(chId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(chId, "Bublu Story", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, chId)
                .setContentTitle("Bublu Story Studio")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build()
        else
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Bublu Story Studio")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build()
    }

    private fun updateNotif(text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(41, notif(text))
        } catch (e: Exception) {}
    }
}
