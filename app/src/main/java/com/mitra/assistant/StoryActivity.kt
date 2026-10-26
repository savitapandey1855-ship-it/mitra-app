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
    private var mp: android.media.MediaPlayer? = null

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
        findViewById<Button>(R.id.btnVoice).setOnClickListener { pickVoice() }
        findViewById<Button>(R.id.btnNext).setOnClickListener { nextScene() }
        findViewById<Button>(R.id.btnSaveFrames).setOnClickListener { saveFrames() }
        findViewById<Button>(R.id.btnMp4).setOnClickListener { makeMp4() }
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

    /** Background service me story banao - app band karo tab bhi chalegi. */
    private fun buildStory() {
        status.text = "Background me ban rahi hai... (app band kar sakte ho)"
        try {
            val si = android.content.Intent(this, StoryService::class.java)
            si.putExtra("topic", topic)
            si.putExtra("minutes", minutes)
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(si) else startService(si)
            pollProgress()
        } catch (e: Exception) {
            status.text = "Service start nahi hui: ${e.message}"
        }
    }

    private fun pollProgress() {
        handler.postDelayed({
            val p = getSharedPreferences("mitra", MODE_PRIVATE).getString("story_progress", "") ?: ""
            if (p.isNotBlank()) status.text = p
            when {
                p == "DONE" -> loadFromJson()
                p.startsWith("FAIL") -> {}
                else -> pollProgress()
            }
        }, 1200)
    }

    private fun loadFromJson() {
        try {
            val raw = getSharedPreferences("mitra", MODE_PRIVATE).getString("story_json", "") ?: return
            val arr = JSONArray(raw)
            val out = ArrayList<Scene>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val bgP = o.optString("bg", "")
                val chP = o.optString("ch", "")
                val line = o.optString("line", "")
                val bg = if (bgP.isNotBlank()) BitmapFactory.decodeFile(bgP) else null
                val ch = if (chP.isNotBlank()) BitmapFactory.decodeFile(chP) else null
                out.add(Scene("", "", line, bg, ch))
            }
            scenes.clear()
            scenes.addAll(out)
            idx = 0
            status.text = "${scenes.size} scenes ready - Play dabao ▶️"
            showScene(0)
        } catch (e: Exception) {
            status.text = "Story load nahi hui"
        }
    }

    private fun buildStoryOld() {
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
        sceneView.captionAlpha = 0
        sceneView.invalidate()

        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2600
            addUpdateListener {
                val t = it.animatedValue as Float
                sceneView.charX = 0.2f + 0.6f * t
                sceneView.captionAlpha = (255 * (t * 1.6f).coerceAtMost(1f)).toInt()
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
        var advanced = false
        fun go() {
            if (advanced || !playing) return
            advanced = true
            handler.postDelayed({ if (playing) { idx++; step() } }, 800)
        }
        if (line.isNotBlank()) speakLine(line) { go() } else go()
        handler.postDelayed({ go() }, 18000)
    }

    /** Hindi narration - Sarvam (chuni hui awaaz) ya phone TTS. */
    private fun speakLine(text: String, onDone: () -> Unit) {
        val spk = getSharedPreferences("mitra", MODE_PRIVATE).getString("tts_speaker", "priya") ?: "priya"
        val key = KeyPool.current("sarvam")
        if (key == null) { speakLocalHi(text, onDone); return }
        Thread {
            val (ok, bytes) = Sarvam.ttsHi(key, text, spk)
            if (ok && bytes != null) {
                try {
                    val f = java.io.File(cacheDir, "line.wav")
                    f.writeBytes(bytes)
                    runOnUiThread {
                        try {
                            mp?.release()
                            mp = android.media.MediaPlayer().apply {
                                setDataSource(f.absolutePath)
                                setOnCompletionListener { onDone() }
                                setOnErrorListener { _, _, _ -> onDone(); true }
                                prepare()
                                start()
                            }
                        } catch (e: Exception) { speakLocalHi(text, onDone) }
                    }
                    return@Thread
                } catch (e: Exception) {}
            }
            runOnUiThread { speakLocalHi(text, onDone) }
        }.start()
    }

    private fun speakLocalHi(text: String, onDone: () -> Unit) {
        try {
            if (!ttsReady || tts == null) { onDone(); return }
            tts?.language = Locale("hi", "IN")
            tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { runOnUiThread { onDone() } }
                override fun onError(id: String?) { runOnUiThread { onDone() } }
            })
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "line")
        } catch (e: Exception) { onDone() }
    }

    private fun pickVoice() {
        val names = arrayOf("Priya (female)", "Ritu (female)", "Kavya (female)", "Shubh (male)", "Aditya (male)", "Rahul (male)")
        val ids = arrayOf("priya", "ritu", "kavya", "shubh", "aditya", "rahul")
        val cur = getSharedPreferences("mitra", MODE_PRIVATE).getString("tts_speaker", "priya") ?: "priya"
        val checked = ids.indexOf(cur).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Storyteller voice")
            .setSingleChoiceItems(names, checked) { d, w ->
                getSharedPreferences("mitra", MODE_PRIVATE).edit().putString("tts_speaker", ids[w]).apply()
                Toast.makeText(this, "${names[w]} chuni gayi", Toast.LENGTH_SHORT).show()
                d.dismiss()
            }.show()
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

    /** Scenes -> MP4 video file (Movies/BubluStudio). */
    private fun makeMp4() {
        if (scenes.isEmpty()) {
            Toast.makeText(this, "Pehle story banao", Toast.LENGTH_SHORT).show()
            return
        }
        status.text = "MP4 ban raha hai... (thoda time lagega)"
        Thread {
            try {
                val frames = ArrayList<Bitmap>()
                for (s in scenes) {
                    val w = 1280
                    val h = 720
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
                    if (s.line.isNotBlank()) {
                        val tp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                        tp.color = android.graphics.Color.WHITE
                        tp.textSize = h * 0.045f
                        tp.textAlign = android.graphics.Paint.Align.CENTER
                        tp.typeface = android.graphics.Typeface.DEFAULT_BOLD
                        tp.setShadowLayer(10f, 0f, 4f, 0xFF000000.toInt())
                        val maxW = w * 0.82f
                        val rows = ArrayList<String>()
                        var curS = ""
                        for (wd in s.line.split(" ")) {
                            val test = if (curS.isEmpty()) wd else "$curS $wd"
                            if (tp.measureText(test) > maxW) { rows.add(curS); curS = wd } else curS = test
                        }
                        if (curS.isNotEmpty()) rows.add(curS)
                        val lineH = tp.textSize * 1.3f
                        val baseY = h * 0.90f
                        val top = baseY - (rows.size - 1) * lineH - h * 0.03f
                        val barP = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                        barP.color = 0xB3000000.toInt()
                        c.drawRoundRect(android.graphics.RectF(w * 0.06f, top, w * 0.94f, baseY + h * 0.02f), h * 0.03f, h * 0.03f, barP)
                        val startY = baseY - (rows.size - 1) * lineH
                        for ((ri, rw) in rows.withIndex()) c.drawText(rw, w / 2f, startY + ri * lineH, tp)
                    }
                    frames.add(bmp)
                }

                val out = java.io.File(cacheDir, "story_${System.currentTimeMillis()}.mp4")
                val ok = Mp4Maker.encode(frames, 3.0f, out) { done, total ->
                    runOnUiThread { status.text = "MP4: $done/$total scenes" }
                }
                if (!ok || !out.exists()) {
                    runOnUiThread { status.text = "MP4 nahi ban paya" }
                    return@Thread
                }
                // Movies/BubluStudio me save karo
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "BubluStory_${System.currentTimeMillis()}.mp4")
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    if (Build.VERSION.SDK_INT >= 29)
                        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/BubluStudio")
                }
                val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                uri?.let {
                    contentResolver.openOutputStream(it)?.use { os ->
                        out.inputStream().use { ins -> ins.copyTo(os) }
                    }
                }
                runOnUiThread { status.text = "MP4 ready! Movies/BubluStudio me hai 🎬" }
            } catch (e: Exception) {
                runOnUiThread { status.text = "MP4 fail: ${e.message}" }
            }
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
        try { mp?.release() } catch (e: Exception) {}
        mp = null
        try { tts?.shutdown() } catch (e: Exception) {}
    }
}
