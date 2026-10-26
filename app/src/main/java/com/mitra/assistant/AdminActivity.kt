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

        g.setText(prefs.getString("gemini_key", ""))
        s.setText(prefs.getString("sarvam_key", ""))
        u.setText(RemoteConfig.configUrl(this))
        c.setText(prefs.getString("admin_code", "BUBLU2026"))

        findViewById<Button>(R.id.saveAdmin).setOnClickListener {
            val gk = g.text.toString().trim()
            val sk = s.text.toString().trim()
            prefs.edit()
                .putString("gemini_key", gk)
                .putString("sarvam_key", sk)
                .putString("config_url", u.text.toString().trim())
                .putString("fb_email", fe.text.toString().trim())
                .putString("fb_pass", fp.text.toString())
                .putString("db_secret", ds.text.toString().trim())
                .putString("admin_code", c.text.toString().trim().ifBlank { "BUBLU2026" })
                .apply()
            Toast.makeText(this, "Saved. Publishing to all devices...", Toast.LENGTH_SHORT).show()
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
}
