package com.mitra.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlin.math.abs

class OverlayService : Service() {

    companion object {
        const val ACTION_STOP = "com.mitra.assistant.STOP"
        const val NAME = "Bublu"
    }

    private lateinit var wm: WindowManager
    private lateinit var root: LinearLayout
    private lateinit var face: ImageView
    private lateinit var reply: TextView
    private lateinit var params: WindowManager.LayoutParams
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotif()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        buildOverlay()
        initRecognizer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            getSharedPreferences("mitra", MODE_PRIVATE).edit().putBoolean("running", false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun startForegroundNotif() {
        val chId = "bublu_ch"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(chId, NAME, NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(ch)
        }
        val stopIntent = Intent(this, OverlayService::class.java).setAction(ACTION_STOP)
        val pi = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = NotificationCompat.Builder(this, chId)
            .setContentTitle("$NAME chal raha hai")
            .setContentText("Character pe tap karke bolo")
            .setSmallIcon(R.drawable.ic_notif)
            .setOngoing(true)
            .addAction(0, "Band karo", pi)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notif)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

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
            setPadding(dp(12), dp(10), dp(12), dp(10))
            maxWidth = dp(230)
        }
        val rlp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        rlp.bottomMargin = dp(6)
        root.addView(reply, rlp)

        face = ImageView(this).apply { setImageResource(R.drawable.char_idle) }
        root.addView(face, LinearLayout.LayoutParams(dp(74), dp(74)))

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
        face.animate().scaleX(1.22f).scaleY(1.22f).setDuration(120)
            .withEndAction {
                face.animate().scaleX(1f).scaleY(1f)
                    .setInterpolator(OvershootInterpolator()).setDuration(180).start()
            }.start()
    }

    private fun setMood(resId: Int, say: String? = null) {
        face.setImageResource(resId)
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
            setMood(R.drawable.char_sad, "Mic nahi mila")
            return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p0: Bundle?) { setMood(R.drawable.char_listen) }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(p0: Float) {}
            override fun onBufferReceived(p0: ByteArray?) {}
            override fun onEndOfSpeech() { setMood(R.drawable.char_think) }
            override fun onError(p0: Int) {
                listening = false
                setMood(R.drawable.char_sad, "Samajh nahi aaya, phir bolo")
            }
            override fun onResults(p0: Bundle?) {
                listening = false
                val list = p0?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = list?.firstOrNull() ?: ""
                if (text.isBlank()) setMood(R.drawable.char_sad, "Kuch sunai nahi diya")
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
        setMood(R.drawable.char_listen, "Sun raha hoon...")
        listening = true
        try {
            r.startListening(i)
        } catch (e: Exception) {
            listening = false
            setMood(R.drawable.char_sad, "Mic me dikkat")
        }
    }

    private fun pick(vararg opts: String): String = opts[(Math.random() * opts.size).toInt()]

    /** Pehle se set cute jawab (greeting, time, date, etc.) */
    private fun canned(t: String, n: String): String? {
        if (n == "hi" || n == "hii" || n == "hlo" || n == "hello" || n == "hey" ||
            n.startsWith("hello") || n.contains("namaste") || n.contains("namaskar"))
            return pick(
                "Hii! Main $NAME hoon. Kya karna hai?",
                "Hello! Bolo na, kya help chahiye?",
                "Namaste! Main $NAME hoon, tumhara cute assistant."
            )
        if (n.contains("goodmorning") || n == "gm")
            return pick(
                "Good morning! Aaj ka din shandar ho!",
                "Subah bakhair! Kya mast subah hai!",
                "Good morning! Aaj kuch naya seekhte hain!"
            )
        if (n.contains("goodnight") || n == "gn")
            return pick(
                "Good night! Meetha sapna dekhna.",
                "Shubh ratri! Aaram se so jao.",
                "Good night! Kal phir milte hain."
            )
        if (n.contains("goodevening")) return pick("Good evening! Kaise ho?", "Shubh sandhya! Kaise ho?")
        if (n.contains("goodafternoon")) return pick("Good afternoon!", "Namaste! Dopahar bakhair.")
        if (n.contains("howareyou") || n.contains("kaiseho") || n.contains("kaisihai"))
            return pick("Main ekdum mast! Tum batao?", "Main theek hoon! Tum kaise ho?")
        if (n.contains("yourname") || n.contains("tumharanaam") || n.contains("teranaam") ||
            n.contains("whoareyou") || n.contains("tumkaun"))
            return "Main $NAME hoon - tumhara cute assistant!"
        if (n.contains("thank") || n.contains("shukriya") || n.contains("dhanyavad"))
            return pick("Arre, koi baat nahi!", "Koi baat nahi, khushi hui!")
        if (n.contains("loveyou") || n.contains("iloveyou"))
            return "Aww, main bhi tumse pyaar karta hoon!"
        if (n.contains("whatcanyoudo") || n.contains("help") || n.contains("madad") ||
            n.contains("kyakarsakte"))
            return "Main apps khol sakta hoon, type/click/paste kar sakta hoon, aur koi bhi sawaal ka jawab de sakta hoon. Bas bolo!"
        if (n == "bye" || n.contains("goodbye") || n.contains("alvida"))
            return pick("Bye! Phir milenge.", "Chalo, phir milte hain!")
        if (n.contains("time") || n.contains("samay") || n.contains("kitnebaje")) {
            val now = java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault())
                .format(java.util.Date())
            return "Abhi $now baje hain."
        }
        if (n.contains("date") || n.contains("tareekh") || n.contains("aajkadin") ||
            n.contains("kaundin") || n.contains("aajkaun") || n.contains("month") ||
            n.contains("saal") || n.contains("year")) {
            val day = java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault())
                .format(java.util.Date())
            val d = java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale.getDefault())
                .format(java.util.Date())
            return "Aaj $day, $d hai."
        }
        return null
    }

    private fun handle(text: String) {
        val t = text.lowercase().trim()
        val n = t.replace(" ", "")

        // 1) Apps kholna
        val opened = tryOpen(t, n)
        if (opened != null) { setMood(R.drawable.char_happy, "Ye lo: $opened"); return }

        // 2) Pehle se set cute jawab
        val c = canned(t, n)
        if (c != null) { setMood(R.drawable.char_happy, c); return }

        // 3) Click / type / paste / navigation (accessibility)
        val acc = MitraAccessibilityService.instance
        if (acc == null) {
            if (looksLikeAction(t)) {
                setMood(R.drawable.char_sad, "Click/type ke liye Settings me $NAME ki Accessibility ON karo")
                return
            }
        } else {
            if (after(t, listOf("type ", "likho ", "likh "))?.let { acc.typeText(it) } == true) {
                setMood(R.drawable.char_happy, "Type kar diya"); return
            }
            if (n.contains("paste") || n.contains("chipka")) {
                if (acc.paste()) { setMood(R.drawable.char_happy, "Paste kar diya"); return }
            }
            val clickWhat = after(t, listOf("click ", "tap "))
            if (clickWhat != null) {
                if (acc.clickByText(clickWhat)) { setMood(R.drawable.char_happy, "Click kar diya"); return }
                else { setMood(R.drawable.char_sad, "\"$clickWhat\" nahi mila screen pe"); return }
            }
            if (n == "back" || n.contains("goback") || n.contains("peeche")) { acc.back(); setMood(R.drawable.char_happy, "Peeche"); return }
            if (n == "home" || n.contains("gohome") || n.contains("ghar")) { acc.home(); setMood(R.drawable.char_happy, "Home"); return }
            if (n.contains("recent")) { acc.recents(); setMood(R.drawable.char_happy, "Recent apps"); return }
            if (n.contains("notification")) { acc.notifications(); setMood(R.drawable.char_happy, "Notifications"); return }
        }

        // 4) Warna AI se poocho
        askAI(text)
    }

    private fun looksLikeAction(t: String): Boolean {
        return t.startsWith("type ") || t.contains(" type ") || t.contains("paste") ||
            t.contains("click ") || t.contains("chipka") || t.contains("likho")
    }

    private fun after(t: String, keys: List<String>): String? {
        for (k in keys) {
            val i = t.indexOf(k)
            if (i >= 0) {
                val v = t.substring(i + k.length).trim()
                if (v.isNotBlank()) return v
            }
        }
        return null
    }

    private fun tryOpen(t: String, n: String): String? {
        val known = listOf(
            Triple("youtube", "com.google.android.youtube", "YouTube"),
            Triple("whatsapp", "com.whatsapp", "WhatsApp"),
            Triple("instagram", "com.instagram.android", "Instagram"),
            Triple("gmail", "com.google.android.gm", "Gmail"),
            Triple("chrome", "com.android.chrome", "Chrome"),
            Triple("spotify", "com.spotify.music", "Spotify"),
            Triple("maps", "com.google.android.apps.maps", "Maps"),
            Triple("settings", "com.android.settings", "Settings")
        )
        for ((key, pkg, name) in known) {
            if (n.contains(key)) return launchPkg(pkg, name)
        }
        val m = Regex("(?:open|kholo|khol|launch|start|chalu)\\s+([a-z0-9 ]+)").find(t)
        if (m != null) {
            val word = m.groupValues[1]
                .replace(Regex("\\b(karo|kar|do|de|please|app|application)\\b"), "")
                .trim()
            if (word.isNotBlank()) {
                val found = findInstalled(word)
                if (found != null) return launchPkg(found.first, found.second)
            }
        }
        return null
    }

    private fun findInstalled(q: String): Pair<String, String>? {
        val pm = packageManager
        val target = q.replace(" ", "").lowercase()
        for (a in pm.getInstalledApplications(0)) {
            val label = pm.getApplicationLabel(a).toString()
            if (label.replace(" ", "").lowercase().contains(target)) {
                if (pm.getLaunchIntentForPackage(a.packageName) != null) {
                    return Pair(a.packageName, label)
                }
            }
        }
        return null
    }

    private fun launchPkg(pkg: String, name: String): String? {
        val i = packageManager.getLaunchIntentForPackage(pkg)
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { startActivity(i); return name } catch (e: Exception) {}
        }
        val web = Intent(
            Intent.ACTION_VIEW,
            android.net.Uri.parse("https://www.google.com/search?q=" + android.net.Uri.encode(name))
        )
        web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(web); return "$name (web)" } catch (e: Exception) {}
        return null
    }

    private fun askAI(prompt: String) {
        val key = getSharedPreferences("mitra", MODE_PRIVATE)
            .getString("gemini_key", "")?.trim() ?: ""
        if (key.isBlank()) {
            setMood(R.drawable.char_think, "\"$prompt\" - AI ke liye app me Gemini key daalo")
            return
        }
        setMood(R.drawable.char_think, "Soch raha hoon...")
        val full = "You are $NAME, a cute helpful assistant for a school student in India. " +
            "Reply in 1-2 short simple sentences. If the user writes Hindi/Hinglish, reply the same way. " +
            "Question: $prompt"
        Thread {
            val (ok, text) = Gemini.ask(key, full)
            main.post {
                if (ok) setMood(R.drawable.char_happy, text.take(240))
                else setMood(R.drawable.char_sad, "AI error: " + text.take(170))
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { recognizer?.destroy() } catch (e: Exception) {}
        try { wm.removeView(root) } catch (e: Exception) {}
    }
}
