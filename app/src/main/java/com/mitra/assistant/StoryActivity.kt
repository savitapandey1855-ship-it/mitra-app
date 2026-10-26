package com.mitra.assistant

import android.animation.ValueAnimator
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** Bublu Story Studio - AI cartoon video apne app me. */
class StoryActivity : AppCompatActivity() {

    private data class Scene(
        val bgPrompt: String,
        val charPrompt: String,
        val line: String,
        var bg: Bitmap? = null,
        var ch: Bitmap? = null
    )

    private lateinit var sceneView: SceneView
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val scenes = ArrayList<Scene>()
    private var playing = false
    private var idx = 0
    private var topic = "a friendly robot"
    private var minutes = 2
    private var animator: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO)
        setContentView(R.layout.activity_story)
        KeyPool.init(this)

        sceneView = findViewById(R.id.scene)
        status = findViewById(R.id.status)

        topic = intent.getStringExtra("topic") ?: "a friendly robot"
        minutes = intent.getIntExtra("minutes", 2)

        tts = TextToSpeech(this) { s -> ttsReady = s == TextToSpeech.SUCCESS }

        findViewById<Button>(R.id.btnNew).setOnClickListener { askStory() }
        findViewById<Button>(R.id.btnPlay).setOnClickListener { play() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stop() }
        findViewById<Button>(R.id.btnNext).setOnClickListener { nextScene() }
        findViewById<Button>(R.id.btnSaveFrames).setOnClickListener { saveFrames() }
        findViewById<Button>(R.id.btnSaveStory).setOnClickListener { saveStoryText() }

        if (intent.getBooleanExtra("auto", false)) askStory()
    }

    private fun askStory() {
        val box = EditText(this)
        box.setTextColor(0xFF101733.toInt())
        box.setText(topic)
        val mins = EditText(this)
        mins.setTextColor(0xFF101733.toInt())
        mins.setText(minutes.toString())
        val wrap = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(android.widget.TextView(this@StoryActivity).apply { text = "Topic"; setTextColor(0xFF101733.toInt()) })
            addView(box)
            addView(android.widget.TextView(this@StoryActivity).apply { text = "Minutes (1-5)"; setTextColor(0xFF101733.toInt()) })
            addView(mins)
        }
        AlertDialog.Builder(this).setTitle("New cartoon story").setView(wrap)
            .setPositiveButton("Generate") { _, _ ->
                topic = box.text.toString().trim().ifBlank { "a friendly robot" }
                minutes = (mins.text.toString().toIntOrNull() ?: 2).coerceIn(1, 5)
                buildStory()
            }
            .setNegativeButton("Cancel", null).show()
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

    private fun buildStory() {
        status.text = "Story likh raha hoon..."
        val sceneCount = (minutes * 6).coerceIn(6, 30)
        Thread {
            val prompt = "Write a NEW, different cartoon story for kids about: $topic. " +
                "It must have exactly $sceneCount short scenes. For every scene give: " +
                "\"bg\" = background image prompt (max 8 words), " +
                "\"char\" = character image prompt (max 8 words, single character, plain white background), " +
                "\"line\" = one narration line (max 14 words). " +
                "Reply ONLY as a JSON array like [{\"bg\":\"...\",\"char\":\"...\",\"line\":\"...\"}]"
            val raw = brain(prompt)
            val parsed = parseScenes(raw)
            if (parsed.isEmpty()) {
                runOnUiThread { status.text = "Story nahi ban payi - Gemini key check karo" }
                return@Thread
            }
            runOnUiThread { status.text = "${parsed.size} scenes bane. Images laa raha hoon..." }
            for ((i, s) in parsed.withIndex()) {
                runOnUiThread { status.text = "Scene ${i + 1}/${parsed.size} - background..." }
                s.bg = fetch("${s.bgPrompt}, cartoon style, vibrant, no text")
                runOnUiThread { status.text = "Scene ${i + 1}/${parsed.size} - character..." }
                val c = fetch("${s.charPrompt}, cartoon character, plain white background, full body")
                s.ch = c?.let { removeWhite(it) }
            }
            runOnUiThread {
                scenes.clear()
                scenes.addAll(parsed)
                idx = 0
                status.text = "${scenes.size} scenes ready - Play dabao ▶️"
                showScene(0)
            }
        }.start()
    }

    private fun parseScenes(raw: String?): List<Scene> {
        if (raw == null) return emptyList()
        val s = raw.indexOf('[')
        val e = raw.lastIndexOf(']')
        if (s < 0 || e <= s) return emptyList()
        return try {
            val arr = JSONArray(raw.substring(s, e + 1))
            val out = ArrayList<Scene>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val bg = o.optString("bg", "").trim()
                val ch = o.optString("char", "").trim()
                val line = o.optString("line", "").trim()
                if (bg.isBlank() && ch.isBlank()) continue
                out.add(Scene(bg.ifBlank { "colourful cartoon background" }, ch.ifBlank { "cute cartoon character" }, line))
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

    /** Safed background hata do (character cutout). */
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

    private fun showScene(i: Int) {
        if (i !in scenes.indices) return
        val s = scenes[i]
        sceneView.bg = s.bg
        sceneView.character = s.ch
        sceneView.line = s.line
        sceneView.charX = 0.2f
        sceneView.invalidate()

        animator?.cancel()
        animator = ValueAnimator.ofFloat(0.2f, 0.8f).apply {
            duration = 2600
            addUpdateListener {
                sceneView.charX = it.animatedValue as Float
                sceneView.invalidate()
            }
            start()
        }
        status.text = "Scene ${i + 1}/${scenes.size}"
    }

    private fun play() {
        if (scenes.isEmpty()) { Toast.makeText(this, "Pehle story banao", Toast.LENGTH_SHORT).show(); return }
        playing = true
        idx = 0
        step()
    }

    private fun step() {
        if (!playing || idx >= scenes.size) { playing = false; return }
        showScene(idx)
        val line = scenes[idx].line
        if (ttsReady && line.isNotBlank()) {
            try {
                tts?.setLanguage(Locale("en", "IN"))
                tts?.speak(line, TextToSpeech.QUEUE_FLUSH, null, "s$idx")
            } catch (e: Exception) {}
        }
        val wait = (line.length * 90L + 2600L).coerceIn(4000L, 14000L)
        handler.postDelayed({
            if (playing) { idx++; step() }
        }, wait)
    }

    private fun stop() {
        playing = false
        try { tts?.stop() } catch (e: Exception) {}
        handler.removeCallbacksAndMessages(null)
        animator?.cancel()
        status.text = "Ruk gaya"
    }

    private fun nextScene() {
        if (scenes.isEmpty()) return
        idx = (idx + 1) % scenes.size
        showScene(idx)
    }

    private fun saveFrames() {
        if (scenes.isEmpty()) return
        status.text = "Frames save kar raha hoon..."
        Thread {
            var saved = 0
            for (s in scenes) {
                try {
                    val w = 1080
                    val h = 1920
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val c = Canvas(bmp)
                    s.bg?.let { c.drawBitmap(it, null, android.graphics.Rect(0, 0, w, h), null) }
                        ?: c.drawColor(0xFF0B1030.toInt())
                    s.ch?.let { ch ->
                        val th = h * 0.55f
                        val tw = th * (ch.width.toFloat() / ch.height.toFloat())
                        val left = (w - tw) / 2f
                        c.drawBitmap(ch, null, android.graphics.RectF(left, h - th * 0.92f, left + tw, h - th * 0.92f + th), null)
                    }
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "story_${System.currentTimeMillis()}_$saved.png")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        if (Build.VERSION.SDK_INT >= 29)
                            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/BubluStudio")
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    uri?.let {
                        contentResolver.openOutputStream(it)?.use { os ->
                            bmp.compress(Bitmap.CompressFormat.PNG, 92, os)
                        }
                        saved++
                    }
                } catch (e: Exception) {}
            }
            runOnUiThread { status.text = "$saved frames Pictures/BubluStudio me save ho gaye ✅" }
        }.start()
    }

    private fun saveStoryText() {
        if (scenes.isEmpty()) return
        val text = scenes.mapIndexed { i, s -> "Scene ${i + 1}: ${s.line}" }.joinToString("\n")
        Store.addNoteV2(this, "Story: $topic", text)
        Toast.makeText(this, "Story Notes me save ho gayi 📝", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        playing = false
        handler.removeCallbacksAndMessages(null)
        try { animator?.cancel() } catch (e: Exception) {}
        try { tts?.shutdown() } catch (e: Exception) {}
    }
}
