package com.mitra.assistant

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.MotionEvent
import android.view.View
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
    private val REQ_OVERLAY = 102
    private val REQ_NOTIF = 103
    private val REQ_CONTACTS = 104

    private lateinit var statusTxt: TextView
    private lateinit var toggleBtn: Button
    private lateinit var schedBox: LinearLayout
    private lateinit var taskBox: LinearLayout
    private lateinit var noteBox: LinearLayout
    private lateinit var groupBox: LinearLayout
    private lateinit var aliasBox: LinearLayout
    private lateinit var eventBox: LinearLayout

    private val prefs by lazy { getSharedPreferences("mitra", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private var adminHold: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Popups ke text saaf dikhein - hamesha light mode
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO)
        KeyPool.init(this)
        setContentView(R.layout.activity_main)
        statusTxt = findViewById(R.id.statusTxt)
        toggleBtn = findViewById(R.id.toggleBtn)
        schedBox = findViewById(R.id.schedBox)
        taskBox = findViewById(R.id.taskBox)
        noteBox = findViewById(R.id.noteBox)
        groupBox = findViewById(R.id.groupBox)
        aliasBox = findViewById(R.id.aliasBox)
        eventBox = findViewById(R.id.eventBox)

        findViewById<Button>(R.id.setBtn).setOnClickListener { showSettings() }
        findViewById<Button>(R.id.designBtn).setOnClickListener { pickDesign() }
        findViewById<View>(R.id.addSched).setOnClickListener { addSchedDialog() }
        findViewById<View>(R.id.addTask).setOnClickListener { addTaskDialog() }
        findViewById<View>(R.id.addNote).setOnClickListener {
            startActivity(Intent(this, NoteActivity::class.java))
        }
        findViewById<View>(R.id.addGroup).setOnClickListener { addGroupDialog() }
        findViewById<View>(R.id.addAlias).setOnClickListener { addAliasDialog() }
        findViewById<View>(R.id.addEvent).setOnClickListener { addEventDialog() }
        toggleBtn.setOnClickListener { if (isRunning()) stopBublu() else tryStart() }

        setupAdminHold()
        updateUi()
        updateDesign()
        loadLists()

        // First launch: silently load keys from Firebase (if configured)
        Thread { RemoteConfig.fetchAndStore(this) }.start()
    }

    override fun onResume() {
        super.onResume()
        updateUi()
        updateDesign()
        loadLists()
        setupShowing = false
        handler.postDelayed({ maybeShowSetupStep() }, 700)
    }

    // ---------- Step-by-step setup (auto on open) ----------
    private var setupShowing = false

    private fun isBatteryOk(): Boolean {
        return try {
            val pm = getSystemService(android.os.PowerManager::class.java)
            pm.isIgnoringBatteryOptimizations(packageName)
        } catch (e: Exception) { true }
    }

    private fun requestBattery() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (e2: Exception) {}
        }
    }

    private fun firstMissingStep(): Int? {
        if (!Settings.canDrawOverlays(this)) return 1
        if (!isBatteryOk()) return 2
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return 3
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) return 4
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return 5
        if (!isAccEnabled()) return 6
        if (!hasNotifAccess()) return 7
        return null
    }

    private fun hasNotifAccess(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        return flat.contains(packageName)
    }

    private fun maybeShowSetupStep() {
        if (setupShowing) return
        // Popup permission sabse zaroori - iske bina kuch kaam nahi karta
        if (!Settings.canDrawOverlays(this)) { showStepDialog(1); return }
        if (prefs.getBoolean("setup_done", false)) return
        val step = firstMissingStep()
        if (step == null) {
            prefs.edit().putBoolean("setup_done", true).apply()
            return
        }
        showStepDialog(step)
    }

    private fun showStepDialog(step: Int) {
        setupShowing = true
        val title: String
        val msg: String
        when (step) {
            1 -> { title = "Step 1 of 7 · Popup (display over other apps)"; msg = "Bublu floats on your screen so you can talk to it anywhere.\n\nTap \"Open settings\", then turn Bublu ON." }
            2 -> { title = "Step 2 of 7 · Run in background"; msg = "Allow Bublu to keep working in the background so it doesn't stop by itself.\n\nTap Allow." }
            3 -> { title = "Step 3 of 7 · Microphone"; msg = "So Bublu can hear your voice.\n\nTap Allow." }
            4 -> { title = "Step 4 of 7 · Notifications"; msg = "For reminders, alarms and important group messages." }
            5 -> { title = "Step 5 of 7 · Contacts & Phone"; msg = "To find people by name and call them." }
            6 -> { title = "Step 6 of 7 · Accessibility"; msg = "This lets Bublu open apps, tap buttons and type.\n\nFind Bublu in the list and turn it ON." }
            else -> { title = "Step 7 of 7 · Notification access"; msg = "Optional. Lets Bublu read messages from your important groups." }
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setCancelable(false)
            .setPositiveButton("Open settings") { _, _ -> openStep(step) }
            .setNegativeButton(if (step == 7) "Skip" else "Later") { _, _ ->
                if (step == 7) prefs.edit().putBoolean("setup_done", true).apply()
                if (step == 1) Toast.makeText(
                    this,
                    "Without this, Bublu cannot open apps or do anything.",
                    Toast.LENGTH_LONG
                ).show()
            }
            .create()
        dlg.setOnDismissListener { setupShowing = false }
        dlg.show()
    }

    private fun openStep(step: Int) {
        when (step) {
            1 -> startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")), REQ_OVERLAY
            )
            2 -> requestBattery()
            3 -> ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            4 -> if (Build.VERSION.SDK_INT >= 33)
                ActivityCompat.requestPermissions(this, arrayOf("android.permission.POST_NOTIFICATIONS"), REQ_NOTIF)
            5 -> ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE), REQ_CONTACTS
            )
            6 -> openAccSettings()
            7 -> openNotifAccess()
        }
    }

    private fun isRunning() = prefs.getBoolean("running", false)

    private fun updateUi() {
        if (isRunning()) {
            statusTxt.text = "Running - tap the bubble and speak"
            toggleBtn.text = "Turn OFF"
        } else {
            statusTxt.text = "Stopped"
            toggleBtn.text = "Turn ON"
        }
    }

    private fun designName(d: Int) = when (d) {
        1 -> "Cyan"
        2 -> "Purple"
        3 -> "Green"
        else -> "Blue"
    }

    private fun updateDesign() {
        val d = prefs.getInt("design", 0)
        findViewById<ImageView>(R.id.avatarImg).setImageResource(if (d == 1) R.drawable.zora_idle else R.drawable.char_idle)
        findViewById<Button>(R.id.designBtn).text = "Design: " + designName(d)
    }

    private fun pickDesign() {
        val names = arrayOf("Blue (classic)", "Cyan (tech ring)", "Purple", "Green")
        AlertDialog.Builder(this).setTitle("Choose Bublu's design")
            .setItems(names) { _, w ->
                prefs.edit().putInt("design", w).apply()
                updateDesign()
                if (isRunning()) restartService()
                Toast.makeText(this, "Design: " + names[w], Toast.LENGTH_SHORT).show()
            }.show()
    }

    // ---------- Admin gate: hold the face for 30 seconds ----------
    private fun setupAdminHold() {
        val avatar = findViewById<ImageView>(R.id.avatarImg)
        avatar.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    adminHold?.let { handler.removeCallbacks(it) }
                    val r = Runnable { showAdminCodeDialog() }
                    adminHold = r
                    handler.postDelayed(r, 30000)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    adminHold?.let { handler.removeCallbacks(it) }
            }
            false
        }
    }

    private fun showAdminCodeDialog() {
        val input = EditText(this)
        input.setTextColor(0xFF101733.toInt())
        input.setHintTextColor(0xFF6B7391.toInt())
        input.hint = "Admin code"
        AlertDialog.Builder(this).setTitle("Admin access")
            .setMessage("Enter the admin code to manage API keys.")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val expected = prefs.getString("admin_code", "BUBLU2026") ?: "BUBLU2026"
                if (input.text.toString().trim() == expected) {
                    startActivity(Intent(this, AdminActivity::class.java))
                } else {
                    Toast.makeText(this, "Wrong code", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ---------- Lists ----------
    private fun loadLists() {
        schedBox.removeAllViews()
        val rems = Store.reminders(this).sortedBy { it.second }
        if (rems.isEmpty()) schedBox.addView(row("No schedules yet - tap + to add", null))
        else for (r in rems) {
            val d = SimpleDateFormat("d MMM, hh:mm a", Locale.getDefault()).format(Date(r.second))
            schedBox.addView(row("$d  -  ${r.third}", "rem:${r.first}"))
        }

        taskBox.removeAllViews()
        val tasks = Store.tasks(this)
        if (tasks.isEmpty()) taskBox.addView(row("No tasks yet - tap + to add", null))
        else for (i in tasks.indices) {
            val t = tasks[i]
            taskBox.addView(row((if (t.second) "[done]  " else "[ ]  ") + t.first, "task:$i"))
        }

        noteBox.removeAllViews()
        // purane notes ko naye format me le aao
        if (Store.notesV2(this).isEmpty() && Store.notes(this).isNotEmpty()) {
            for (t in Store.notes(this)) Store.addNoteV2(this, t.take(40).ifBlank { "Note" }, t)
        }
        val notes2 = Store.notesV2(this)
        if (notes2.isEmpty()) noteBox.addView(row("No notes yet - tap + to add", null))
        else for (n in notes2) {
            val preview = n.third.replace("\n", " ").take(34)
            noteBox.addView(row("${n.second}  —  $preview", "note2:${n.first}"))
        }

        groupBox.removeAllViews()
        val grps = Store.importantGroups(this)
        if (grps.isEmpty()) groupBox.addView(row("No groups yet - tap + to add", null))
        else for (g in grps) groupBox.addView(row(g, "grp:$g"))

        aliasBox.removeAllViews()
        val aliases = Store.aliases(this)
        if (aliases.isEmpty()) aliasBox.addView(row("No nicknames yet - tap + to add", null))
        else for (a in aliases) aliasBox.addView(row("${a.first}  =  ${a.second}", "alias:${a.first}"))

        eventBox.removeAllViews()
        val evs = Store.events(this)
        if (evs.isEmpty()) eventBox.addView(row("Nothing yet - tap + to add", null))
        else for (i in evs.indices) {
            val e = evs[i]
            eventBox.addView(row("${e.first}  -  ${e.third}  -  ${e.second}", "evt:$i"))
        }
    }

    private fun row(s: String, tag: String?): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = 14f
        t.setTextColor(0xFFE7ECFF.toInt())
        t.setBackgroundResource(R.drawable.row_dark)
        val p = (12 * resources.displayMetrics.density).toInt()
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
        val options = if (kind == "task") arrayOf("Edit", "Mark done / undone", "Delete") else arrayOf("Edit", "Delete")
        AlertDialog.Builder(this).setTitle("What do you want to do?").setItems(options) { _, w ->
            when (kind) {
                "rem" -> {
                    val id = key.toIntOrNull()
                    if (id != null) {
                        if (w == 0) {
                            val cur = Store.reminders(this).firstOrNull { it.first == id }?.third ?: ""
                            promptText("Edit schedule", cur) { Store.updateReminder(this, id, it) }
                        } else { Store.deleteReminder(this, id); loadLists() }
                    }
                }
                "task" -> {
                    val i = key.toIntOrNull()
                    if (i != null) {
                        val cur = Store.tasks(this).getOrNull(i)?.first ?: ""
                        when (w) {
                            0 -> promptText("Edit task", cur) { Store.updateTask(this, i, it) }
                            1 -> { Store.toggleTask(this, i); loadLists() }
                            else -> { Store.deleteTask(this, i); loadLists() }
                        }
                    }
                }
                "note2" -> {
                    val id = key.toIntOrNull()
                    if (id != null) {
                        if (w == 0) startActivity(Intent(this, NoteActivity::class.java).putExtra("note_id", id))
                        else { Store.deleteNoteV2(this, id); loadLists() }
                    }
                }
                "note" -> {
                    val i = key.toIntOrNull()
                    if (i != null) {
                        val cur = Store.notes(this).getOrNull(i) ?: ""
                        if (w == 0) promptText("Edit note", cur) { Store.updateNote(this, i, it) }
                        else { Store.deleteNote(this, i); loadLists() }
                    }
                }
                "grp" -> { if (w == 1) { Store.deleteGroup(this, key); loadLists() } }
                "alias" -> {
                    val cur = Store.aliases(this).firstOrNull { it.first.equals(key, true) }?.second ?: ""
                    if (w == 0) promptText("Edit nickname target ($key)", cur) { Store.updateAlias(this, key, it) }
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
        val pad = (14 * resources.displayMetrics.density).toInt()
        box.setPadding(pad, pad, pad, pad)
        val name = EditText(this); name.setText(e.first); name.hint = "Name"
        val date = EditText(this); date.setText(e.second); date.hint = "Date (dd/MM)"
        val type = EditText(this); type.setText(e.third); type.hint = "Birthday / Engagement / Exam"
        box.addView(name); box.addView(date); box.addView(type)
        AlertDialog.Builder(this).setTitle("Edit").setView(box)
            .setPositiveButton("Save") { _, _ ->
                Store.updateEvent(this, index, name.text.toString().trim(), date.text.toString().trim(), type.text.toString().trim())
                loadLists()
            }.setNegativeButton("Cancel", null).show()
    }

    // ---------- Add dialogs ----------
    private fun addTaskDialog() {
        val input = EditText(this); input.hint = "New task"
        AlertDialog.Builder(this).setTitle("Add task").setView(input)
            .setPositiveButton("Save") { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotBlank()) { Store.addTask(this, t); loadLists() }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun addNoteDialog() {
        startActivity(Intent(this, NoteActivity::class.java))
    }

    private fun addSchedDialog() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val pad = (14 * resources.displayMetrics.density).toInt()
        box.setPadding(pad, pad, pad, pad)
        val text = EditText(this); text.hint = "What should I remind you about?"
        val hh = EditText(this); hh.hint = "Hour (1-12)"; hh.inputType = InputType.TYPE_CLASS_NUMBER
        val mm = EditText(this); mm.hint = "Minute (0-59)"; mm.inputType = InputType.TYPE_CLASS_NUMBER
        val ampm = android.widget.Spinner(this)
        ampm.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, arrayOf("AM", "PM"))
        box.addView(text); box.addView(hh); box.addView(mm); box.addView(ampm)
        AlertDialog.Builder(this).setTitle("Add schedule / alarm").setView(box)
            .setPositiveButton("Save") { _, _ ->
                val t = text.text.toString().trim()
                val h = hh.text.toString().toIntOrNull() ?: -1
                val m = mm.text.toString().toIntOrNull() ?: 0
                val pm = ampm.selectedItemPosition == 1
                if (t.isNotBlank() && h in 1..12) {
                    val h24 = if (pm) (h % 12) + 12 else (h % 12)
                    Scheduler.schedule(this, t, h24, m.coerceIn(0, 59), 0)
                    loadLists()
                    Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
                } else Toast.makeText(this, "Enter text and hour (1-12)", Toast.LENGTH_LONG).show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun addEventDialog() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val pad = (14 * resources.displayMetrics.density).toInt()
        box.setPadding(pad, pad, pad, pad)
        val name = EditText(this); name.hint = "Name (e.g. Didi)"
        val date = EditText(this); date.hint = "Date (dd/MM, e.g. 11/11)"
        val type = android.widget.Spinner(this)
        type.adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            arrayOf("Birthday", "Anniversary", "Engagement", "Exam", "Other")
        )
        box.addView(name); box.addView(date); box.addView(type)
        AlertDialog.Builder(this).setTitle("Add birthday / occasion").setView(box)
            .setPositiveButton("Save") { _, _ ->
                val n = name.text.toString().trim()
                val d = date.text.toString().trim()
                val ty = type.selectedItem.toString()
                if (n.isNotBlank() && d.isNotBlank()) { Store.addEvent(this, n, d, ty); loadLists() }
                else Toast.makeText(this, "Enter name and date", Toast.LENGTH_LONG).show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun addGroupDialog() {
        val input = EditText(this); input.hint = "Group name (e.g. School Group)"
        AlertDialog.Builder(this).setTitle("Add important group").setView(input)
            .setPositiveButton("Save") { _, _ ->
                val g = input.text.toString().trim()
                if (g.isNotBlank()) { Store.addImportantGroup(this, g); loadLists() }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun addAliasDialog() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val pad = (14 * resources.displayMetrics.density).toInt()
        box.setPadding(pad, pad, pad, pad)
        val nick = EditText(this); nick.hint = "Nickname (e.g. bhaiya)"
        val target = EditText(this); target.hint = "Real name or number (e.g. Ravi / 98765...)"
        box.addView(nick); box.addView(target)
        AlertDialog.Builder(this).setTitle("Add nickname").setView(box)
            .setPositiveButton("Save") { _, _ ->
                val nk = nick.text.toString().trim()
                val tg = target.text.toString().trim()
                if (nk.isNotBlank() && tg.isNotBlank()) {
                    Store.addAlias(this, nk, tg)
                    loadLists()
                    Toast.makeText(this, "Saved: $nk = $tg", Toast.LENGTH_SHORT).show()
                }
            }.setNegativeButton("Cancel", null).show()
    }

    // ---------- Start / stop ----------
    private fun tryStart() {
        if (!Settings.canDrawOverlays(this)) { showPermDialog(); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            showPermDialog(); return
        }
        if (!isAccEnabled()) {
            Toast.makeText(this, "Turn ON Bublu in Accessibility first", Toast.LENGTH_LONG).show()
            openAccSettings()
            return
        }
        startBublu()
    }

    private fun startBublu() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Turn ON 'Display over other apps' first - Bublu needs it", Toast.LENGTH_LONG).show()
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                REQ_OVERLAY
            )
            return
        }
        prefs.edit().putBoolean("running", true).apply()
        val i = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        updateUi()
        finish()
    }

    private fun stopBublu() {
        prefs.edit().putBoolean("running", false).apply()
        val i = Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP)
        try { startService(i) } catch (e: Exception) {}
        updateUi()
    }

    private fun restartService() {
        stopBublu()
        handler.postDelayed({
            prefs.edit().putBoolean("running", true).apply()
            val i = Intent(this, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
            updateUi()
        }, 700)
    }

    private fun isAccEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return enabled.contains(packageName) && enabled.contains("MitraAccessibilityService")
    }

    // ---------- Settings ----------
    private fun showSettings() {
        val fest = if (prefs.getBoolean("festival_on", true)) "ON" else "OFF"
        val items = arrayOf(
            "Permissions & setup",
            "Accessibility (tap & type)",
            "Notification access (important groups)",
            "Autostart (background)",
            "Background & popup permissions",
            "Voice",
            "Speak replies: " + (if (prefs.getBoolean("tts_on", true)) "ON" else "OFF"),
            "My details (name & class)",
            "Test AI",
            "Add nickname",
            "Add important group",
            "Festival announcements: $fest",
            "Diagnose"
        )
        AlertDialog.Builder(this).setTitle("Settings").setItems(items) { _, w ->
            when (w) {
                0 -> { prefs.edit().putBoolean("setup_done", false).apply(); maybeShowSetupStep() }
                1 -> openAccSettings()
                2 -> openNotifAccess()
                3 -> openAutostart()
                4 -> backgroundDialog()
                5 -> pickVoice()
                6 -> toggleSpeak()
                7 -> myDetailsDialog()
                8 -> testAi()
                9 -> addAliasDialog()
                10 -> addGroupDialog()
                11 -> toggleFestival()
                12 -> showDiagnose()
            }
        }.show()
    }

    private fun backgroundDialog() {
        val items = arrayOf(
            "Allow background running (battery)",
            "Autostart (Xiaomi / Realme / Vivo / Oppo)",
            "Popup permission (display over other apps)"
        )
        AlertDialog.Builder(this).setTitle("Background & popup")
            .setItems(items) { _, w ->
                when (w) {
                    0 -> requestBattery()
                    1 -> openAutostart()
                    2 -> startActivityForResult(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                        REQ_OVERLAY
                    )
                }
            }.show()
    }

    private fun toggleSpeak() {
        val on = !prefs.getBoolean("tts_on", true)
        prefs.edit().putBoolean("tts_on", on).apply()
        Toast.makeText(this, if (on) "Bublu will speak" else "Bublu will stay silent", Toast.LENGTH_SHORT).show()
    }

    private fun myDetailsDialog() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val pad = (14 * resources.displayMetrics.density).toInt()
        box.setPadding(pad, pad, pad, pad)
        val name = EditText(this)
        name.hint = "Your name"
        name.setText(prefs.getString("my_name", ""))
        val cls = EditText(this)
        cls.hint = "Your class (e.g. 10-B)"
        cls.setText(prefs.getString("my_class", ""))
        box.addView(name); box.addView(cls)
        AlertDialog.Builder(this).setTitle("My details")
            .setMessage("Used when Bublu writes applications or messages for you.")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit()
                    .putString("my_name", name.text.toString().trim())
                    .putString("my_class", cls.text.toString().trim())
                    .apply()
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun toggleFestival() {
        val on = !prefs.getBoolean("festival_on", true)
        prefs.edit().putBoolean("festival_on", on).apply()
        Toast.makeText(this, if (on) "Festival announcements ON" else "Festival announcements OFF", Toast.LENGTH_LONG).show()
    }

    private fun pickVoice() {
        val names = arrayOf("Priya (female)", "Shubh (male)", "Ritu (female)", "Aditya (male)", "Kavya (female)", "Rahul (male)")
        val ids = arrayOf("priya", "shubh", "ritu", "aditya", "kavya", "rahul")
        AlertDialog.Builder(this).setTitle("Choose Bublu's voice").setItems(names) { _, w ->
            prefs.edit().putString("tts_speaker", ids[w]).apply()
            Toast.makeText(this, "Voice: ${names[w]}", Toast.LENGTH_SHORT).show()
        }.show()
    }

    private fun testAi() {
        val key = prefs.getString("gemini_key", "")?.trim() ?: ""
        if (key.isBlank()) { Toast.makeText(this, "AI key not set (admin only)", Toast.LENGTH_LONG).show(); return }
        Toast.makeText(this, "Checking...", Toast.LENGTH_SHORT).show()
        Thread {
            val (ok, text) = Gemini.ask(key, "Reply with exactly: OK")
            runOnUiThread {
                if (ok) Toast.makeText(this, "AI is working\n$text", Toast.LENGTH_LONG).show()
                else Toast.makeText(this, "AI error\n$text", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun showPermDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_perm, null)
        val dlg = AlertDialog.Builder(this).setView(view).create()
        view.findViewById<Button>(R.id.allowAll).setOnClickListener { dlg.dismiss(); grantNext() }
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
        Toast.makeText(this, "All set! Now turn ON.", Toast.LENGTH_SHORT).show()
        startBublu()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        setupShowing = false
        handler.postDelayed({ maybeShowSetupStep() }, 600)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OVERLAY) grantNext()
    }

    private fun openAccSettings() {
        try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (e: Exception) {}
        Toast.makeText(this, "Find 'Bublu' in the list and turn it ON", Toast.LENGTH_LONG).show()
    }

    private fun openNotifAccess() {
        try { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) } catch (e: Exception) {}
        Toast.makeText(this, "Find 'Bublu' and turn it ON", Toast.LENGTH_LONG).show()
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
                Toast.makeText(this, "Turn ON Autostart / Background for Bublu", Toast.LENGTH_LONG).show()
                return
            } catch (e: Exception) {}
        }
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            Toast.makeText(this, "Turn ON Autostart / Background for Bublu", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {}
    }

    private fun showDiagnose() {
        val accOn = isAccEnabled()
        val overlayOn = Settings.canDrawOverlays(this)
        val micOn = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val keyOn = (prefs.getString("gemini_key", "") ?: "").isNotBlank()
        val sarvamOn = (prefs.getString("sarvam_key", "") ?: "").isNotBlank()
        val battOk = isBatteryOk()
        val msg = "1. Accessibility: " + (if (accOn) "ON (good)" else "OFF (needed for apps, tap, type)") +
            "\n2. Display over apps: " + (if (overlayOn) "ON (good)" else "OFF (needed)") +
            "\n2b. Background running: " + (if (battOk) "allowed (good)" else "restricted (needed)") +
            "\n3. Microphone: " + (if (micOn) "ON (good)" else "OFF (needed)") +
            "\n4. AI key: " + (if (keyOn) "present (good)" else "missing") +
            "\n5. Voice key: " + (if (sarvamOn) "present (good)" else "missing") +
            "\n\nIf Accessibility is OFF, Bublu cannot open apps or tap buttons."
        AlertDialog.Builder(this)
            .setTitle("Bublu health check")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .setNeutralButton("Accessibility") { _, _ -> openAccSettings() }
            .show()
    }
}
