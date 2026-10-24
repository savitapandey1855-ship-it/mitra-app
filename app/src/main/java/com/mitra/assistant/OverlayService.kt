package com.mitra.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlin.math.abs

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var root: LinearLayout
    private lateinit var bubble: TextView
    private lateinit var reply: TextView
    private lateinit var params: WindowManager.LayoutParams
    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotif()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        buildOverlay()
        initRecognizer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun startForegroundNotif() {
        val chId = "mitra_ch"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(chId, "Mitra", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(ch)
        }
        val notif = NotificationCompat.Builder(this, chId)
            .setContentTitle("Mitra chal raha hai")
            .setContentText("Bubble pe tap karke bolo")
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notif)
        }
    }

    private fun buildOverlay() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        reply = TextView(this).apply {
            visibility = View.GONE
            textSize = 13f
            setTextColor(0xFF3A2E5A.toInt())
            setBackgroundResource(R.drawable.bubble_bg)
            val p = (12 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
            maxWidth = (220 * resources.displayMetrics.density).toInt()
        }
        val rlp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        rlp.bottomMargin = (8 * resources.displayMetrics.density).toInt()
        root.addView(reply, rlp)

        bubble = TextView(this).apply {
            text = "\uD83D\uDE0A"
            textSize = 34f
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bubble_circle)
            val p = (14 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        root.addView(bubble, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 60
        params.y = 300

        var startX = 0
        var startY = 0
        var startTouchX = 0f
        var startTouchY = 0f
        var moved = false
        root.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    startTouchX = e.rawX; startTouchY = e.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - startTouchX).toInt()
                    val dy = (e.rawY - startTouchY).toInt()
                    if (abs(dx) > 12 || abs(dy) > 12) moved = true
                    params.x = startX + dx
                    params.y = startY + dy
                    wm.updateViewLayout(root, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onTap()
                    true
                }
                else -> false
            }
        }

        wm.addView(root, params)
    }

    private fun pop() {
        bubble.animate().scaleX(1.25f).scaleY(1.25f).setDuration(120)
            .withEndAction {
                bubble.animate().scaleX(1f).scaleY(1f)
                    .setInterpolator(OvershootInterpolator()).setDuration(180).start()
            }.start()
    }

    private fun setFace(emoji: String, say: String? = null) {
        bubble.text = emoji
        pop()
        if (say == null) {
            reply.visibility = View.GONE
        } else {
            reply.text = say
            reply.visibility = View.VISIBLE
        }
    }

    private fun initRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setFace("\uD83D\uDE15", "Mic nahi mila")
            return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p0: Bundle?) { setFace("\uD83D\uDC42") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(p0: Float) {}
            override fun onBufferReceived(p0: ByteArray?) {}
            override fun onEndOfSpeech() { setFace("\uD83E\uDD14") }
            override fun onError(p0: Int) {
                listening = false
                setFace("\uD83D\uDE05", "Samajh nahi aaya, phir bolo")
            }
            override fun onResults(p0: Bundle?) {
                listening = false
                val list = p0?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = list?.firstOrNull() ?: ""
                if (text.isBlank()) setFace("\uD83D\uDE05", "Kuch sunai nahi diya")
                else handle(text)
            }
            override fun onPartialResults(p0: Bundle?) {}
            override fun onEvent(p0: Int, p1: Bundle?) {}
        })
    }

    private fun onTap() {
        if (listening) return
        if (recognizer == null) initRecognizer()
        val r = recognizer ?: return
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Bolo...")
        }
        setFace("\uD83D\uDC42", "Sun raha hoon...")
        listening = true
        try {
            r.startListening(i)
        } catch (e: Exception) {
            listening = false
            setFace("\uD83D\uDE05", "Mic me dikkat")
        }
    }

    private fun handle(text: String) {
        val t = text.lowercase()
        val opened = tryOpen(t)
        if (opened != null) {
            setFace("\uD83D\uDE04", "Ye lo: $opened")
            return
        }
        if (t.contains("time") || t.contains("samay")) {
            val now = java.text.SimpleDateFormat(
                "hh:mm a", java.util.Locale.getDefault()
            ).format(java.util.Date())
            setFace("\u23F0", "Abhi $now")
            return
        }
        setFace("\uD83E\uDD14", "\"$text\" - ye abhi nahi aata, par main seekh raha hoon!")
    }

    private fun tryOpen(t: String): String? {
        val apps = listOf(
            Triple("youtube", "com.google.android.youtube", "YouTube"),
            Triple("whatsapp", "com.whatsapp", "WhatsApp"),
            Triple("instagram", "com.instagram.android", "Instagram"),
            Triple("gmail", "com.google.android.gm", "Gmail"),
            Triple("chrome", "com.android.chrome", "Chrome"),
            Triple("camera", "com.android.camera", "Camera"),
            Triple("settings", "com.android.settings", "Settings"),
            Triple("spotify", "com.spotify.music", "Spotify"),
            Triple("maps", "com.google.android.apps.maps", "Maps")
        )
        for ((key, pkg, name) in apps) {
            if (t.contains(key)) {
                val intent = packageManager.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { startActivity(intent); return name } catch (e: Exception) {}
                } else {
                    val web = Intent(
                        Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://www.google.com/search?q=$name")
                    )
                    web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { startActivity(web); return "$name (web)" } catch (e: Exception) {}
                }
            }
        }
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        try { recognizer?.destroy() } catch (e: Exception) {}
        try { wm.removeView(root) } catch (e: Exception) {}
    }
}
