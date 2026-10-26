package com.mitra.assistant

import android.animation.ValueAnimator
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
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
import java.io.File
import java.util.Locale

/** Bublu Story Studio - Hindi story, voice narration, 16:9 MP4. */
class StoryActivity : AppCompatActivity() {

    private data class Scene(val line: String)

    private lateinit var sceneView: SceneView
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var mp: android.media.MediaPlayer? = null
    private var animator: ValueAnimator? = null

    private val scenes = ArrayList<Scene>()
    private var playing = false
    private var idx = 0
    private var topic = "a friendly robot"
    private var minutes = 2

    private val palettes = arrayOf(
        intArrayOf(0xFF141E30.toInt(), 0xFF243B55.toInt()),
        intArrayOf(0xFF2B1055.toInt(), 0xFF7597DE.toInt()),
        intArrayOf(0xFF0F2027.toInt(), 0xFF203A43.toInt()),
        intArrayOf(0xFF3A1C71.toInt(), 0xFFD76D77.toInt()),
        intArrayOf(0xFF134E5E.toInt(), 0xFF71B280.toInt())
    )

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
        findViewById<Button>(R.id.btnVoice).setOnClickListener { pickVoice() }
        findViewById<Button>(R.id.btnSaveFrames).setOnClickListener { saveFrames() }
        findViewById<Button>(R.id.btnMp4).setOnClickListener { makeMp4() }
        findViewById<Button>(R.id.btnSaveStory).setOnClickListener { saveStoryText() }

