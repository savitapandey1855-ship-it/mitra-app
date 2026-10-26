package com.mitra.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Story banane ka kaam background me - app band karo tab bhi chalta rahe. */
class StoryService : Service() {

    private val chId = "bublu_story"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val topic = intent?.getStringExtra("topic") ?: "a friendly robot"
        val minutes = intent?.getIntExtra("minutes", 2) ?: 2
        KeyPool.init(this)

        startForeground(41, notif("Story ban rahi hai..."))
        val prefs = getSharedPreferences("mitra", MODE_PRIVATE)
        prefs.edit().putString("story_progress", "Story likh raha hoon...").apply()

        Thread {
            try {
                val sceneCount = (minutes * 6).coerceIn(6, 30)
                val prompt = "Write a NEW, different cartoon story for kids about: $topic. " +
                    "It must have exactly $sceneCount short scenes. For every scene give: " +
                    "\"bg\" = background image prompt (max 8 words), " +
                    "\"char\" = character image prompt (max 8 words, single character, plain white background), " +
                    "\"line\" = one narration line in HINDI, Devanagari script ONLY (max 14 words, no English letters). " +
                    "Reply ONLY as a JSON array like [{\"bg\":\"...\",\"char\":\"...\",\"line\":\"...\"}]"
                val raw = brain(prompt)
                val scenes = parse(raw)
                if (scenes.isEmpty()) {
                    prefs.edit().putString("story_progress", "FAIL: story nahi bani").apply()
                    updateNotif("Story nahi ban payi")
                    stopSelf()
                    return@Thread
                }

                val dir = File(cacheDir, "story").apply { mkdirs() }
                val arr = JSONArray()
                for ((i, s) in scenes.withIndex()) {
                    prefs.edit().putString("story_progress", "Scene ${i + 1}/${scenes.size} images...").apply()
                    updateNotif("Scene ${i + 1}/${scenes.size} bana raha hoon...")

                    val bg = fetch("${s.first}, cartoon style, vibrant, no text")
                    val ch = fetch("${s.second}, cartoon character, plain white background, full body")?.let { removeWhite(it) }

                    val bgFile = File(dir, "bg_$i.png")
                    val chFile = File(dir, "ch_$i.png")
                    bg?.let { FileOutputStream(bgFile).use { os -> it.compress(Bitmap.CompressFormat.PNG, 92, os) } }
                    ch?.let { FileOutputStream(chFile).use { os -> it.compress(Bitmap.CompressFormat.PNG, 92, os) } }

                    arr.put(
                        JSONObject()
                            .put("bg", if (bg != null) bgFile.absolutePath else "")
                            .put("ch", if (ch != null) chFile.absolutePath else "")
                            .put("line", s.third)
                    )

                    // frame bhi save karo (1080x1920)
                    saveFrame(bg, ch, s.third, i)
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
        if (gk != null) {
            val (ok, t) = Gemini.ask(gk, prompt)
            if (ok && t.isNotBlank()) return t
        }
        val sk = KeyPool.current("sarvam")
        if (sk != null) {
            val (ok, t) = SarvamChat.ask(sk, prompt)
            if (ok && t.isNotBlank()) return t
        }
        return null
    }

    private fun parse(raw: String?): List<Triple<String, String, String>> {
        if (raw == null) return emptyList()
        val s = raw.indexOf('[')
        val e = raw.lastIndexOf(']')
        if (s < 0 || e <= s) return emptyList()
        return try {
            val a = JSONArray(raw.substring(s, e + 1))
            val out = ArrayList<Triple<String, String, String>>()
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val bg = o.optString("bg", "").trim()
                val ch = o.optString("char", "").trim()
                val line = o.optString("line", "").trim()
                if (bg.isBlank() && ch.isBlank()) continue
                out.add(
                    Triple(
                        bg.ifBlank { "colourful cartoon background" },
                        ch.ifBlank { "cute cartoon character" },
                        line
                    )
                )
            }
            out
        } catch (ex: Exception) { emptyList() }
    }

    private fun fetch(prompt: String): Bitmap? {
        return try {
            val q = URLEncoder.encode(prompt, "UTF-8")
            val conn = URL("https://image.pollinations.ai/prompt/$q?width=768&height=768&nologo=true")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            BitmapFactory.decodeStream(conn.inputStream)
        } catch (e: Exception) { null }
    }

    private fun removeWhite(src: Bitmap): Bitmap {
        return try {
            val b = src.copy(Bitmap.Config.ARGB_8888, true)
            val w = b.width
            val h = b.height
            val px = IntArray(w * h)
            b.getPixels(px, 0, w, 0, 0, w, h)
            for (i in px.indices) {
                val c = px[i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val bl = c and 0xFF
                if (r > 232 && g > 232 && bl > 232) px[i] = 0
            }
            b.setPixels(px, 0, w, 0, 0, w, h)
            b
        } catch (e: Exception) { src }
    }

    private fun saveFrame(bg: Bitmap?, ch: Bitmap?, line: String, index: Int) {
        try {
            val w = 1080
            val h = 1920
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            if (bg != null) c.drawBitmap(bg, null, Rect(0, 0, w, h), null)
            else c.drawColor(0xFF0B1030.toInt())
            if (ch != null) {
                val th = h * 0.55f
                val tw = th * (ch.width.toFloat() / ch.height.toFloat())
                val left = (w - tw) / 2f
                c.drawBitmap(ch, null, RectF(left, h - th * 0.92f, left + tw, h - th * 0.92f + th), null)
            }
            // stylish caption bar
            if (line.isNotBlank()) {
                val tp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                tp.color = android.graphics.Color.WHITE
                tp.textSize = h * 0.045f
                tp.textAlign = android.graphics.Paint.Align.CENTER
                tp.typeface = android.graphics.Typeface.DEFAULT_BOLD
                tp.setShadowLayer(10f, 0f, 4f, 0xFF000000.toInt())
                val maxW = w * 0.82f
                val words = line.split(" ")
                val rows = ArrayList<String>()
                var curS = ""
                for (wd in words) {
                    val test = if (curS.isEmpty()) wd else "$curS $wd"
                    if (tp.measureText(test) > maxW) { rows.add(curS); curS = wd } else curS = test
                }
                if (curS.isNotEmpty()) rows.add(curS)
                val lineH = tp.textSize * 1.3f
                val baseY = h * 0.90f
                val top = baseY - (rows.size - 1) * lineH - h * 0.03f
                val barP = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                barP.color = 0xB3000000.toInt()
                c.drawRoundRect(RectF(w * 0.06f, top, w * 0.94f, baseY + h * 0.02f), h * 0.03f, h * 0.03f, barP)
                val accP = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                accP.color = 0xFF22D3EE.toInt()
                c.drawRoundRect(RectF(w * 0.06f, top, w * 0.075f, baseY + h * 0.02f), h * 0.02f, h * 0.02f, accP)
                val startY = baseY - (rows.size - 1) * lineH
                for ((ri, rw) in rows.withIndex()) c.drawText(rw, w / 2f, startY + ri * lineH, tp)
            }
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
