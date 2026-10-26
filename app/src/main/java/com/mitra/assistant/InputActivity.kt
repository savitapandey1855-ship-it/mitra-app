package com.mitra.assistant

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Neeche se aata curved input box - type karke Bublu ko command do. */
class InputActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_input)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val box = findViewById<EditText>(R.id.inputText)
        val close = findViewById<TextView>(R.id.btnCloseInput)

        close.setOnClickListener { finish() }

        box.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                send(box.text.toString())
                true
            } else false
        }

        findViewById<TextView>(R.id.btnSendInput).setOnClickListener {
            send(box.text.toString())
        }

        // keyboard khud khul jaaye
        box.requestFocus()
        handler.postDelayed({
            try {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(box, InputMethodManager.SHOW_IMPLICIT)
            } catch (e: Exception) {}
        }, 220)
    }

    private fun send(text: String) {
        val t = text.trim()
        if (t.isBlank()) {
            finish()
            return
        }
        try {
            val i = Intent(this, OverlayService::class.java)
            i.action = "TYPED_CMD"
            i.putExtra("text", t)
            startService(i)
        } catch (e: Exception) {}
        try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(findViewById<View>(R.id.inputText).windowToken, 0)
        } catch (e: Exception) {}
        finish()
    }
}
