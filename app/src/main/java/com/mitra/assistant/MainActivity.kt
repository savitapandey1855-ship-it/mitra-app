package com.mitra.assistant

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val REQ_MIC = 101
    private val REQ_NOTIF = 103
    private val REQ_OVERLAY = 102
    private lateinit var statusTxt: TextView
    private lateinit var toggleBtn: Button
    private lateinit var schedBox: LinearLayout
    private lateinit var eventBox: LinearLayout
    private val prefs by lazy { getSharedPreferences("mitra", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusTxt = findViewById(R.id.statusTxt)
        toggleBtn = findViewById(R.id.toggleBtn)
        schedBox = findViewById(R.id.schedBox)
        eventBox = findViewById(R.id.eventBox)
        findViewById<Button>(R.id.setBtn).setOnClickListener { showSettings() }
        toggleBtn.setOnClickListener { if (isRunning()) stopMitra() else tryStart() }
    }

    override fun onResume() {
        super.onResume()
        updateUi()
        loadLists()
    }

    private fun isRunning() = prefs.getBoolean("running", false)

    private fun updateUi() {
        if (isRunning()) {
            statusTxt.text = "Chal raha hai (bubble pe tap karke bolo)"
            toggleBtn.text = "OFF karo"
        } else {
            statusTxt.text = "Band hai"
            toggleBtn.text = "ON karo"
        }
    }

    private fun loadLists() {
        schedBox.removeAllViews()
        val rems = Store.reminders(this).sortedBy { it.second }
        if (rems.isEmpty()) schedBox.addView(row("Koi schedule ya alarm nahi"))
        else for (r in rems) {
            val d = SimpleDateFormat("d MMM, hh:mm a", Locale.getDefault()).format(Date(r.second))
            schedBox.addView(row("$d  -  ${r.third}"))
        }
        eventBox.removeAllViews()
        val evs = Store.events(this)
        if (evs.isEmpty()) eventBox.addView(row("Koi birthday/anniversary set nahi"))
        else for (e in evs) eventBox.addView(row("${e.first}  -  ${e.third}  -  ${e.second}"))
    }

    private fun row(s: String): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = 14f
        t.setTextColor(0xFF22305A.toInt())
        t.setBackgroundResource(R.drawable.bubble_bg)
        val p = (10 * resources.displayMetrics.density).toInt()
        t.setPadding(p, p, p, p)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = (6 * resources.displayMetrics.density).toInt()
        t.layoutParams = lp
        return t
    }

    private fun tryStart() {
        if (!Settings.canDrawOverlays(this)) { showPermDialog(); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            showPermDialog(); return
        }
        if (!isAccEnabled()) {
            Toast.makeText(this, "Click/type ke liye Accessibility ON karo", Toast.LENGTH_SHORT).show()
            openAccSettings()
            return
        }
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
        try { startService(i) } catch (e: Exception) {}
        updateUi()
    }

    private fun isAccEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return enabled.contains(packageName) && enabled.contains("MitraAccessibilityService")
    }

    private fun showSettings() {
        val items = arrayOf(
            "Permissions do", "Click/Type permission", "Autostart ON karo",
            "Gemini key", "Test AI", "Jaanch (diagnose)"
        )
        AlertDialog.Builder(this).setTitle("Settings").setItems(items) { _, w ->
            when (w) {
                0 -> showPermDialog()
                1 -> openAccSettings()
                2 -> openAutostart()
                3 -> showKeyDialog()
                4 -> testAi()
                5 -> showDiagnose()
            }
        }.show()
    }

    private fun showPermDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_perm, null)
        val dlg = AlertDialog.Builder(this).setView(view).create()
        view.findViewById<Button>(R.id.allowAll).setOnClickListener {
            dlg.dismiss(); grantNext()
        }
        dlg.show()
    }

    private fun grantNext() {
        if (!Settings.canDrawOverlays(this)) {
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")), REQ_OVERLAY
            )
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf("android.permission.POST_NOTIFICATIONS"), REQ_NOTIF)
            return
        }
        Toast.makeText(this, "Sab set! Ab ON karo", Toast.LENGTH_SHORT).show()
        startMitra()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        grantNext()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OVERLAY) grantNext()
    }

    private fun openAccSettings() {
        try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (e: Exception) {}
        Toast.makeText(this, "List me 'Bublu' dhoondh ke ON karo", Toast.LENGTH_LONG).show()
    }

    private fun openAutostart() {
        val candidates = listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")
        )
        for (c in candidates) {
            try {
                val i = Intent().setComponent(c)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
                Toast.makeText(this, "Bublu ko 'Autostart' / 'Background' me ON karo", Toast.LENGTH_LONG).show()
                return
            } catch (e: Exception) {}
        }
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            Toast.makeText(this, "Bublu ki settings me 'Autostart'/'Background' ON karo", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {}
    }

    private fun showKeyDialog() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.hint = "Gemini API key"
        input.setText(prefs.getString("gemini_key", ""))
        AlertDialog.Builder(this)
            .setTitle("Gemini API key")
            .setMessage("Free key: aistudio.google.com/app/apikey\nKey sirf aapke phone me rehti hai.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putString("gemini_key", input.text.toString().trim()).apply()
                Toast.makeText(this, "Save ho gayi", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun testAi() {
        val key = prefs.getString("gemini_key", "")?.trim() ?: ""
        if (key.isBlank()) { Toast.makeText(this, "Pehle Gemini key daalo", Toast.LENGTH_SHORT).show(); return }
        Toast.makeText(this, "Check kar raha hoon...", Toast.LENGTH_SHORT).show()
        Thread {
            val (ok, text) = Gemini.ask(key, "Reply with exactly: OK")
            runOnUiThread {
                if (ok) Toast.makeText(this, "AI chal raha hai\n$text", Toast.LENGTH_LONG).show()
                else Toast.makeText(this, "AI error\n$text", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun showDiagnose() {
        val accOn = isAccEnabled()
        val overlayOn = Settings.canDrawOverlays(this)
        val micOn = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val keyOn = (prefs.getString("gemini_key", "") ?: "").isNotBlank()
        val msg = "1. Accessibility: " + (if (accOn) "ON (theek)" else "OFF (gadbad!) - Settings > Accessibility > Bublu > ON karo") +
            "\n2. Overlay: " + (if (overlayOn) "ON (theek)" else "OFF (gadbad!)") +
            "\n3. Microphone: " + (if (micOn) "ON (theek)" else "OFF (gadbad!)") +
            "\n4. Gemini key: " + (if (keyOn) "daali hui (theek)" else "nahi daali") +
            "\n\nAccessibility OFF ho to apps kholna, click, type kuch nahi chalega."
        AlertDialog.Builder(this)
            .setTitle("Bublu ki jaanch")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .setNeutralButton("Accessibility kholo") { _, _ -> openAccSettings() }
            .show()
    }
}
