package com.mitra.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import org.json.JSONArray
import org.json.JSONObject

/** Background me Hindi story + 16:9 frames banata hai. */
class StoryService : Service() {

    private val chId = "bublu_story"
    private var err = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val topic = intent?.getStringExtra("topic") ?: "a friendly robot"
        val minutes = intent?.getIntExtra("minutes", 2) ?: 2
        KeyPool.init(this)

        startForeground(41, buildNotif("Story likh raha hoon..."))
        val prefs = getSharedPreferences("mitra", MODE_PRIVATE)
        prefs.edit().putString("story_progress", "Story likh raha hoon...").apply()

        Thread {
            try {
                val count = (minutes * 4).coerceIn(6, 40)
                val p1 = "Write a cartoon story for kids about: " + topic + ". " +
                        "Give exactly " + count + " short narration lines in HINDI (Devanagari only). " +
                        "Each line max 14 words, in story order. " +
                        "Reply ONLY as a JSON array of strings."
                var raw = brain(p1)
                var lines = parseLines(raw)
                if (lines.isEmpty()) {
                    raw = brain(p1 + " STRICT: only the JSON array.")
                    lines = parseLines(raw)
                }
                if (lines.isEmpty()) {
                    val msg = if (err.isBlank()) "story nahi bani" else err
                    prefs.edit().putString("story_progress", "FAIL: " + msg).apply()
                    updateNotif("Story nahi ban payi")
                    stopSelf()
                    return@Thread
                }

                val arr = JSONArray()
                for (i in 0 until lines.size) {
                    arr.put(JSONObject().put("line", lines[i]))
                }
                prefs.edit().putString("story_progress", "Frames bana raha hoon...").apply()
                StoryFrames.saveFrames(this, lines)
                prefs.edit()
                    .putString("story_json", arr.toString())
                    .putString("story_progress", "DONE")
                    .apply()
                updateNotif("Story ready! Bublu Story Studio kholo")
            } catch (e: Exception) {
                prefs.edit().putString("story_progress", "FAIL: " + (e.message ?: "error")).apply()
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
            err = "Koi API key nahi mili (admin panel me daalo)"
            return null
        }
        if (gk != null) {
            val r = Gemini.ask(gk, prompt)
            if (r.first && r.second.isNotBlank()) return r.second
            err = "Gemini: " + r.second
        }
        if (sk != null) {
            val r = SarvamChat.ask(sk, prompt)
            if (r.first && r.second.isNotBlank()) return r.second
            err = err + " | Sarvam: " + r.second
        }
        return null
    }

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
                    if (v is String) out.add(v)
                    else if (v is JSONObject) out.add(v.optString("line", ""))
                }
            } catch (ex: Exception) {}
        }
        if (out.isEmpty()) {
            val m = Regex("line\\s*[:=]\\s*\"?([^\",}\\]]+)").findAll(raw)
            for (x in m) out.add(x.groupValues[1])
        }
        if (out.isEmpty()) {
            for (x in raw.split("\n", "।")) out.add(x)
        }
        val clean = ArrayList<String>()
        for (x in out) {
            val c = cleanLine(x)
            if (c.length > 6) clean.add(c)
            if (clean.size >= 40) break
        }
        return clean
    }

    private fun cleanLine(src: String): String {
        var t = src
        t = t.replace(Regex("(?i)(bg|char|line|image|background)\\s*[:=]\\s*"), " ")
        val junk = charArrayOf('"', '*', '#', '`', '[', ']', '{', '}', '\\')
        for (j in junk) t = t.replace(j.toString(), " ")
        t = t.replace(Regex("\\s+"), " ").trim()
        t = t.trim(',', '.', ':', ';', ' ', '-')
        return t
    }

    private fun buildNotif(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            if (nm.getNotificationChannel(chId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(chId, "Bublu Story", NotificationManager.IMPORTANCE_LOW)
                )
            }
            return Notification.Builder(this, chId)
                .setContentTitle("Bublu Story Studio")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build()
        }
        return Notification.Builder(this)
            .setContentTitle("Bublu Story Studio")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
    }

    private fun updateNotif(text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(41, buildNotif(text))
        } catch (e: Exception) {}
    }
}
