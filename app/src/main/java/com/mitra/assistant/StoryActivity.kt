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
    private var vertical = false
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

        vertical = intent.getBooleanExtra("vertical", false)
        sceneView.vertical = vertical
        topic = intent.getStringExtra("topic") ?: "a friendly robot"
        minutes = intent.getIntExtra("minutes", 2)

        tts = TextToSpeech(this) { s -> ttsReady = s == TextToSpeech.SUCCESS }

        findViewById<Button>(R.id.btnNew).setOnClickListener { askStory() }
        findViewById<Button>(R.id.btnPlay).setOnClickListener { play() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stop() }
        findViewById<Button>(R.id.btnVoice).setOnClickListener { pickVoice() }
        findViewById<Button>(R.id.btnNext).setOnClickListener { nextScene() }
        findViewById<Button>(R.id.btnSaveFrames).setOnClickListener { saveFrames() }
        findViewById<Button>(R.id.btn3d).setOnClickListener { start3d() }
        findViewById<Button>(R.id.btnDiag).setOnClickListener { diagnose() }
        findViewById<Button>(R.id.btnExportTop).setOnClickListener { makeMp4() }
        findViewById<Button>(R.id.btnRatio).setOnClickListener { toggleRatio() }
        findViewById<Button>(R.id.btnUpload).setOnClickListener { uploadDialog() }
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
    /** Android 9 ya purane pe gallery me likhne ke liye permission. */
    private fun askStoragePerm() {
        try {
            if (android.os.Build.VERSION.SDK_INT <= 28) {
                if (androidx.core.content.ContextCompat.checkSelfPermission(
                        this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    androidx.core.app.ActivityCompat.requestPermissions(
                        this, arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 91
                    )
                }
            }
        } catch (e: Exception) {}
    }

    /** 3D-look short (9:16) - 2 AI images + Hindi voice. */
    private fun start3d() {
        val box = EditText(this)
        box.setTextColor(0xFF101733.toInt())
        box.setText(topic)
        AlertDialog.Builder(this).setTitle("3D Short ka topic").setView(box)
            .setPositiveButton("Banao") { _, _ ->
                topic = box.text.toString().trim().ifBlank { "a friendly robot" }
                sceneView.vertical = true
                status.text = "3D short ban rahi hai (2 image + voice)..."
                try {
                    val si = android.content.Intent(this, StoryService::class.java)
                    si.putExtra("topic", topic)
                    si.putExtra("minutes", minutes)
                    si.putExtra("mode", "3d")
                    getSharedPreferences("mitra", MODE_PRIVATE).edit()
                        .putString("story_progress", "Shuru...")
                        .putString("story_json", "[]")
                        .apply()
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(si) else startService(si)
                    pollProgress()
                } catch (e: Exception) {
                    status.text = "Service start nahi hui: " + e.message
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

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
            val note = getSharedPreferences("mitra", MODE_PRIVATE).getString("story_img_note", "") ?: ""
            if (note.isNotBlank()) Toast.makeText(this, note, Toast.LENGTH_LONG).show()
            status.text = if (note.isBlank()) "${scenes.size} scenes ready - Play dabao ▶️"
            else "${scenes.size} scenes ready (${note})"
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
        sceneView.paletteIndex = i
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
        if (playing) status.text = "Scene ${i + 1}/${scenes.size}"
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

    /** Poora diagnosis - images kyun nahi dikh rahi, sab yahin dikhega. */
    private fun diagnose() {
        val prefs = getSharedPreferences("mitra", MODE_PRIVATE)
        val tok = prefs.getString("cf_token", "") ?: ""
        val acc = prefs.getString("cf_account", "") ?: ""
        val note = prefs.getString("story_img_note", "") ?: ""
        val made = prefs.getInt("story_img_count", -1)
        val raw = prefs.getString("story_json", "") ?: ""

        val sb = StringBuilder()
        sb.append("CF token: ")
        sb.append(if (tok.isBlank()) "NOT SET" else "set (len " + tok.length + ")")
        sb.append("\nCF account: ").append(acc.ifBlank { "(default)" })
        sb.append("\nImages made: ").append(if (made < 0) "?" else made.toString())
        sb.append("\nNote: ").append(note.ifBlank { "-" })
        sb.append("\nLast image error: ").append(ImageGen.lastError.ifBlank { "-" })
        sb.append("\n\nScenes in JSON: ").append(scenes.size)

        val dir = java.io.File(filesDir, "story")
        val files = dir.listFiles()?.size ?: 0
        sb.append("\nImage files: ").append(files)

        try {
            val arr = JSONArray(raw)
            if (arr.length() > 0) {
                val o = arr.optJSONObject(0)
                val bP = o?.optString("bg", "") ?: ""
                val cP = o?.optString("ch", "") ?: ""
                sb.append("\n\nScene1 bg: ").append(bP.ifBlank { "(empty)" })
                if (bP.isNotBlank()) {
                    val f = java.io.File(bP)
                    sb.append("\n  exists=").append(f.exists())
                    sb.append(" size=").append(f.length())
                    val bmp = android.graphics.BitmapFactory.decodeFile(bP)
                    sb.append(" decode=").append(bmp != null)
                }
                sb.append("\nScene1 ch: ").append(cP.ifBlank { "(empty)" })
                if (cP.isNotBlank()) {
                    val f2 = java.io.File(cP)
                    sb.append("\n  exists=").append(f2.exists())
                    sb.append(" size=").append(f2.length())
                }
            }
        } catch (e: Exception) {
            sb.append("\nJSON error: ").append(e.message)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Story Studio diagnosis")
            .setMessage(sb.toString())
            .setPositiveButton("OK", null)
            .setNeutralButton("Test Cloudflare") { _, _ ->
                Toast.makeText(this, "Testing...", Toast.LENGTH_SHORT).show()
                Thread {
                    val r = ImageGen.testCloudflare(this)
                    runOnUiThread {
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle(if (r.first) "Cloudflare OK" else "Cloudflare fail")
                            .setMessage(r.second)
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }.start()
            }
            .show()
    }

    /** 16:9 (long video) <-> 9:16 (shorts/reels) */
    private fun toggleRatio() {
        vertical = !vertical
        sceneView.vertical = vertical
        sceneView.requestLayout()
        sceneView.invalidate()
        Toast.makeText(
            this,
            if (vertical) "Format: 9:16 (Shorts / Reels)" else "Format: 16:9 (Long video)",
            Toast.LENGTH_SHORT
        ).show()
    }

    /** Title/description/hashtags banao aur upload ke liye app kholo. */
    private fun uploadDialog() {
        if (scenes.isEmpty()) {
            Toast.makeText(this, "Pehle story banao", Toast.LENGTH_SHORT).show()
            return
        }
        status.text = "Title aur description bana raha hoon..."
        val storyText = scenes.joinToString(" ") { it.line }
        Thread {
            val meta = UploadHelper.makeMeta(topic, storyText, vertical)
            runOnUiThread {
                status.text = "${scenes.size} scenes ready"
                val msg = "TITLE\n" + meta.title + "\n\nDESCRIPTION\n" + meta.description +
                        "\n\nHASHTAGS\n" + meta.tags

                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Upload ready (" + (if (vertical) "9:16" else "16:9") + ")")
                    .setMessage(msg)
                    .setPositiveButton("YouTube") { _, _ -> goUpload(meta, "youtube") }
                    .setNegativeButton("Instagram") { _, _ -> goUpload(meta, "instagram") }
                    .setNeutralButton("Copy text") { _, _ ->
                        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(
                            android.content.ClipData.newPlainText(
                                "bublu", meta.title + "\n\n" + meta.description + "\n\n" + meta.tags
                            )
                        )
                        Toast.makeText(this, "Copy ho gaya - app me paste karo", Toast.LENGTH_LONG).show()
                    }
                    .show()
            }
        }.start()
    }

    private fun goUpload(meta: UploadHelper.Meta, platform: String) {
        try {
            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            cm.setPrimaryClip(
                android.content.ClipData.newPlainText(
                    "bublu", meta.title + "\n\n" + meta.description + "\n\n" + meta.tags
                )
            )
        } catch (e: Exception) {}

        val ok = UploadHelper.openApp(this, platform)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(platform.replaceFirstChar { it.uppercase() } + " pe upload")
            .setMessage(
                (if (ok) "" else "App nahi khula, browser me khol raha hoon.\n\n") +
                    UploadHelper.steps(platform, vertical) +
                    "\n\nTitle/description clipboard me copy hai - paste kar dena."
            )
            .setPositiveButton("OK", null)
            .show()
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

    /** Ek scene ka frame (16:9 ya 9:16) - image ya gradient + stylish caption. */
    private fun buildFrame(line: String, index: Int): Bitmap {
        val w = if (vertical) 720 else 1280
        val h = if (vertical) 1280 else 720
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        val src = scenes.getOrNull(index)?.bg
        if (src != null) {
            c.drawBitmap(src, null, android.graphics.Rect(0, 0, w, h), null)
        } else {
            val pals = arrayOf(
                intArrayOf(0xFF141E30.toInt(), 0xFF243B55.toInt()),
                intArrayOf(0xFF2B1055.toInt(), 0xFF7597DE.toInt()),
                intArrayOf(0xFF0F2027.toInt(), 0xFF203A43.toInt()),
                intArrayOf(0xFF3A1C71.toInt(), 0xFFD76D77.toInt()),
                intArrayOf(0xFF134E5E.toInt(), 0xFF71B280.toInt())
            )
            val pal = pals[index % pals.size]
            val gp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            gp.shader = android.graphics.LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(), pal[0], pal[1],
                android.graphics.Shader.TileMode.CLAMP
            )
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), gp)
        }

        if (line.isNotBlank()) {
            val tp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            tp.color = android.graphics.Color.WHITE
            tp.textSize = h * 0.052f
            tp.textAlign = android.graphics.Paint.Align.CENTER
            tp.typeface = android.graphics.Typeface.DEFAULT_BOLD
            tp.setShadowLayer(12f, 0f, 5f, 0xFF000000.toInt())

            val maxW = w * 0.84f
            val rows = ArrayList<String>()
            var cur = ""
            for (wd in line.split(" ")) {
                val test = if (cur.isEmpty()) wd else "$cur $wd"
                if (tp.measureText(test) > maxW) {
                    if (cur.isNotEmpty()) rows.add(cur)
                    cur = wd
                } else cur = test
            }
            if (cur.isNotEmpty()) rows.add(cur)

            val lineH = tp.textSize * 1.35f
            val firstY = h * 0.86f - (rows.size - 1) * lineH
            val top = firstY - tp.textSize
            val bottom = firstY + (rows.size - 1) * lineH + tp.textSize * 0.5f

            val barP = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            barP.color = 0x66000000
            c.drawRoundRect(android.graphics.RectF(w * 0.05f, top, w * 0.95f, bottom), 26f, 26f, barP)

            val accP = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            accP.color = 0xFF22D3EE.toInt()
            c.drawRoundRect(android.graphics.RectF(w * 0.05f, top, w * 0.062f, bottom), 10f, 10f, accP)

            for (i in 0 until rows.size) {
                c.drawText(rows[i], w / 2f, firstY + i * lineH, tp)
            }
        }
        return bmp
    }

    private fun saveFrames() {
        if (scenes.isEmpty()) return
        status.text = "Frames save kar raha hoon..."
        Thread {
            var saved = 0
            for ((i, sc) in scenes.withIndex()) {
                try {
                    val bmp = buildFrame(sc.line, i)
                    if (MediaSaver.saveImage(
                            this@StoryActivity, bmp,
                            "story_" + System.currentTimeMillis() + "_" + i + ".png"
                        )
                    ) saved++
                } catch (e: Exception) {}
            }
            runOnUiThread {
                status.text = if (saved > 0) "$saved frames gallery me save ho gaye"
                else "Frames save nahi hue"
            }
        }.start()
    }

    /** Scenes -> MP4 video file (Movies/BubluStudio). */
    /** Scenes -> MP4 (video + narration audio) -> gallery. */
    private fun makeMp4() {
        if (scenes.isEmpty()) {
            Toast.makeText(this, "Pehle story banao", Toast.LENGTH_SHORT).show()
            return
        }
        status.text = "Narration aur video ban rahi hai..."
        Thread {
            try {
                val prefs = getSharedPreferences("mitra", MODE_PRIVATE)
                val spk = prefs.getString("tts_speaker", "priya") ?: "priya"
                val key = KeyPool.current("sarvam")
                val dir = java.io.File(filesDir, "story").apply { mkdirs() }

                var rate = 22050
                val chunks = ArrayList<ShortArray>()
                val durations = ArrayList<Float>()
                val gapSec = 0.35f

                for ((i, sc) in scenes.withIndex()) {
                    val msg = "Audio ${i + 1}/${scenes.size}"
                    runOnUiThread { status.text = msg }

                    val wavFile = java.io.File(dir, "line_$i.wav")
                    var pcm: ShortArray? = null

                    if (wavFile.exists() && wavFile.length() > 44) {
                        val r = Mp4Maker.wavToPcm(wavFile.readBytes())
                        if (r != null) { pcm = r.first; rate = r.second }
                    }
                    if (pcm == null && key != null) {
                        val t = Sarvam.ttsHi(key, sc.line, spk)
                        val bytes = t.second
                        if (t.first && bytes != null) {
                            try { wavFile.writeBytes(bytes) } catch (e: Exception) {}
                            val r = Mp4Maker.wavToPcm(bytes)
                            if (r != null) { pcm = r.first; rate = r.second }
                        }
                    }

                    if (pcm != null && pcm.isNotEmpty()) {
                        chunks.add(pcm)
                        val d = pcm.size.toFloat() / rate.toFloat()
                        durations.add((d + gapSec).coerceIn(2f, 15f))
                    } else {
                        chunks.add(ShortArray(rate))
                        durations.add(4f)
                    }
                }

                val gapSamples = (rate * gapSec).toInt()
                var totalSamples = 0
                for (c in chunks) totalSamples += c.size + gapSamples
                val all = ShortArray(totalSamples)
                var off = 0
                for (c in chunks) {
                    System.arraycopy(c, 0, all, off, c.size)
                    off += c.size + gapSamples
                }

                val out = java.io.File(cacheDir, "story_" + System.currentTimeMillis() + ".mp4")
                val res = Mp4Maker.encode(
                    frameAt = { i -> buildFrame(scenes[i].line, i) },
                    count = scenes.size,
                    durations = durations,
                    pcm = all,
                    sampleRate = rate,
                    outFile = out
                ) { done, total ->
                    runOnUiThread { status.text = "Video: $done/$total" }
                }

                if (!res.first || !out.exists()) {
                    runOnUiThread { status.text = "Video nahi bani: " + res.second }
                    return@Thread
                }

                val saved = MediaSaver.saveVideo(
                    this@StoryActivity,
                    out,
                    "BubluStory_" + System.currentTimeMillis() + ".mp4"
                )
                runOnUiThread {
                    status.text = if (saved.first) "Video ready - " + saved.second
                    else "Video save nahi hui - " + saved.second
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Video fail: " + (e.message ?: "error") }
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
