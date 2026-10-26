package com.mitra.assistant

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Bublu Studio - apne app ka editing page (text, filters, effects, bg, AI art, code objects). */
class EditorActivity : AppCompatActivity() {

    private lateinit var canvas: EditCanvas
    private lateinit var preview: VideoView
    private var music: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO)
        setContentView(R.layout.activity_editor)

        canvas = findViewById(R.id.canvas)
        preview = findViewById(R.id.preview)

        findViewById<Button>(R.id.btnVideo).setOnClickListener { pickVideo() }
        findViewById<Button>(R.id.btnText).setOnClickListener { addTextDialog() }
        findViewById<Button>(R.id.btnShape).setOnClickListener { addShapeDialog() }
        findViewById<Button>(R.id.btnFilter).setOnClickListener { pickFilter() }
        findViewById<Button>(R.id.btnEffect).setOnClickListener { pickEffect() }
        findViewById<Button>(R.id.btnBg).setOnClickListener { pickBg() }
        findViewById<Button>(R.id.btnAI).setOnClickListener { aiDialog() }
        findViewById<Button>(R.id.btnCode).setOnClickListener { codeDialog() }
        findViewById<Button>(R.id.btnSound).setOnClickListener { pickSound() }
        findViewById<Button>(R.id.btnUndo).setOnClickListener { canvas.undo() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveProject() }
        findViewById<Button>(R.id.btnExport).setOnClickListener { exportPng() }
    }

    // ---------- video ----------
    private fun pickVideo() {
        try {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "video/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(i, 11)
        } catch (e: Exception) {
            Toast.makeText(this, "Gallery nahi khuli", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri: Uri = data?.data ?: return
        when (requestCode) {
            11 -> try {
                preview.setVideoURI(uri)
                preview.setOnPreparedListener { it.isLooping = true; preview.start() }
            } catch (e: Exception) {
                Toast.makeText(this, "Video nahi chala", Toast.LENGTH_SHORT).show()
            }
            12 -> try {
                music?.release()
                music = MediaPlayer().apply {
                    setDataSource(this@EditorActivity, uri)
                    isLooping = true
                    prepare()
                    start()
                }
                Toast.makeText(this, "Sound add ho gaya 🎵", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Sound nahi chala", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun pickSound() {
        try {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "audio/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }, 12
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Audio picker nahi khula", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- text / shapes ----------
    private fun addTextDialog() {
        val input = EditText(this)
        input.setTextColor(0xFF101733.toInt())
        input.hint = "Title / text"
        AlertDialog.Builder(this).setTitle("Add text").setView(input)
            .setPositiveButton("Add") { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotBlank()) canvas.addText(t)
            }.setNegativeButton("Cancel", null).show()
    }

    private fun addShapeDialog() {
        val shapes = arrayOf("Circle", "Rectangle", "Star", "Heart")
        AlertDialog.Builder(this).setTitle("Object")
            .setItems(shapes) { _, w ->
                val kind = when (w) {
                    0 -> EditCanvas.CIRCLE
                    1 -> EditCanvas.RECT
                    2 -> EditCanvas.STAR
                    else -> EditCanvas.HEART
                }
                val colors = intArrayOf(
                    Color.WHITE, 0xFFFF4D9D.toInt(), 0xFF22D3EE.toInt(),
                    0xFF6C4BFF.toInt(), 0xFFFFD54F.toInt(), 0xFF3ED98A.toInt()
                )
                canvas.addShape(kind, colors.random())
            }.show()
    }

    private fun pickFilter() {
        val f = arrayOf("None", "Warm", "Cool", "Mono", "Vintage")
        AlertDialog.Builder(this).setTitle("Filter")
            .setItems(f) { _, w -> canvas.filter = w; canvas.invalidate() }.show()
    }

    private fun pickEffect() {
        val e = arrayOf("None", "Vignette", "Glow frame", "Scanlines")
        AlertDialog.Builder(this).setTitle("Effect")
            .setItems(e) { _, w -> canvas.effect = w; canvas.invalidate() }.show()
    }

    private fun pickBg() {
        val bgs = arrayOf("Dark", "Purple", "Blue", "Night", "White", "AI art")
        AlertDialog.Builder(this).setTitle("Background")
            .setItems(bgs) { _, w ->
                when (w) {
                    0 -> { canvas.bgColor = 0xFF101733.toInt(); canvas.bgBmp = null }
                    1 -> { canvas.bgColor = 0xFF3B1E6E.toInt(); canvas.bgBmp = null }
                    2 -> { canvas.bgColor = 0xFF10305E.toInt(); canvas.bgBmp = null }
                    3 -> { canvas.bgColor = 0xFF000000.toInt(); canvas.bgBmp = null }
                    4 -> { canvas.bgColor = 0xFFFFFFFF.toInt(); canvas.bgBmp = null }
                    else -> aiDialog()
                }
                canvas.invalidate()
            }.show()
    }

    // ---------- Pollinations AI art ----------
    private fun aiDialog() {
        val input = EditText(this)
        input.setTextColor(0xFF101733.toInt())
        input.hint = "AI image prompt (e.g. sunset over mountains)"
        AlertDialog.Builder(this).setTitle("AI art (Pollinations)").setView(input)
            .setPositiveButton("Generate") { _, _ ->
                val p = input.text.toString().trim()
                if (p.isNotBlank()) fetchAI(p)
            }.setNegativeButton("Cancel", null).show()
    }

    private fun fetchAI(prompt: String) {
        Toast.makeText(this, "AI image ban rahi hai...", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val q = URLEncoder.encode(prompt, "UTF-8")
                val url = URL("https://image.pollinations.ai/prompt/$q?width=512&height=512&nologo=true")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 60000
                val bmp = BitmapFactory.decodeStream(conn.inputStream)
                runOnUiThread {
                    if (bmp != null) {
                        canvas.addImage(bmp)
                        Toast.makeText(this, "AI object add ho gaya ✨", Toast.LENGTH_SHORT).show()
                    } else Toast.makeText(this, "Image nahi mili", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "AI art fail: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    // ---------- code objects ----------
    private fun codeDialog() {
        val input = EditText(this)
        input.setTextColor(0xFF101733.toInt())
        input.setHintTextColor(0xFF6B7391.toInt())
        input.minLines = 6
        input.setText(
            "text Hello Bublu\ncircle 120 #FF4D9D\nstar 90 #FFD54F\nimage cute robot in space"
        )
        AlertDialog.Builder(this)
            .setTitle("Code objects")
            .setMessage("Commands: text <words> | circle/rect/star/heart <size> <#color> | image <prompt>\nmove <i> <x> <y> | moveby <i> <dx> <dy> | anim <i> <fromX> <toX> <ms> | animy <i> <fromY> <toY> <ms>")
            .setView(input)
            .setPositiveButton("Run") { _, _ -> runCode(input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runCode(src: String) {
        var added = 0
        for (raw in src.split("\n")) {
            val line = raw.trim()
            if (line.isBlank()) continue
            val parts = line.split(" ").filter { it.isNotBlank() }
            if (parts.isEmpty()) continue
            val cmd = parts[0].lowercase()
            when (cmd) {
                "text" -> {
                    val t = line.removePrefix(parts[0]).trim()
                    if (t.isNotBlank()) { canvas.addText(t); added++ }
                }
                "circle", "rect", "star", "heart" -> {
                    val size = parts.getOrNull(1)?.toFloatOrNull() ?: 80f
                    val col = try { Color.parseColor(parts.getOrNull(2) ?: "#FFFFFF") } catch (e: Exception) { Color.WHITE }
                    val kind = when (cmd) {
                        "circle" -> EditCanvas.CIRCLE
                        "rect" -> EditCanvas.RECT
                        "star" -> EditCanvas.STAR
                        else -> EditCanvas.HEART
                    }
                    canvas.addShape(kind, col, size)
                    added++
                }
                "image" -> {
                    val p = line.removePrefix(parts[0]).trim()
                    if (p.isNotBlank()) { fetchAI(p); added++ }
                }
                "move" -> {
                    val i = parts.getOrNull(1)?.toIntOrNull() ?: continue
                    val x = parts.getOrNull(2)?.toFloatOrNull() ?: 0f
                    val y = parts.getOrNull(3)?.toFloatOrNull() ?: 0f
                    if (canvas.moveItem(i, x, y)) added++
                }
                "moveby" -> {
                    val i = parts.getOrNull(1)?.toIntOrNull() ?: continue
                    val dx = parts.getOrNull(2)?.toFloatOrNull() ?: 0f
                    val dy = parts.getOrNull(3)?.toFloatOrNull() ?: 0f
                    val it0 = canvas.items.getOrNull(i) ?: continue
                    if (canvas.moveItem(i, it0.x + dx, it0.y + dy)) added++
                }
                "anim" -> {
                    val i = parts.getOrNull(1)?.toIntOrNull() ?: continue
                    val from = parts.getOrNull(2)?.toFloatOrNull() ?: 0f
                    val to = parts.getOrNull(3)?.toFloatOrNull() ?: 800f
                    val ms = parts.getOrNull(4)?.toLongOrNull() ?: 1500L
                    canvas.animateX(i, from, to, ms)
                    added++
                }
                "animy" -> {
                    val i = parts.getOrNull(1)?.toIntOrNull() ?: continue
                    val from = parts.getOrNull(2)?.toFloatOrNull() ?: 0f
                    val to = parts.getOrNull(3)?.toFloatOrNull() ?: 800f
                    val ms = parts.getOrNull(4)?.toLongOrNull() ?: 1500L
                    canvas.animateY(i, from, to, ms)
                    added++
                }
            }
        }
        Toast.makeText(this, "$added objects add hue", Toast.LENGTH_SHORT).show()
    }

    // ---------- save / export ----------
    private fun saveProject() {
        try {
            val o = JSONObject().apply {
                put("filter", canvas.filter)
                put("effect", canvas.effect)
                put("bg", canvas.bgColor)
                put("count", canvas.items.size)
            }
            getSharedPreferences("mitra", MODE_PRIVATE).edit()
                .putString("studio_project", o.toString()).apply()
            Toast.makeText(this, "Project save ho gaya ✅", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Save fail", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportPng() {
        try {
            val w = if (canvas.width > 0) canvas.width else 1080
            val h = if (canvas.height > 0) canvas.height else 1920
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            canvas.drawBg = true
            canvas.draw(c)
            canvas.drawBg = false

            val name = "bublu_studio_${System.currentTimeMillis()}.png"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= 29)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/BubluStudio")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                contentResolver.openOutputStream(it)?.use { os -> bmp.compress(Bitmap.CompressFormat.PNG, 100, os) }
                Toast.makeText(this, "Export ho gaya! Pictures/BubluStudio me hai 🎨", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Export fail: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { music?.release() } catch (e: Exception) {}
        music = null
        try { preview.stopPlayback() } catch (e: Exception) {}
    }
}
