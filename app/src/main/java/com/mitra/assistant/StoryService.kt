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
        val mode = intent?.getStringExtra("mode") ?: "story"
        KeyPool.init(this)

        startForeground(41, notif("Story ban rahi hai..."))
        val prefs = getSharedPreferences("mitra", MODE_PRIVATE)
        prefs.edit().putString("story_progress", "Story likh raha hoon...").apply()

        Thread {
            try {
                val sceneCount = (minutes * 6).coerceIn(8, 30)

                // 1) seedha plain lines maango (JSON nahi - isi se ek line ka bug aata tha)
                val plainPrompt = "Ek nayi cartoon kahani sunao bacchon ke liye. Topic: " + topic + ". " +
                        "Bilkul " + sceneCount + " scenes likho, ek ke neeche ek, is EXACT format me:\n" +
                        "<kahani ki line HINDI me, Devanagari, 8-14 shabd> | <english background prompt, 5-8 words, no people> | <pose: standing/waving/running/sitting/jumping>\n" +
                        "Example: जंगल में शेर दहाड़ता है | dark jungle with tall trees at night | standing\n" +
                        "Sirf ye lines likho - koi number, JSON, heading, explanation nahi."
                var lines = parsePlain(brain(plainPrompt))

                // 2) kam lines aayi to dobara maango
                if (lines.size < 4) {
                    val p2 = "Topic: " + topic + ". Likho " + sceneCount + " scenes is format me:\n" +
                            "Hindi line (Devanagari) | english background prompt | pose\n" +
                            "Har scene nayi line me, kahani ke kram me. Aur kuch nahi likho."
                    lines = parsePlain(brain(p2))
                }

                // 3) phir bhi kam to JSON wale jawab se nikaalo
                if (lines.size < 4) {
                    val p3 = "Kahani topic: " + topic + ". " + sceneCount + " narration lines Hindi me. " +
                            "Reply ONLY as a JSON array of strings."
                    lines = parseLines(brain(p3))
                }

                // 4) aakhri rasta: jo bhi text mila usko vaakya me todo
                if (lines.size < 4) {
                    val rawAll = brain(plainPrompt)
                    lines = splitSentences(rawAll)
                }

                if (lines.isEmpty()) {
                    val msg = if (lastErr.isBlank()) "story nahi bani" else lastErr
                    prefs.edit().putString("story_progress", "FAIL: " + msg).apply()
                    updateNotif("Story nahi ban payi")
                    stopSelf()
                    return@Thread
                }

                // har scene ka apna bg prompt + pose (story ke hisaab se)
                val scenes = ArrayList<Triple<String, String, String>>()
                for (ln in lines) {
                    val parts = ln.split("|")
                    val text = parts[0].trim()
                    val bgPrompt = if (parts.size > 1) parts[1].trim() else ""
                    val pose = if (parts.size > 2) parts[2].trim() else "standing"
                    if (text.isBlank()) continue
                    scenes.add(
                        Triple(
                            bgPrompt.ifBlank { "cartoon scene of " + topic + ", colourful, no text" },
                            pose.ifBlank { "standing" },
                            text
                        )
                    )
                }
                if (scenes.isEmpty()) {
                    prefs.edit().putString("story_progress", "FAIL: scenes nahi bane").apply()
                    stopSelf()
                    return@Thread
                }

                val dir = File(cacheDir, "story").apply { mkdirs() }

                // Images sirf tab jab Cloudflare key ho (warna gradient - koi wait nahi)
                val useImages = ImageGen.hasCloudflare(this)
                val bgPaths = ArrayList<String>()
                val chPaths = ArrayList<String>()
                if (useImages) {
                    for ((i, sc) in scenes.withIndex()) {
                        prefs.edit().putString("story_progress", "Image ${i + 1}/${scenes.size} (Cloudflare)...").apply()
                        updateNotif("Image ${i + 1}/${scenes.size}")

                        val bgBmp = ImageGen.gen(
                            this,
                            sc.first + ", 3D render cartoon background, cinematic depth, no text, no people",
                            768, 768
                        )
                        val bgFile = File(dir, "bg_$i.png")
                        if (bgBmp != null) {
                            val fos = FileOutputStream(bgFile)
                            bgBmp.compress(Bitmap.CompressFormat.PNG, 92, fos)
                            fos.close()
                            bgPaths.add(bgFile.absolutePath)
                        } else {
                            bgPaths.add("")
                        }

                        val poseWord = if (sc.second.isBlank()) "standing" else sc.second
                        val chBmp = ImageGen.gen(
                            this,
                            "cute 3D Pixar style cartoon character for this story: " + topic +
                                ", " + poseWord + " pose, full body, plain white background, studio lighting",
                            768, 768
                        )
                        val chFile = File(dir, "ch_$i.png")
                        if (chBmp != null) {
                            val fos2 = FileOutputStream(chFile)
                            chBmp.compress(Bitmap.CompressFormat.PNG, 92, fos2)
                            fos2.close()
                            chPaths.add(chFile.absolutePath)
                        } else {
                            chPaths.add("")
                        }
                    }
                    if (bgPaths.all { it.isBlank() }) {
                        prefs.edit().putString("story_img_note", "Image nahi bani - " + ImageGen.lastError).apply()
                    } else {
                        prefs.edit().putString("story_img_note", "").apply()
                    }
                } else {
                    prefs.edit().putString(
                        "story_img_note",
                        "Cloudflare token nahi mila - admin panel me daalo, phir images aayengi."
                    ).apply()
                }

                val arr = JSONArray()
                for ((i, sc) in scenes.withIndex()) {
                    prefs.edit().putString("story_progress", "Scene ${i + 1}/${scenes.size}").apply()

                    val bP = if (i < bgPaths.size) bgPaths[i] else ""
                    val cP = if (i < chPaths.size) chPaths[i] else ""

                    val bg: Bitmap? = if (bP.isNotBlank()) android.graphics.BitmapFactory.decodeFile(bP) else null
                    val ch: Bitmap? = if (cP.isNotBlank()) android.graphics.BitmapFactory.decodeFile(cP) else null

                    arr.put(
                        JSONObject()
                            .put("bg", bP)
                            .put("ch", cP)
                            .put("line", sc.third)
                    )

                    saveFrame(bg, ch, sc.third, i)
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

    private var lastErr = ""

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
            lastErr = (lastErr + " | Sarvam: $t").trim()
        }
        return null
    }

    /** JSON array (strings ya objects) se lines nikaalo. */
    private fun parseLines(raw: String?): List<String> {
        if (raw == null) return ArrayList<String>()
        val out = ArrayList<String>()
        val s = raw.indexOf('[')
        val e = raw.lastIndexOf(']')
        if (s >= 0 && e > s) {
            try {
                val a = JSONArray(raw.substring(s, e + 1))
                for (i in 0 until a.length()) {
                    val v = a.opt(i)
                    if (v is String) out.add(cleanLine(v))
                    else if (v is JSONObject) out.add(cleanLine(v.optString("line", "")))
                }
            } catch (ex: Exception) {}
        }
        val clean = ArrayList<String>()
        for (x in out) if (x.length > 8) clean.add(x)
        return clean
    }

    /** Plain text (numbered lines bhi) se saaf Hindi lines nikaalo. */
    private fun parsePlain(raw: String?): List<String> {
        if (raw == null) return ArrayList<String>()
        val out = ArrayList<String>()
        for (line in raw.split("\n")) {
            var t = line.trim()
            // numbering / bullets hatao: "1.", "1)", "-", "*", "•"
            t = t.replace(Regex("^[\\s\\-\\*\\u2022]+"), "")
            t = t.replace(Regex("^\\d+\\s*[\\.\\)\\-:]\\s*"), "")
            t = t.replace(Regex("""^["']+|["']+$"""), "")
            if (t.contains("{") || t.contains("}") || t.contains("\"bg\"") || t.contains("\"char\"")) continue
            if (t.startsWith("Scene") || t.startsWith("Here")) continue
            val c = cleanLine(t)
            if (c.length > 8) out.add(c)
            if (out.size >= 40) break
        }
        return out
    }

    /** Ek bade paragraph ko vaakyon me todo. */
    private fun splitSentences(raw: String?): List<String> {
        if (raw == null) return ArrayList<String>()
        val out = ArrayList<String>()
        val parts = raw.split("\n", "।", "!", "?", ". ")
        for (p in parts) {
            val c = cleanLine(p)
            if (c.length > 10) out.add(c)
            if (out.size >= 40) break
        }
        return out
    }

    /** AI ne JSON nahi diya - plain text / kachre se sirf narration lines nikaalo. */
    private fun fallbackScenes(raw: String, topic: String): List<Triple<String, String, String>> {
        val lines = ArrayList<String>()

        // 1) "line" ki value dhoondho (quotes ho ya na ho)
        val r1 = Regex("\"line\"\\s*[:=]\\s*\"([^\"]+)\"")
        for (m in r1.findAll(raw)) lines.add(m.groupValues[1])
        if (lines.isEmpty()) {
            val r2 = Regex("(?i)line\\s*\"?\\s*[:=]\\s*\"?([^\",}\\]]+)")
            for (m in r2.findAll(raw)) lines.add(m.groupValues[1])
        }

        // 2) warna line-by-line saaf karo
        if (lines.isEmpty()) {
            for (x in raw.split("\n", "।")) lines.add(x)
        }

        val clean = ArrayList<String>()
        for (x in lines) {
            val c = cleanLine(x)
            if (c.length > 8) clean.add(c)
            if (clean.size >= 20) break
        }
        if (clean.isEmpty()) return emptyList()
        return clean.map {
            Triple(
                "cartoon scene of " + topic + ", colourful background, no text",
                "cute cartoon character for " + topic + ", plain white background, full body",
                it
            )
        }
    }

    /** JSON ke tukde (bg:, char:, quotes) hata ke sirf story line chhodo. */
    private fun cleanLine(src: String): String {
        var t = src
        // pehle "line": ... wali value nikaalo
        val m = Regex("(?i)\"?line\"?\\s*[:=]\\s*\"?([^\"]+)").find(t)
        if (m != null) t = m.groupValues[1]
        // baaki keys hatao
        t = t.replace(Regex("(?i)\"?(bg|char|line|image|background|idx)\"?\\s*[:=]\\s*"), " ")
        val junk = charArrayOf('"', '*', '#', '`', '[', ']', '{', '}', '\\')
        for (j in junk) t = t.replace(j.toString(), " ")
        t = t.replace(Regex("\\s+"), " ").trim()
        t = t.trim(',', '.', ':', ';', ' ', '-')
        return t
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
                val line = cleanLine(o.optString("line", ""))
                if (line.isBlank()) continue
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
            val vertical = bg != null || ch != null
            val w = if (vertical) 1080 else 1280
            val h = if (vertical) 1920 else 720
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
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures")
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
