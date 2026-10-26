package com.mitra.assistant

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** Bublu ka apna Notes editor - Title + Body, save / edit / delete. */
class NoteActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO)
        setContentView(R.layout.activity_note)

        val id = intent.getIntExtra("note_id", -1)
        val prefill = intent.getStringExtra("prefill") ?: ""

        val title = findViewById<EditText>(R.id.noteTitle)
        val body = findViewById<EditText>(R.id.noteBody)

        if (id > 0) {
            Store.getNoteV2(this, id)?.let {
                title.setText(it.second)
                body.setText(it.third)
            }
        } else if (prefill.isNotBlank()) {
            body.setText(prefill)
            val firstLine = prefill.lineSequence().firstOrNull()?.trim() ?: ""
            title.setText(firstLine.take(40).ifBlank { "Note" })
        }

        findViewById<Button>(R.id.saveNote).setOnClickListener {
            val t = title.text.toString().trim().ifBlank { "Note" }
            val b = body.text.toString().trim()
            if (b.isBlank()) {
                Toast.makeText(this, "Note is empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (id > 0) Store.updateNoteV2(this, id, t, b) else Store.addNoteV2(this, t, b)
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            finish()
        }

        findViewById<Button>(R.id.deleteNote).setOnClickListener {
            if (id > 0) Store.deleteNoteV2(this, id)
            finish()
        }

        findViewById<Button>(R.id.closeNote).setOnClickListener { finish() }
    }
}
