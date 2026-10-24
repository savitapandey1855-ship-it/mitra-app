package com.mitra.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val REQ_MIC = 101
    private val REQ_OVERLAY = 102
    private val REQ_NOTIF = 103
    private lateinit var statusTxt: TextView
    private lateinit var toggleBtn: Button
    private val prefs by lazy { getSharedPreferences("mitra", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusTxt = findViewById(R.id.statusTxt)
        toggleBtn = findViewById(R.id.toggleBtn)
        findViewById<Button>(R.id.permBtn).setOnClickListener { showPermDialog() }
        findViewById<Button>(R.id.keyBtn).setOnClickListener { showKeyDialog() }
        findViewById<Button>(R.id.testBtn).setOnClickListener { testAi() }
        toggleBtn.setOnClickListener {
            if (isRunning()) stopMitra() else tryStart()
        }
    }

    override fun onResume() {
        super.onResume()
        updateUi()
    }

    private fun isRunning() = prefs.getBoolean("running", false)

    private fun updateUi() {
        if (isRunning()) {
            statusTxt.text = "Chal raha hai \u2705\nBubble pe tap karke bolo"
            toggleBtn.text = "OFF karo"
        } else {
            statusTxt.text = "Band hai"
            toggleBtn.text = "ON karo"
        }
    }

    private fun tryStart() {
        if (!Settings.canDrawOverlays(this)) { showPermDialog(); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) { showPermDialog(); return }
        startMitra()
    }

    private fun startMitra() {
        prefs.edit().putBoolean("running", true).apply()
        val i = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        updateUi()
        finish()
    }

    private fun stopMitra() {
        prefs.edit().putBoolean("running", false).apply()
        val i = Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP)
        startService(i)
        updateUi()
    }

    private fun showPermDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_perm, null)
        val dlg = AlertDialog.Builder(this).setView(view).setCancelable(true).create()
        view.findViewById<Button>(R.id.allowAll).setOnClickListener {
            dlg.dismiss()
            grantNext()
        }
        view.findViewById<TextView>(R.id.later).setOnClickListener { dlg.dismiss() }
        dlg.show()
    }

    private fun grantNext() {
        if (!Settings.canDrawOverlays(this)) {
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                REQ_OVERLAY
            )
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS")
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf("android.permission.POST_NOTIFICATIONS"), REQ_NOTIF
            )
            return
        }
        Toast.makeText(this, "Sab permissions mil gayi! \uD83C\uDF89", Toast.LENGTH_SHORT).show()
        startMitra()
    }

    private fun showKeyDialog() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.hint = "AQ... ya AIza... (Gemini key)"
        input.setText(prefs.getString("gemini_key", ""))
        AlertDialog.Builder(this)
            .setTitle("Gemini API key \uD83D\uDD11")
            .setMessage("Ye key sirf aapke phone me rehti hai, kisi ko nahi jaati.\n" +
                "Free key: aistudio.google.com/app/apikey\n" +
                "Save ke baad 'Test AI' dabao.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putString("gemini_key", input.text.toString().trim()).apply()
                Toast.makeText(this, "Save ho gayi \u2705 - ab Test AI dabao", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun testAi() {
        val key = prefs.getString("gemini_key", "")?.trim() ?: ""
        if (key.isBlank()) {
            Toast.makeText(this, "Pehle Gemini key daalo \uD83D\uDD11", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Check kar raha hoon...", Toast.LENGTH_SHORT).show()
        Thread {
            val (ok, text) = Gemini.ask(key, "Reply with exactly one word: OK")
            runOnUiThread {
                val msg = if (ok) "AI chal raha hai \u2705\n$text"
                else "AI error \u274C\n$text"
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC || requestCode == REQ_NOTIF) grantNext()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OVERLAY) grantNext()
    }
}