        loadFromJson()
        if (intent.getBooleanExtra("auto", false)) askStory()
    }

    // ---------- new story ----------
    private fun askStory() {
        val box = EditText(this)
        box.setTextColor(0xFF101733.toInt())
        box.setText(topic)
        val mins = EditText(this)
        mins.setTextColor(0xFF101733.toInt())
        mins.setText(minutes.toString())

        val wrap = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(TextView(this@StoryActivity).apply {
                text = "Topic"
                setTextColor(0xFF101733.toInt())
            })
            addView(box)
            addView(TextView(this@StoryActivity).apply {
                text = "Minutes (1-5)"
                setTextColor(0xFF101733.toInt())
            })
            addView(mins)
        }

        AlertDialog.Builder(this).setTitle("New story (Hindi voice)").setView(wrap)
            .setPositiveButton("Generate") { _, _ ->
                topic = box.text.toString().trim().ifBlank { "a friendly robot" }
                minutes = (mins.text.toString().toIntOrNull() ?: 2).coerceIn(1, 5)
                startService()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startService() {
        status.text = "Background me story ban rahi hai... (app band kar sakte ho)"
        try {
            val si = android.content.Intent(this, StoryService::class.java)
            si.putExtra("topic", topic)
            si.putExtra("minutes", minutes)
            getSharedPreferences("mitra", MODE_PRIVATE).edit()
                .putString("story_progress", "Shuru...")
                .putString("story_json", "[]")
                .apply()
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(si) else startService(si)
            pollProgress()
        } catch (e: Exception) {
            status.text = "Service start nahi hui: ${e.message}"
        }
    }

    private fun pollProgress() {
        handler.postDelayed({
            val p = getSharedPreferences("mitra", MODE_PRIVATE)
                .getString("story_progress", "") ?: ""
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
            val raw = getSharedPreferences("mitra", MODE_PRIVATE)
                .getString("story_json", "") ?: return
            val arr = JSONArray(raw)
            val out = ArrayList<Scene>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val line = o.optString("line", "").trim()
                if (line.isNotBlank()) out.add(Scene(line))
            }
            if (out.isEmpty()) return
            scenes.clear()
            scenes.addAll(out)
            idx = 0
            status.text = "${scenes.size} scenes ready - Play dabao ▶"
            showScene(0)
        } catch (e: Exception) {
            status.text = "Story load nahi hui"
        }
    }

    // ---------- playback ----------
    private fun showScene(i: Int) {
        if (i !in scenes.indices) return
        sceneView.line = scenes[i].line
        sceneView.paletteIndex = i
        sceneView.captionAlpha = 0
        sceneView.invalidate()

        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900
            addUpdateListener {
                val t = it.animatedValue as Float
                sceneView.captionAlpha = (255 * t).toInt()
                sceneView.invalidate()
            }
            start()
        }
        status.text = "Scene ${i + 1}/${scenes.size}"
    }

    private fun play() {
        if (scenes.isEmpty()) {
            Toast.makeText(this, "Pehle story banao", Toast.LENGTH_SHORT).show()
            return
        }
        playing = true
        idx = 0
        step()
    }

    private fun step() {
        if (!playing || idx >= scenes.size) {
            playing = false
            return
        }
        showScene(idx)
        val line = scenes[idx].line
        var advanced = false

        fun go() {
            if (advanced || !playing) return
            advanced = true
            handler.postDelayed({ if (playing) { idx++; step() } }, 700)
        }

        if (line.isNotBlank()) speakLine(line) { go() } else go()
        handler.postDelayed({ go() }, 18000)
    }

    private fun speakLine(text: String, onDone: () -> Unit) {
        val spk = getSharedPreferences("mitra", MODE_PRIVATE)
            .getString("tts_speaker", "priya") ?: "priya"
        val key = KeyPool.current("sarvam")
        if (key == null) {
            speakLocalHi(text, onDone)
            return
        }
        Thread {
            val (ok, bytes) = Sarvam.ttsHi(key, text, spk)
            if (ok && bytes != null) {
                try {
                    val f = File(cacheDir, "line.wav")
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
                        } catch (e: Exception) {
                            speakLocalHi(text, onDone)
                        }
                    }
                    return@Thread
                } catch (e: Exception) {}
            }
            runOnUiThread { speakLocalHi(text, onDone) }
        }.start()
    }

    private fun speakLocalHi(text: String, onDone: () -> Unit) {
        try {
            if (!ttsReady || tts == null) {
                onDone()
                return
            }
            tts?.language = Locale("hi", "IN")
            tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { runOnUiThread { onDone() } }
                override fun onError(id: String?) { runOnUiThread { onDone() } }
            })
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "line")
        } catch (e: Exception) {
            onDone()
        }
    }

    private fun stop() {
        playing = false
        handler.removeCallbacksAndMessages(null)
        try { tts?.stop() } catch (e: Exception) {}
        try { mp?.stop() } catch (e: Exception) {}
        animator?.cancel()
        status.text = "Ruk gaya"
    }

    private fun nextScene() {
        if (scenes.isEmpty()) return
        idx = (idx + 1) % scenes.size
        showScene(idx)
    }

    private fun pickVoice() {
        val names = arrayOf(
            "Priya (female)", "Ritu (female)", "Kavya (female)",
            "Shubh (male)", "Aditya (male)", "Rahul (male)"
        )
        val ids = arrayOf("priya", "ritu", "kavya", "shubh", "aditya", "rahul")
        val cur = getSharedPreferences("mitra", MODE_PRIVATE)
            .getString("tts_speaker", "priya") ?: "priya"
        val checked = ids.indexOf(cur).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Storyteller voice")
            .setSingleChoiceItems(names, checked) { d, w ->
                getSharedPreferences("mitra", MODE_PRIVATE).edit()
                    .putString("tts_speaker", ids[w]).apply()
                Toast.makeText(this, "${names[w]} chuni gayi", Toast.LENGTH_SHORT).show()
                d.dismiss()
            }.show()
    }

    // ---------- 16:9 frame ----------
    private fun buildFrame(line: String, index: Int): Bitmap {
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
        var curS = ""
        for (wd in line.split(" ")) {
            val test = if (curS.isEmpty()) wd else "$curS $wd"
            if (tp.measureText(test) > maxW) {
                if (curS.isNotEmpty()) rows.add(curS)
                curS = wd
            } else curS = test
        }
        if (curS.isNotEmpty()) rows.add(curS)

        val lineH = tp.textSize * 1.35f
        val firstY = h * 0.52f - (rows.size - 1) * lineH / 2f

        val barP = Paint(Paint.ANTI_ALIAS_FLAG)
        barP.color = 0x66000000
        val top = firstY - tp.textSize
        val bottom = firstY + (rows.size - 1) * lineH + tp.textSize * 0.5f
        c.drawRoundRect(RectF(w * 0.06f, top, w * 0.94f, bottom), 26f, 26f, barP)

        val accP = Paint(Paint.ANTI_ALIAS_FLAG)
        accP.color = 0xFF22D3EE.toInt()
        c.drawRoundRect(RectF(w * 0.06f, top, w * 0.075f, bottom), 10f, 10f, accP)

        for ((ri, rw) in rows.withIndex()) c.drawText(rw, w / 2f, firstY + ri * lineH, tp)
        return bmp
    }

    private fun saveFrames() {
        if (scenes.isEmpty()) {
            Toast.makeText(this, "Pehle story banao", Toast.LENGTH_SHORT).show()
            return
        }
        status.text = "Frames save kar raha hoon..."
        Thread {
            var saved = 0
            for ((i, s) in scenes.withIndex()) {
                try {
                    val bmp = buildFrame(s.line, i)
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "story_${System.currentTimeMillis()}_$i.png")
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
            runOnUiThread { status.text = "$saved frames save ho gaye (Pictures/BubluStudio)" }
        }.start()
    }

    private fun makeMp4() {
        if (scenes.isEmpty()) {
            Toast.makeText(this, "Pehle story banao", Toast.LENGTH_SHORT).show()
            return
        }
        status.text = "MP4 ban raha hai (16:9)..."
        Thread {
            try {
                val frames = ArrayList<Bitmap>()
                for ((i, s) in scenes.withIndex()) frames.add(buildFrame(s.line, i))

                val out = File(cacheDir, "story_${System.currentTimeMillis()}.mp4")
                val ok = Mp4Maker.encode(frames, 3.0f, out) { done, total ->
                    runOnUiThread { status.text = "MP4: $done/$total scenes" }
                }
                if (!ok || !out.exists()) {
                    runOnUiThread { status.text = "MP4 nahi ban paya" }
                    return@Thread
                }

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
                runOnUiThread { status.text = "MP4 ready (16:9) - Movies/BubluStudio" }
            } catch (e: Exception) {
                runOnUiThread { status.text = "MP4 fail: ${e.message}" }
            }
        }.start()
    }

    private fun saveStoryText() {
        if (scenes.isEmpty()) return
        val text = scenes.mapIndexed { i, s -> "Scene ${i + 1}: ${s.line}" }.joinToString("\n")
        Store.addNoteV2(this, "Story: $topic", text)
        Toast.makeText(this, "Story Notes me save ho gayi", Toast.LENGTH_SHORT).show()
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
