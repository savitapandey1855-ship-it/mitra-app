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
        setContentView(R.layout.activity_admin)

        val g = findViewById<EditText>(R.id.geminiKey)
        val s = findViewById<EditText>(R.id.sarvamKey)
        val u = findViewById<EditText>(R.id.configUrl)
        val c = findViewById<EditText>(R.id.adminCode)

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
                .putString("admin_code", c.text.toString().trim().ifBlank { "BUBLU2026" })
                .apply()
            Toast.makeText(this, "Saved on this phone. Publishing to all devices...", Toast.LENGTH_SHORT).show()
            Thread {
                val ok = RemoteConfig.uploadKeys(this, gk, sk)
                runOnUiThread {
                    Toast.makeText(
                        this,
                        if (ok) "Published! Every device will load these keys on first open."
                        else "Saved locally. Upload failed - check the database write rules.",
                        Toast.LENGTH_LONG
                    ).show()
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
