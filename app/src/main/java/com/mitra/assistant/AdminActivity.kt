package com.mitra.assistant

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** Sirf owner ke liye - API keys aur Firebase config. */
class AdminActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("mitra", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO)
        setContentView(R.layout.activity_admin)

        val g = findViewById<EditText>(R.id.geminiKey)
        val s = findViewById<EditText>(R.id.sarvamKey)
        val u = findViewById<EditText>(R.id.configUrl)
        val c = findViewById<EditText>(R.id.adminCode)
        val fe = findViewById<EditText>(R.id.fbEmail)
        val fp = findViewById<EditText>(R.id.fbPass)
        val ds = findViewById<EditText>(R.id.dbSecret)

        fe.setText(prefs.getString("fb_email", ""))
        fp.setText(prefs.getString("fb_pass", ""))
        ds.setText(prefs.getString("db_secret", ""))

        KeyPool.init(this)

        // ---- multiple keys: har key apni row me, + se nayi row ----
        val gExtra = findViewById<android.widget.LinearLayout>(R.id.geminiExtra)
        val sExtra = findViewById<android.widget.LinearLayout>(R.id.sarvamExtra)
        val gRows = arrayListOf(g)
        val sRows = arrayListOf(s)

        val gKeys = KeyPool.keys("gemini")
        val sKeys = KeyPool.keys("sarvam")
        g.setText(gKeys.firstOrNull() ?: "")
        s.setText(sKeys.firstOrNull() ?: "")
        gKeys.drop(1).forEach { addKeyRow(gExtra, it, gRows) }
        sKeys.drop(1).forEach { addKeyRow(sExtra, it, sRows) }

        findViewById<Button>(R.id.addGeminiKey).setOnClickListener {
            addKeyRow(gExtra, "", gRows)
        }
        findViewById<Button>(R.id.addSarvamKey).setOnClickListener {
            addKeyRow(sExtra, "", sRows)
        }

        // ---- Cloudflare (free image generation) ----
        val cfAcc = findViewById<EditText>(R.id.cfAccount)
        val cfTok = findViewById<EditText>(R.id.cfToken)
        cfAcc.setText(prefs.getString("cf_account", "")?.ifBlank { "b3b1b21be5efcfa358164ff8c7bccba2" } ?: "b3b1b21be5efcfa358164ff8c7bccba2")
        cfTok.setText(prefs.getString("cf_token", ""))
        findViewById<Button>(R.id.btnTestCf).setOnClickListener {
            prefs.edit()
                .putString("cf_account", cfAcc.text.toString().trim())
                .putString("cf_token", cfTok.text.toString().trim())
                .apply()
            Toast.makeText(this, "Testing Cloudflare...", Toast.LENGTH_SHORT).show()
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
        u.setText(RemoteConfig.configUrl(this))
        c.setText(prefs.getString("admin_code", "BUBLU2026"))

        findViewById<Button>(R.id.saveAdmin).setOnClickListener {
            val gk = gRows.map { it.text.toString().trim() }.filter { it.isNotBlank() }.joinToString("\n")
            val sk = sRows.map { it.text.toString().trim() }.filter { it.isNotBlank() }.joinToString("\n")
            KeyPool.save("gemini", gk.split("\n", ","))
            KeyPool.save("sarvam", sk.split("\n", ","))
            prefs.edit()
                .putString("cf_account", findViewById<EditText>(R.id.cfAccount).text.toString().trim())
                .putString("cf_token", findViewById<EditText>(R.id.cfToken).text.toString().trim())
                .putString("config_url", u.text.toString().trim())
                .putString("fb_email", fe.text.toString().trim())
                .putString("fb_pass", fp.text.toString())
                .putString("db_secret", ds.text.toString().trim())
                .putString("admin_code", c.text.toString().trim().ifBlank { "BUBLU2026" })
                .apply()
            Toast.makeText(
                this,
                "Saved ${KeyPool.count("gemini")} AI keys + ${KeyPool.count("sarvam")} voice keys. Publishing...",
                Toast.LENGTH_SHORT
            ).show()
            Thread {
                val (ok, msg) = RemoteConfig.uploadKeys(this, gk, sk)
                runOnUiThread {
                    val detail = if (ok) {
                        "Every device will load these keys on first open."
                    } else {
                        "$msg\n\nTips:\n- HTTP 401/403 = rules publish nahi hue, ya login galat\n- 'EMAIL_NOT_FOUND' / 'INVALID_PASSWORD' = Authentication me user banao\n- 'OPERATION_NOT_ALLOWED' = Email/Password provider enable karo"
                    }
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(if (ok) "Published!" else "Publish failed")
                        .setMessage(detail)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }.start()
        }

        findViewById<Button>(R.id.testSarvam).setOnClickListener {
            val k = s.text.toString().trim()
            if (k.isBlank()) {
                Toast.makeText(this, "Sarvam key khaali hai", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Toast.makeText(this, "Testing...", Toast.LENGTH_SHORT).show()
            Thread {
                val (ok, msg) = Sarvam.testKey(k)
                runOnUiThread {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(if (ok) "Sarvam: working" else "Sarvam: failed")
                        .setMessage(msg)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }.start()
        }

        findViewById<Button>(R.id.fetchNow).setOnClickListener {
            prefs.edit().putString("config_url", u.text.toString().trim()).apply()
            Toast.makeText(this, "Fetching from Firebase...", Toast.LENGTH_SHORT).show()
            Thread {
                val ok = RemoteConfig.fetchAndStore(this)
                runOnUiThread {
                    if (ok) {
                        g.setText(prefs.getString("gemini_key", ""))
                        s.setText(prefs.getString("sarvam_key", ""))
                        Toast.makeText(this, "Keys loaded from Firebase", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "Could not fetch. Check the URL / JSON.", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }

        findViewById<Button>(R.id.closeAdmin).setOnClickListener { finish() }
    }

    /** Ek nayi key row (EditText + × button) add karo. */
    private fun addKeyRow(
        container: android.widget.LinearLayout,
        initial: String,
        list: MutableList<EditText>
    ) {
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val et = EditText(this).apply {
            hint = "key"
            setText(initial)
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF7C8BC7.toInt())
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        val del = Button(this).apply {
            text = "×"
            setTextColor(0xFFFFB4B4.toInt())
            setOnClickListener {
                container.removeView(row)
                list.remove(et)
            }
        }
        row.addView(et)
        row.addView(del)
        container.addView(row)
        list.add(et)
    }
}
