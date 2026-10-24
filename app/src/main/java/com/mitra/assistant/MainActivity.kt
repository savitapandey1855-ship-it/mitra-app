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
    private val REQ_CONTACTS = 104
    private lateinit var statusTxt: TextView
    private lateinit var toggleBtn: Button
    private lateinit var schedBox: LinearLayout
    private lateinit var eventBox: LinearLayout
    private lateinit var taskBox: LinearLayout
    private lateinit var noteBox: LinearLayout
    private lateinit var aliasBox: LinearLayout
    private lateinit var groupBox: LinearLayout
    private val prefs by lazy { getSharedPreferences("mitra", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusTxt = findViewById(R.id.statusTxt)
        toggleBtn = findViewById(R.id.toggleBtn)
        schedBox = findViewById(R.id.schedBox)
        eventBox = findViewById(R.id.eventBox)
        taskBox = findViewById(R.id.taskBox)
        noteBox = findViewById(R.id.noteBox)
        aliasBox = findViewById(R.id.aliasBox)
        groupBox = findViewById(R.id.groupBox)
        findViewById<Button>(R.id.setBtn).setOnClickListener { showSettings() }
        findViewById<Button>(R.id.modeBtn).setOnClickListener { switchMode() }
        toggleBtn.setOnClickListener { if (isRunning()) stopMitra() else tryStart() }
        updateModeBtn()
    }

    override fun onResume() {
        super.onResume()
        updateUi()
        updateModeBtn()
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
        if (rems.isEmpty()) schedBox.addView(row("Koi schedule ya alarm nahi", null))
        else for (r in rems) {
            val d = SimpleDateFormat("d MMM, hh:mm a", Locale.getDefault()).format(Date(r.second))
            schedBox.addView(row("$d  -  ${r.third}", "rem:${r.first}"))
        }
        taskBox.removeAllViews()
        val tasks = Store.tasks(this)
        if (tasks.isEmpty()) taskBox.addView(row("Koi task nahi - bolo 'task add karo ...'", null))
        else for (i in tasks.indices) {
            val t = tasks[i]
            taskBox.addView(row((if (t.second) "[done]  " else "[ ]  ") + t.first, "task:$i"))
        }
        noteBox.removeAllViews()
        val notes = Store.notes(this)
        if (notes.isEmpty()) noteBox.addView(row("Koi note nahi - bolo 'note banao ...'", null))
        else for (i in notes.indices) noteBox.addView(row(notes[i], "note:$i"))
        groupBox.removeAllViews()
        val grps = Store.importantGroups(this)
        if (grps.isEmpty()) groupBox.addView(row("Koi group nahi - bolo 'school group ko important set karo'", null))
        else for (g in grps) groupBox.addView(row(g, "grp:$g"))
        aliasBox.removeAllViews()
        val aliases = Store.aliases(this)
        if (aliases.isEmpty()) aliasBox.addView(row("Koi alias nahi - bolo 'bhaiya ka naam Ravi rakho'", null))
        else for (a in aliases) aliasBox.addView(row("${a.first}  =  ${a.second}", "alias:${a.first}"))
        eventBox.removeAllViews()
        val evs = Store.events(this)
        if (evs.isEmpty()) eventBox.addView(row("Koi birthday/anniversary set nahi", null))
        else for (i in evs.indices) {
            val e = evs[i]
            eventBox.addView(row("${e.first}  -  ${e.third}  -  ${e.second}", "evt:$i"))
        }
    }

    private fun row(s: String, tag: String?): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = 14f
        t.setTextColor(0xFF22305A.toInt())
        t.setBackgroundResource(R.drawable.card_row)
        val p = (10 * resources.displayMetrics.density).toInt()
        t.setPadding(p, p, p, p)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = (6 * resources.displayMetrics.density).toInt()
        t.layoutParams = lp
        if (tag != null) {
            t.isClickable = true
            t.setOnClickListener { onRowClick(tag) }
        }
        return t
    }

    private fun onRowClick(tag: String) {
        val parts = tag.split(":", limit = 2)
        val kind = parts[0]
        val key = parts.getOrElse(1) { "" }
        val options = if (kind == "task") arrayOf("Edit", "Mark done/undone", "Delete") else arrayOf("Edit", "Delete")
        AlertDialog.Builder(this).setTitle("Kya karna hai?").setItems(options) { _, w ->
            when (kind) {
                "rem" -> {
                    val id = key.toIntOrNull()
                    if (id != null) {
                        if (w == 0) {
                            val cur = Store.reminders(this).firstOrNull { it.first == id }?.third ?: ""
                            promptText("Schedule edit karo", cur) { Store.updateReminder(this, id, it) }
                        } else { Store.deleteReminder(this, id); loadLists() }
                    }
                }
                "task" -> {
                    val i = key.toIntOrNull()
                    if (i != null) {
                        val cur = Store.tasks(this).getOrNull(i)?.first ?: ""
                        when (w) {
                            0 -> promptText("Task edit karo", cur) { Store.updateTask(this, i, it) }
                            1 -> { Store.toggleTask(this, i); loadLists() }
                            else -> { Store.deleteTask(this, i); loadLists() }
                        }
                    }
                }
                "note" -> {
                    val i = key.toIntOrNull()
                    if (i != null) {
                        val cur = Store.notes(this).getOrNull(i) ?: ""
                        if (w == 0) promptText("Note edit karo", cur) { Store.updateNote(this, i, it) }
                        else { Store.deleteNote(this, i); loadLists() }
                    }
                }
                "grp" -> { if (w == 1) { Store.deleteGroup(this, key); loadLists() } }
                "alias" -> {
                    val cur = Store.aliases(this).firstOrNull { it.first.equals(key, true) }?.second ?: ""
                    if (w == 0) promptText("Alias target edit karo ($key)", cur) { Store.updateAlias(this, key, it) }
                    else { Store.deleteAlias(this, key); loadLists() }
                }
                "evt" -> {
                    val i = key.toIntOrNull()
                    if (i != null) {
                        val e = Store.events(this).getOrNull(i)
                        if (w == 0 && e != null) promptEvent(i, e) else { Store.deleteEvent(this, i); loadLists() }
                    }
                }
            }
        }.show()
    }

    private fun promptText(title: String, current: String, onSave: (String) -> Unit) {
        val input = EditText(this)
        input.setText(current)
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton("Save") { _, _ -> onSave(input.text.toString().trim()); loadLists() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun promptEvent(index: Int, e: Triple<String, String, String>) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val name = EditText(this); name.setText(e.first); name.hint = "Naam"
        val date = EditText(this); date.setText(e.second); date.hint = "dd/MM"
        val type = EditText(this); type.setText(e.third); type.hint = "Birthday/Anniversary"
        box.addView(name); box.addView(date); box.addView(type)
        AlertDialog.Builder(this).setTitle("Edit karo").setView(box)
            .setPositiveButton("Save") { _, _ ->
                Store.updateEvent(this, index, name.text.toString().trim(), date.text.toString().trim(), type.text.toString().trim())
                loadLists()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun updateModeBtn() {
        val cur = prefs.getString("mode", "bublu") ?: "bublu"
        findViewById<Button>(R.id.modeBtn).text = if (cur == "zora") "Mode: Zora  (tap for Bublu)" else "Mode: Bublu  (tap for Zora)"
        findViewById<ImageView>(R.id.avatarImg).setImageResource(if (cur == "zora") R.drawable.zora_idle else R.drawable.char_idle)
    }

    private fun switchMode() {
        val cur = prefs.getString("mode", "bublu") ?: "bublu"
        val next = if (cur == "zora") "bublu" else "zora"
        prefs.edit().putString("mode", next).apply()
        updateModeBtn()
        if (isRunning()) {
            stopMitra()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                prefs.edit().putBoolean("running", true).apply()
                val i = Intent(this, OverlayService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
                updateUi()
            }, 700)
        }
        Toast.makeText(this, if (next == "zora") "Zora mode ON" else "Bublu mode ON", Toast.LENGTH_SHORT).show()
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
            "Notification access ON karo", "Gemini key (AI)", "Sarvam key (voice)",
            "Awaaz (TTS) ON/OFF", "Test AI",
            "Alias add karo (nickname)", "Important group add karo", "Jaanch (diagnose)"
        )
        AlertDialog.Builder(this).setTitle("Settings").setItems(items) { _, w ->
            when (w) {
                0 -> showPermDialog()
                1 -> openAccSettings()
                2 -> openAutostart()
                3 -> openNotifAccess()
                4 -> showKeyDialog()
                5 -> showSarvamKeyDialog()
                6 -> toggleTts()
                7 -> testAi()
                8 -> addAliasDialog()
                9 -> addGroupDialog()
                10 -> showDiagnose()
            }
        }.show()
    }

    private fun toggleTts() {
        val on = !(prefs.getBoolean("tts_on", false))
        prefs.edit().putBoolean("tts_on", on).apply()
        val keyOk = (prefs.getString("sarvam_key", "") ?: "").isNotBlank()
        Toast.makeText(
            this,
            if (on) (if (keyOk) "Awaaz ON - Bublu bolega (Sarvam Bulbul)" else "Awaaz ON - par pehle Sarvam key daalo") else "Awaaz OFF",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun showSarvamKeyDialog() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.hint = "Sarvam api-subscription-key"
        input.setText(prefs.getString("sarvam_key", ""))
        AlertDialog.Builder(this)
            .setTitle("Sarvam key (Saaras v4 voice)")
            .setMessage("Key milegi: dashboard.sarvam.ai\nAgar key daali hai to Bublu aapki awaaz Sarvam se samjhega (Hinglish me behtar).\nKey sirf aapke phone me rehti hai.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putString("sarvam_key", input.text.toString().trim()).apply()
                Toast.makeText(this, "Sarvam key save ho gayi", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openNotifAccess() {
        try { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) } catch (e: Exception) {}
        Toast.makeText(this, "List me 'Bublu' ko ON karo", Toast.LENGTH_LONG).show()
    }

    private fun addGroupDialog() {
        val input = EditText(this)
        input.hint = "Group ka naam (jaise School Group)"
        AlertDialog.Builder(this)
            .setTitle("Important group add karo")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val g = input.text.toString().trim()
                if (g.isNotBlank()) {
                    Store.addImportantGroup(this, g)
                    loadLists()
                    Toast.makeText(this, "Save: $g", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addAliasDialog() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val pad = (14 * resources.displayMetrics.density).toInt()
        box.setPadding(pad, pad, pad, pad)
        val nick = EditText(this)
        nick.hint = "Nickname (jaise bhaiya)"
        val target = EditText(this)
        target.hint = "Asli naam ya number (jaise Ravi / 98765...)"
        box.addView(nick)
        box.addView(target)
        AlertDialog.Builder(this)
            .setTitle("Alias add karo")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val nk = nick.text.toString().trim()
                val tg = target.text.toString().trim()
                if (nk.isNotBlank() && tg.isNotBlank()) {
                    Store.addAlias(this, nk, tg)
                    loadLists()
                    Toast.makeText(this, "Alias save: $nk = $tg", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE),
                REQ_CONTACTS
            )
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
