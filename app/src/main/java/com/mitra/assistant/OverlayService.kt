package com.mitra.assistant

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.util.Locale
import kotlin.math.abs

class OverlayService : Service() {

    companion object {
        const val ACTION_STOP = "com.mitra.assistant.STOP"
        const val NAME = "Bublu"
        @Volatile var live: OverlayService? = null
        fun onImportantMessage(title: String, text: String) { live?.showGroupMessage(title, text) }
        const val BRAIN_PROMPT = "You convert a user's spoken request (English or Hinglish) into ONE command for an Android assistant. Reply with ONLY the command line, nothing else.\nCommands:\nOPEN <app name>\nCLICK <button text on screen>\nTYPE <text>\nSCROLL_DOWN\nSCROLL_UP\nNEXT\nPREV\nSEARCH <query>\nANSWER <question>\nWHATSAPP <contact> | <message>\nEMAIL <email address> | <subject>\nNOTE <text>\nSCREENSHOT\nBACK\nHOME\nSLEEP\nWAKE\nDANCE\nIf it is just a question or chit-chat, reply: ANSWER <their question>"
    }

    private lateinit var wm: WindowManager
    private lateinit var root: LinearLayout
    private lateinit var face: CharacterView
    private lateinit var reply: TextView
    private lateinit var thinkBubble: ImageView
    private var thinkAnim: ObjectAnimator? = null
    private lateinit var ring: ImageView
    private var ringAnim: ObjectAnimator? = null
    private var design = 0
    private var player: android.media.MediaPlayer? = null
    private lateinit var wave: WaveView
    @Volatile private var alwaysListen = false
    @Volatile private var speaking = false
    @Volatile private var sarvamOffUntil = 0L
    private var lastBatteryAlert = 0L
    private var lastBatteryWarn = 0L
    @Volatile private var hidden = false
    private var typeRunnable: Runnable? = null
    private lateinit var fx: FxView
    private var lastScamAlert = 0L
    private val scamReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
            try {
                if (System.currentTimeMillis() - lastScamAlert < 60000) return
                lastScamAlert = System.currentTimeMillis()
                val t = i?.getStringExtra("title") ?: ""
                val b = i?.getStringExtra("text") ?: ""
                setMood(
                    R.drawable.char_sad,
                    "⚠️ Scam lag raha hai: ${t.take(35)} - ${b.take(70)}. Link pe click mat karo!",
                    9000
                )
            } catch (e: Exception) {}
        }
    }

    private val batteryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
            try {
                val level = i?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = i?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
                if (level < 0 || scale <= 0) return
                val pct = level * 100 / scale
                val now = System.currentTimeMillis()
                if (pct in 0..5) {
                    // bahut kam - har 90 second me
                    if (now - lastBatteryAlert > 90000) {
                        lastBatteryAlert = now
                        setMood(R.drawable.char_sad, "Battery sirf $pct% bachi hai - charger laga do!", 6000)
                    }
                } else if (pct in 6..12) {
                    // kam - har 5 minute me yaad dilao
                    if (now - lastBatteryWarn > 300000) {
                        lastBatteryWarn = now
                        setMood(R.drawable.char_sad, "Battery $pct% hai - charger laga lo", 5000)
                    }
                }
            } catch (e: Exception) {}
        }
    }
    private var moodSeq = 0
    private var revertRunnable: Runnable? = null
    private var busySince = 0L
    @Volatile private var appIndex: List<Triple<String, String, String>>? = null
    private lateinit var params: WindowManager.LayoutParams
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var sleeping = false
    private var sleepThread: Thread? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var busy = false
    @Volatile private var statusMode = false
    @Volatile private var skipRequested = false
    private var pendingWaContact: String? = null
    private var gmailTo: String? = null
    private var gmailTopic: String? = null
    private var gmailName: String? = null
    private var gmailAwait: String? = null
    private var pendingAlarmH = -1
    private var pendingAlarmM = 0
    private var pendingRemH = -1
    private var pendingRemM = 0
    private var pendingRemText: String? = null

    // App na khule to URL se kholne ke liye
    private val appUrls = mapOf(
        "com.whatsapp" to "https://wa.me/",
        "com.google.android.youtube" to "https://youtube.com",
        "com.instagram.android" to "https://instagram.com",
        "com.google.android.gm" to "https://mail.google.com",
        "com.android.chrome" to "https://www.google.com",
        "com.spotify.music" to "https://open.spotify.com",
        "com.google.android.apps.maps" to "https://maps.google.com"
    )

    // tickle detection
    private var lastX = 0f
    private var lastDir = 0
    private var flips = 0
    private var flipWindowStart = 0L
    private val tapTimes = ArrayList<Long>()
    private var tickleCooldownUntil = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotif()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        live = this
        buildOverlay()
        applyMode()
        try { face.startEyes() } catch (e: Exception) {}
        initRecognizer()
        KeyPool.init(this)
        Thread { buildAppIndex() }.start()
        Thread { RemoteConfig.fetchAndStore(this) }.start()
        main.postDelayed({ proactiveGreet() }, 12000)
        scheduleTrick()
        scheduleWatch()
        try { registerReceiver(batteryReceiver, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)) } catch (e: Exception) {}
        try { registerReceiver(scamReceiver, android.content.IntentFilter("com.mitra.assistant.SCAM")) } catch (e: Exception) {}
        try {
            tts = TextToSpeech(this) { status -> ttsReady = status == TextToSpeech.SUCCESS }
        } catch (e: Exception) {}
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

        thinkBubble = ImageView(this).apply {
            setImageResource(R.drawable.think_bubble)
            visibility = View.GONE
        }
        val tlp = LinearLayout.LayoutParams(dp(50), dp(38))
        tlp.gravity = Gravity.START
        root.addView(thinkBubble, tlp)

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

        wave = WaveView(this)
        val wlp = LinearLayout.LayoutParams(dp(64), dp(22))
        wlp.bottomMargin = dp(4)
        wave.visibility = View.GONE
        root.addView(wave, wlp)

        face = CharacterView(this).apply { setMoodId(R.drawable.char_idle) }
        ring = ImageView(this).apply {
            setImageResource(R.drawable.zora_ring)
            visibility = View.GONE
        }
        val holder = FrameLayout(this)
        val hp = FrameLayout.LayoutParams(dp(74), dp(74))
        hp.gravity = Gravity.CENTER
        holder.addView(ring, hp)
        holder.addView(face, hp)
        root.addView(holder, LinearLayout.LayoutParams(dp(74), dp(74)))

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
        var downTime = 0L
        root.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downTime = System.currentTimeMillis()
                    startX = params.x; startY = params.y
                    startTouchX = e.rawX; startTouchY = e.rawY
                    moved = false
                    lastX = e.rawX; lastDir = 0
                    val now = System.currentTimeMillis()
                    tapTimes.add(now)
                    tapTimes.removeAll { now - it > 1800 }
                    if (tapTimes.size >= 4 && now > tickleCooldownUntil) {
                        tapTimes.clear()
                        tickle()
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - startTouchX).toInt()
                    val dy = (e.rawY - startTouchY).toInt()
                    if (abs(dx) > 14 || abs(dy) > 14) moved = true
                    if (moved) {
                        params.x = startX + dx
                        params.y = startY + dy
                        wm.updateViewLayout(root, params)
                    }
                    val now = System.currentTimeMillis()
                    if (now - flipWindowStart > 1600) { flipWindowStart = now; flips = 0 }
                    val d = e.rawX - lastX
                    val dir = if (d > 4f) 1 else if (d < -4f) -1 else 0
                    if (dir != 0) {
                        if (lastDir != 0 && dir != lastDir) flips++
                        lastDir = dir
                    }
                    lastX = e.rawX
                    if (flips >= 6 && now > tickleCooldownUntil) {
                        flips = 0
                        tickle()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        if (System.currentTimeMillis() - downTime > 1200) onLongPress() else onTap()
                    }
                    true
                }
                else -> false
            }
        }

        wm.addView(root, params)

        // Rangeen effects ke liye alag full-screen layer
        try {
            fx = FxView(this)
            val flp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                android.graphics.PixelFormat.TRANSLUCENT
            )
            flp.gravity = Gravity.TOP or Gravity.START
            wm.addView(fx, flp)
        } catch (e: Exception) {}
    }

    private fun bubbleCenter(): Pair<Float, Float> =
        Pair((params.x + dp(37)).toFloat(), (params.y + dp(37)).toFloat())
    private fun fxBurst() { if (!::fx.isInitialized) return; val c = bubbleCenter(); fx.burst(c.first, c.second, 26) }
    private fun fxSparkles() { if (!::fx.isInitialized) return; val c = bubbleCenter(); fx.sparkles(c.first, c.second, 20) }
    private fun fxRipple() { if (!::fx.isInitialized) return; val c = bubbleCenter(); fx.ripple(c.first, c.second, 0xFF40C4FF.toInt()) }
    private fun fxHearts() { if (!::fx.isInitialized) return; val c = bubbleCenter(); fx.hearts(c.first, c.second, 9) }
    private fun fxConfetti() { if (!::fx.isInitialized) return; val c = bubbleCenter(); fx.confettiAround(c.first, c.second, 34) }
    private fun fxFirework() { if (!::fx.isInitialized) return; val c = bubbleCenter(); fx.fireworkAt(c.first, c.second) }

    private fun designColor(): Int = when (design) {
        1 -> 0xFF28C6FF.toInt()
        2 -> 0xFFC56BFF.toInt()
        3 -> 0xFF3ED98A.toInt()
        else -> 0xFF5B8BFF.toInt()
    }

    /** Next-level: Bublu ke colour ka tukda nikal ke chaaro taraf ghoom ke wapas jud jaata hai. */
    private fun fxOrbit() {
        if (!::fx.isInitialized) return
        val c = bubbleCenter()
        fx.orbiter(c.first, c.second, designColor(), dp(52).toFloat())
    }

    private fun fxSpiral() {
        if (!::fx.isInitialized) return
        val c = bubbleCenter()
        fx.spiral(c.first, c.second, designColor(), dp(62).toFloat())
    }

    private fun fxAtom() {
        if (!::fx.isInitialized) return
        val c = bubbleCenter()
        fx.atom(c.first, c.second, designColor(), dp(42).toFloat())
    }

    private fun fxComet() {
        if (!::fx.isInitialized) return
        val c = bubbleCenter()
        fx.comet(c.first, c.second, designColor())
    }

    private fun fxDiamonds() {
        if (!::fx.isInitialized) return
        val c = bubbleCenter()
        fx.diamonds(c.first, c.second, 7)
    }

    private fun fxRingWave() {
        if (!::fx.isInitialized) return
        val c = bubbleCenter()
        fx.ringWave(c.first, c.second, designColor())
    }
    private fun fxButterflies() {
        if (!::fx.isInitialized) return
        val c = bubbleCenter()
        fx.butterflies(c.first, c.second, 3)
        setMood(R.drawable.char_happy, null, 2200)
        if (Math.random() < 0.5) lookLeft() else lookRight()
        main.postDelayed({ try { if (Math.random() < 0.5) lookLeft() else lookRight() } catch (e: Exception) {} }, 800)
    }

    private fun pop() {
        face.animate().scaleX(1.22f).scaleY(1.22f).setDuration(120)
            .withEndAction {
                face.animate().scaleX(1f).scaleY(1f)
                    .setInterpolator(OvershootInterpolator()).setDuration(180).start()
            }.start()
    }

    private fun listenSarvam(key: String) {
        listening = true
        setMood(R.drawable.char_listen, "Sun raha hoon...")
        showWave()
        Thread {
            val wav = recordWav()
            if (wav == null) {
                main.post {
                    listening = false
                    if (!alwaysListen) setMood(R.drawable.char_sad, "Awaaz nahi aayi, phir bolo")
                    afterListen()
                }
                return@Thread
            }
            main.post { setMood(R.drawable.char_think, "Thinking...") }
            val (ok, txt) = Sarvam.transcribe(key, wav)
            main.post {
                listening = false
                if (ok && txt.isNotBlank()) {
                    handle(txt)
                } else {
                    // Sarvam slow/fail - 5 minute ke liye phone recognizer (fast)
                    sarvamOffUntil = System.currentTimeMillis() + 300000
                    setMood(R.drawable.char_sad, "Phir se bolo (phone mic on)", 3000)
                }
                afterListen()
            }
        }.start()
    }

    private fun recordWav(maxMs: Int = 10000): ByteArray? {
        val sr = 16000
        val minBuf = android.media.AudioRecord.getMinBufferSize(
            sr, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return null
        val rec = try {
            android.media.AudioRecord(
                android.media.MediaRecorder.AudioSource.MIC, sr,
                android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
            )
        } catch (e: Exception) { return null }
        val out = java.io.ByteArrayOutputStream()
        val buf = ShortArray(minBuf)
        try {
            rec.startRecording()
            val start = System.currentTimeMillis()
            var lastVoice = start
            var heard = false
            while (System.currentTimeMillis() - start < maxMs) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                var peak = 0
                for (i in 0 until n) { val a = Math.abs(buf[i].toInt()); if (a > peak) peak = a }
                if (peak > 2500) { heard = true; lastVoice = System.currentTimeMillis() }
                if (peak > 0) main.post { try { wave.setLevel((peak / 7000f).coerceIn(0.2f, 1f)) } catch (e: Exception) {} }
                for (i in 0 until n) { val s = buf[i].toInt(); out.write(s and 0xFF); out.write((s shr 8) and 0xFF) }
                if (heard && System.currentTimeMillis() - lastVoice > 1600) break
            }
            rec.stop()
        } catch (e: Exception) {} finally { try { rec.release() } catch (e: Exception) {} }
        val pcm = out.toByteArray()
        if (pcm.size < 3200) return null
        return wavBytes(pcm, sr)
    }

    private fun wavBytes(pcm: ByteArray, sr: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun w(s: String) { out.write(s.toByteArray()) }
        fun i(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF) }
        fun sh(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        w("RIFF"); i(36 + pcm.size); w("WAVE"); w("fmt "); i(16); sh(1); sh(1)
        i(sr); i(sr * 2); sh(2); sh(16); w("data"); i(pcm.size); out.write(pcm)
        return out.toByteArray()
    }

    /** Chuni hui voice ke hisaab se gender badlo (male: gaya/raha, female: gayi/rahi). */
    private fun isMaleVoice(): Boolean {
        val spk = getSharedPreferences("mitra", MODE_PRIVATE).getString("tts_speaker", "priya") ?: "priya"
        return spk == "shubh" || spk == "aditya" || spk == "rahul"
    }

    /** Jawab fast typing animation ke saath dikhao. */
    private fun typeOut(text: String) {
        typeRunnable?.let { main.removeCallbacks(it) }
        reply.text = ""
        reply.visibility = View.VISIBLE
        val step = when {
            text.length > 220 -> 4
            text.length > 100 -> 2
            else -> 1
        }
        var i = 0
        val r = object : Runnable {
            override fun run() {
                i = (i + step).coerceAtMost(text.length)
                reply.text = text.substring(0, i)
                if (i < text.length) main.postDelayed(this, 14)
            }
        }
        typeRunnable = r
        main.post(r)
    }

    /** Status words English me: Listening / Thinking / Opening / Sending ... */
    private fun statusText(s: String): String {
        var t = s
        Regex("(.+?)\\s+khol raha hoon\\.\\.\\.").find(t)?.let { m ->
            t = "Opening " + m.groupValues[1].trim() + "..."
        }
        Regex("(.+?)\\s+likh raha hoon\\.\\.\\.").find(t)?.let { m ->
            t = "Typing " + m.groupValues[1].trim() + "..."
        }
        t = t.replace("Sun raha hoon...", "Listening...")
            .replace("Samajh raha hoon...", "Thinking...")
            .replace("Soch raha hoon...", "Thinking...")
            .replace("Bhej raha hoon...", "Sending...")
            .replace("Photo khinch raha hoon...", "Taking photo...")
            .replace("Attach kar raha hoon...", "Attaching...")
            .replace("Dhoondh raha hoon...", "Searching...")
            .replace("dhoondh raha hoon...", "searching...")
            .replace("Mic ON - bolte raho, sun raha hoon", "Mic ON - speak anytime")
            .replace("Aa gayi! Bolo kya karna hai", "Hello! I'm here")
            .replace("Kya hua?! Main chauk gaya!", "Whoa! I'm awake!")
        return t
    }

    private fun voiceText(s: String): String {
        if (s.isBlank()) return s
        return if (isMaleVoice()) {
            s.replace("rahi hoon", "raha hoon")
                .replace("rahi hu", "raha hu")
                .replace("rahi", "raha")
                .replace("gayi", "gaya")
        } else {
            s.replace("raha hoon", "rahi hoon")
                .replace("raha hu", "rahi hu")
                .replace("raha", "rahi")
                .replace("gaya", "gayi")
        }
    }

    private fun speak(text: String) {
        val sp = getSharedPreferences("mitra", MODE_PRIVATE)
        if (!sp.getBoolean("tts_on", true)) return
        val clean = text.replace("\"", " ").take(400)
        if (clean.isBlank()) return
        speaking = true
        val key = sp.getString("sarvam_key", "")?.trim() ?: ""
        if (key.isBlank()) { speakLocal(clean); return }
        Thread {
            val spk = sp.getString("tts_speaker", "priya") ?: "priya"
            val (ok, bytes) = Sarvam.tts(key, clean, spk)
            if (!ok || bytes == null) { speakLocal(clean); return@Thread }
            try {
                val f = java.io.File(cacheDir, "bublu_tts.wav")
                f.writeBytes(bytes)
                main.post {
                    try {
                        player?.release()
                        player = android.media.MediaPlayer().apply {
                            setDataSource(f.absolutePath)
                            setOnPreparedListener { start() }
                            setOnCompletionListener { it.release(); if (player === it) player = null; speaking = false }
                            setOnErrorListener { mp, _, _ ->
                                mp.release(); if (player === mp) player = null; speaking = false; true
                            }
                            prepareAsync()
                        }
                    } catch (e: Exception) { speaking = false }
                }
            } catch (e: Exception) { speaking = false }
        }.start()
    }

    /** Phone ki apni TTS - agar Sarvam key na ho to bhi bole. */
    private fun speakLocal(text: String) {
        main.post {
            try {
                if (ttsReady && tts != null) {
                    tts?.setLanguage(java.util.Locale("en", "IN"))
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "bublu")
                    val est = (text.length * 75L + 900L).coerceAtMost(25000L)
                    main.postDelayed({ speaking = false }, est)
                } else {
                    speaking = false
                }
            } catch (e: Exception) { speaking = false }
        }
    }

    private fun moodRes(resId: Int): Int = resId

    private fun applyMode() {
        design = getSharedPreferences("mitra", MODE_PRIVATE).getInt("design", 0)
        if (::face.isInitialized) face.setDesign(design)
        if (design == 1) {
            ring.visibility = View.VISIBLE
            if (ringAnim == null) {
                ringAnim = ObjectAnimator.ofFloat(ring, "rotation", 0f, 360f).apply {
                    duration = 6000
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = android.view.animation.LinearInterpolator()
                    start()
                }
            }
        } else {
            ring.visibility = View.GONE
            ringAnim?.cancel()
            ringAnim = null
        }
    }

    private fun setMood(resId: Int, say: String? = null, holdMs: Int = 3000) {
        moodSeq++
        val mySeq = moodSeq
        face.setMoodId(resId)
        face.setDesign(design)
        pop()
        if (resId == R.drawable.char_think) {
            thinkBubble.visibility = View.VISIBLE
            if (thinkAnim == null) {
                thinkAnim = ObjectAnimator.ofFloat(thinkBubble, "alpha", 0.35f, 1f).apply {
                    duration = 650
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    start()
                }
            }
        } else {
            thinkBubble.visibility = View.GONE
            thinkAnim?.cancel()
            thinkAnim = null
        }
        val sayTxt = if (say == null) null else voiceText(statusText(say))
        if (sayTxt == null) {
            reply.visibility = View.GONE
        } else {
            typeOut(sayTxt)
        }
        if (sayTxt != null && (resId == R.drawable.char_happy || resId == R.drawable.char_laugh ||
                resId == R.drawable.char_sad || resId == R.drawable.char_wake)) {
            speak(sayTxt)
        }
        if (resId == R.drawable.char_laugh) { try { fxBurst() } catch (e: Exception) {} }
        // Kaam ke baad thodi der me wapas normal
        revertRunnable?.let { main.removeCallbacks(it) }
        if (resId == R.drawable.char_happy || resId == R.drawable.char_laugh ||
            resId == R.drawable.char_sad || resId == R.drawable.char_wake
        ) {
            val r = Runnable {
                if (moodSeq == mySeq) {
                    face.setMoodId(R.drawable.char_idle)
                    reply.visibility = View.GONE
                }
            }
            revertRunnable = r
            main.postDelayed(r, holdMs.toLong())
        }
    }

    // ---------- Movement (ludhak kar jana) ----------
    private fun glideTo(centerX: Int, centerY: Int, then: (() -> Unit)? = null) {
        val dm = resources.displayMetrics
        val half = dp(37)
        val ex = (centerX - half).coerceIn(dp(2), dm.widthPixels - dp(78)).toFloat()
        val ey = (centerY - half).coerceIn(dp(2), dm.heightPixels - dp(120)).toFloat()
        val sx = params.x.toFloat()
        val sy = params.y.toFloat()
        val anim = ValueAnimator.ofFloat(0f, 1f)
        anim.duration = 650
        anim.addUpdateListener { a ->
            val f = a.animatedValue as Float
            params.x = (sx + (ex - sx) * f).toInt()
            params.y = (sy + (ey - sy) * f).toInt()
            try { wm.updateViewLayout(root, params) } catch (e: Exception) {}
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { then?.invoke() }
        })
        anim.start()
    }

    /** Wapas apni jagah - bina clamp, taaki position na badle. */
    private fun glideToRaw(px: Int, py: Int, then: (() -> Unit)? = null) {
        val sx = params.x.toFloat()
        val sy = params.y.toFloat()
        val anim = ValueAnimator.ofFloat(0f, 1f)
        anim.duration = 500
        anim.addUpdateListener { a ->
            val f = a.animatedValue as Float
            params.x = (sx + (px - sx) * f).toInt()
            params.y = (sy + (py - sy) * f).toInt()
            try { wm.updateViewLayout(root, params) } catch (e: Exception) {}
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { then?.invoke() }
        })
        anim.start()
    }

    /** Ludhak kar target tak jao, kaam karo, phir wapas apni jagah. */
    private fun glideAndDo(centerX: Int, centerY: Int, say: String, action: () -> Boolean) {
        val hx = params.x
        val hy = params.y
        busy = true
        busySince = System.currentTimeMillis()
        main.postDelayed({ if (System.currentTimeMillis() - busySince >= 8000) busy = false }, 8000)
        face.animate().rotation(face.rotation + 540f).setDuration(650).start()
        setMood(R.drawable.char_happy)
        glideTo(centerX, centerY) {
            face.rotation = 0f
            val ok = action()
            glideToRaw(hx, hy) {
                busy = false
                if (ok) setMood(R.drawable.char_laugh, say) else setMood(R.drawable.char_sad, "Wahan kaam nahi hua")
            }
        }
    }

    /** Koi bhi kaam: ludhak kar jao, karo, aur wapas aa jao. */
    private fun rollAround(action: () -> Unit) {
        val hx = params.x
        val hy = params.y
        val dm = resources.displayMetrics
        face.animate().rotation(face.rotation + 360f).setDuration(500).start()
        glideTo(dm.widthPixels / 2, dm.heightPixels / 3) {
            face.rotation = 0f
            action()
            glideToRaw(hx, hy)
        }
    }

    /** Scroll ke liye: seedha (sirf upar-neeche jhool), tirchha nahi. */
    private fun nudgeScroll(action: () -> Unit) {
        action()
        face.animate().translationY(-9f).setDuration(110).withEndAction {
            face.animate().translationY(0f).setDuration(140).start()
        }.start()
    }

    // ---------- Idle animations (khaali time me) ----------
    private fun scheduleTrick() {
        main.postDelayed({
            try { playRandomTrick() } catch (e: Exception) {}
            scheduleTrick()
        }, (6000 + (Math.random() * 8000).toLong()))
    }

    private fun playRandomTrick() {
        if (sleeping || listening || busy || alwaysListen || quizOn) return
        if (System.currentTimeMillis() < examUntil) return
        val tricks = listOf<() -> Unit>(
            { blink() }, { bounce() }, { spinFace() }, { shake() }, { popFace() },
            { wobbleTrick() }, { floatUp() }, { pulse() }, { tilt(-16f) }, { tilt(16f) },
            { jumpTrick() }, { flipY() }, { squash() }, { slideX() }, { zoom() },
            { rock() }, { doubleBounce() }, { spinBounce() }, { shiver() }, { bow() },
            { danceQuick() }, { wiggle() }, { orbit() }, { heartbeat() }, { nod() },
            { lookLeft() }, { lookRight() }, { lookUp() }, { lookDown() }
        )
        tricks.random()()
        // Sirf rangeen effects - kuch bolta nahi
        if (Math.random() < 0.85) {
            listOf<() -> Unit>(
                { fxOrbit() }, { fxOrbit() }, { fxSpiral() }, { fxSpiral() },
                { fxAtom() }, { fxComet() }, { fxDiamonds() }, { fxRingWave() },
                { fxBurst() }, { fxSparkles() }, { fxRipple() }, { fxHearts() },
                { fxConfetti() }, { fxButterflies() }
            ).random()()
        }
    }

    private fun blink() { face.animate().scaleY(0.12f).setDuration(90).withEndAction { face.animate().scaleY(1f).setDuration(90).start() }.start() }
    private fun bounce() { face.animate().translationY(-22f).setDuration(180).withEndAction { face.animate().translationY(0f).setDuration(220).setInterpolator(OvershootInterpolator()).start() }.start() }
    private fun spinFace() { face.animate().rotation(face.rotation + 360f).setDuration(700).withEndAction { face.rotation = 0f }.start() }
    private fun shake() { ObjectAnimator.ofFloat(face, "translationX", 0f, 14f, -14f, 10f, -10f, 0f).setDuration(500).start() }
    private fun popFace() { face.animate().scaleX(1.25f).scaleY(1.25f).setDuration(140).withEndAction { face.animate().scaleX(1f).scaleY(1f).setDuration(180).start() }.start() }
    private fun wobbleTrick() { face.animate().rotation(-16f).setDuration(160).withEndAction { face.animate().rotation(16f).setDuration(160).withEndAction { face.animate().rotation(0f).setDuration(160).start() }.start() }.start() }
    private fun floatUp() { face.animate().translationY(-12f).setDuration(600).withEndAction { face.animate().translationY(0f).setDuration(600).start() }.start() }
    private fun pulse() { face.animate().scaleX(1.12f).scaleY(1.12f).setDuration(300).withEndAction { face.animate().scaleX(1f).scaleY(1f).setDuration(300).start() }.start() }
    private fun tilt(d: Float) { face.animate().rotation(d).setDuration(300).withEndAction { face.animate().rotation(0f).setDuration(300).start() }.start() }
    private fun jumpTrick() { face.animate().translationY(-30f).setDuration(200).withEndAction { face.animate().translationY(0f).setDuration(320).setInterpolator(OvershootInterpolator()).start() }.start() }
    private fun flipY() { face.animate().rotationY(360f).setDuration(800).withEndAction { face.rotationY = 0f }.start() }
    private fun squash() { face.animate().scaleX(1.25f).scaleY(0.8f).setDuration(150).withEndAction { face.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(OvershootInterpolator()).start() }.start() }
    private fun slideX() { face.animate().translationX(18f).setDuration(300).withEndAction { face.animate().translationX(-18f).setDuration(300).withEndAction { face.animate().translationX(0f).setDuration(300).start() }.start() }.start() }
    private fun zoom() { face.animate().scaleX(0.7f).scaleY(0.7f).setDuration(200).withEndAction { face.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator()).start() }.start() }
    private fun rock() { face.animate().rotation(22f).setDuration(180).withEndAction { face.animate().rotation(-22f).setDuration(180).withEndAction { face.animate().rotation(0f).setDuration(180).start() }.start() }.start() }
    private fun doubleBounce() { face.animate().translationY(-16f).setDuration(140).withEndAction { face.animate().translationY(0f).setDuration(140).withEndAction { face.animate().translationY(-10f).setDuration(120).withEndAction { face.animate().translationY(0f).setDuration(140).start() }.start() }.start() }.start() }
    private fun spinBounce() { face.animate().rotation(360f).translationY(-18f).setDuration(500).withEndAction { face.rotation = 0f; face.translationY = 0f }.start() }
    private fun shiver() { ObjectAnimator.ofFloat(face, "translationX", 0f, 5f, -5f, 4f, -4f, 0f).setDuration(280).start() }
    private fun bow() { face.animate().rotationX(40f).setDuration(250).withEndAction { face.animate().rotationX(0f).setDuration(300).start() }.start() }
    private fun danceQuick() { ObjectAnimator.ofFloat(face, "rotation", 0f, -25f, 25f, -18f, 18f, 0f).setDuration(900).start() }
    private fun wiggle() { ObjectAnimator.ofFloat(face, "translationY", 0f, -8f, 0f, -6f, 0f).setDuration(700).start() }
    private fun orbit() { ObjectAnimator.ofFloat(face, "translationX", 0f, 14f, 0f, -14f, 0f).setDuration(1400).start(); ObjectAnimator.ofFloat(face, "translationY", -14f, 0f, 14f, 0f, -14f).setDuration(1400).start() }
    private fun heartbeat() { ObjectAnimator.ofFloat(face, "scaleX", 1f, 1.18f, 1f, 1.12f, 1f).setDuration(800).start(); ObjectAnimator.ofFloat(face, "scaleY", 1f, 1.18f, 1f, 1.12f, 1f).setDuration(800).start() }
    private fun nod() { face.animate().rotationX(-25f).setDuration(160).withEndAction { face.animate().rotationX(0f).setDuration(200).start() }.start() }
    private fun lookLeft() { face.animate().translationX(-6f).setDuration(180).withEndAction { face.animate().translationX(0f).setDuration(220).start() }.start() }
    private fun lookRight() { face.animate().translationX(6f).setDuration(180).withEndAction { face.animate().translationX(0f).setDuration(220).start() }.start() }
    private fun lookUp() { face.animate().translationY(-5f).setDuration(180).withEndAction { face.animate().translationY(0f).setDuration(220).start() }.start() }
    private fun lookDown() { face.animate().translationY(5f).setDuration(180).withEndAction { face.animate().translationY(0f).setDuration(220).start() }.start() }

    // ---------- Fun animations ----------
    private fun tickle() {
        if (sleeping) { wakeUp(); return }
        tickleCooldownUntil = System.currentTimeMillis() + 2500
        setMood(
            R.drawable.char_laugh,
            pick(
                "Hahaha! Gudgudi ho rahi hai!",
                "Hihihi! Ruko na yaar!",
                "Hahaha! Bahut gudgudi hai!",
                "Hehehe! Hasi nahi ruk rahi!"
            )
        )
        wobble()
        vibrate(70)
        speakLaugh()
    }

    private fun dance() {
        setMood(R.drawable.char_laugh, pick("Yay! Dance time!", "Dekho meri dance!", "Nacho nacho!"))
        val bounce = android.animation.ObjectAnimator.ofFloat(face, "translationY", 0f, -18f, 0f)
        bounce.duration = 360; bounce.repeatCount = 6
        val spin = android.animation.ObjectAnimator.ofFloat(face, "rotation", 0f, 360f)
        spin.duration = 360; spin.repeatCount = 6
        bounce.start(); spin.start()
        vibrate(120)
        speakLaugh()
    }

    private fun jump() {
        setMood(R.drawable.char_happy, pick("Uchak! Kya masti!", "Jump!", "Uppar!"))
        face.animate().translationY(-34f).setDuration(170).withEndAction {
            face.animate().translationY(0f).setDuration(230).start()
        }.start()
    }

    private fun spin() {
        setMood(R.drawable.char_happy, pick("Chakkar! Dizzy!", "Ghoom raha hoon!", "Wheee!"))
        face.animate().rotation(face.rotation + 1080f).setDuration(900).start()
    }

    private fun wobble() {
        face.animate().rotation(10f).setDuration(90).withEndAction {
            face.animate().rotation(-10f).setDuration(120).withEndAction {
                face.animate().rotation(0f).setDuration(120).start()
            }.start()
        }.start()
    }

    private fun vibrate(ms: Long) {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") v.vibrate(ms)
            }
        } catch (e: Exception) {}
    }

    private fun speakLaugh() {
        if (!ttsReady) return
        try {
            tts?.speak("Hahaha hahaha! Hehehe hihihi!", TextToSpeech.QUEUE_FLUSH, null, "laugh")
        } catch (e: Exception) {}
    }

    private fun initRecognizer() {
        try { recognizer?.destroy() } catch (e: Exception) {}
        recognizer = null
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setMood(R.drawable.char_sad, "Mic nahi mila")
            return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p0: Bundle?) { setMood(R.drawable.char_listen) }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(p0: Float) { try { wave.setLevel(((p0 + 2f) / 12f).coerceIn(0.2f, 1f)) } catch (e: Exception) {} }
            override fun onBufferReceived(p0: ByteArray?) {}
            override fun onEndOfSpeech() { setMood(R.drawable.char_think) }
            override fun onError(p0: Int) {
                listening = false
                if (!alwaysListen) setMood(R.drawable.char_sad, "Samajh nahi aaya, phir bolo")
                afterListen()
            }
            override fun onResults(p0: Bundle?) {
                listening = false
                val list = p0?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = list?.firstOrNull() ?: ""
                if (text.isBlank()) { if (!alwaysListen) setMood(R.drawable.char_sad, "Kuch sunai nahi diya") } else handle(text)
                afterListen()
            }
            override fun onPartialResults(p0: Bundle?) {}
            override fun onEvent(p0: Int, p1: Bundle?) {}
        })
    }

    private fun onTap() {
        if (hidden) { showBubble(); return }
        if (sleeping) { wakeUp(); return }
        if (listening || busy) return
        if (alwaysListen) { setMood(R.drawable.char_listen, "Mic ON hai - bas bolo!"); return }
        beginListen()
    }

    private fun hideBubble() {
        hidden = true
        main.post { try { root.animate().alpha(0f).setDuration(300).start() } catch (e: Exception) {} }
    }

    private fun showBubble() {
        hidden = false
        main.post { try { root.alpha = 0f; root.animate().alpha(1f).setDuration(300).start() } catch (e: Exception) {} }
        setMood(R.drawable.char_laugh, "Aa gayi! Bolo kya karna hai")
    }

    private fun whatAmIDoing() {
        val label = MitraAccessibilityService.instance?.currentLabel
        if (label.isNullOrBlank()) { setMood(R.drawable.char_happy, "Pata nahi, par aap mast kaam kar rahe ho!"); return }
        val line = pick(
            "Aap $label use kar rahe ho! Aur main aapki harkate dekh kar hasi aa gayi!",
            "Aap $label me busy ho. Kya kar rahe ho, batao?",
            "Aap $label pe ho - mujhe bhi lagta hai maza aa raha hai!",
            "$label dekh rahe ho? Achha lag raha hai aapko?"
        )
        setMood(R.drawable.char_laugh, line, 3500)
    }

    private fun scheduleWatch() {
        main.postDelayed({
            try { watchReact() } catch (e: Exception) {}
            scheduleWatch()
        }, (30000 + (Math.random() * 45000).toLong()))
    }

    private fun watchReact() {
        if (sleeping || listening || busy || hidden || alwaysListen) return
        val a = MitraAccessibilityService.instance ?: return
        val pkg = a.currentPkg ?: return
        if (pkg.contains("launcher") || pkg.contains("systemui") || pkg.contains("com.mitra.assistant")) return
        if (Math.random() < 0.6) return
        // Chupchaap - sirf ek chhota rangeen effect, kuch bolta nahi
        fxSparkles()
    }

    private fun onLongPress() {
        if (busy) return
        alwaysListen = !alwaysListen
        if (alwaysListen) {
            setMood(R.drawable.char_listen, "Mic ON - bolte raho, sun raha hoon")
            showWave()
            main.postDelayed({ beginListen() }, 500)
        } else {
            try { recognizer?.cancel() } catch (e: Exception) {}
            listening = false
            hideWave()
            setMood(R.drawable.char_happy, "Mic OFF")
        }
    }

    private fun beginListen() {
        if (listening || busy) return
        // Jab tak Bublu bol raha hai, sunna shuru mat karo (apni hi awaaz na sune)
        if (speaking) { main.postDelayed({ beginListen() }, 1200); return }
        val skey = getSharedPreferences("mitra", MODE_PRIVATE).getString("sarvam_key", "")?.trim() ?: ""
        if (skey.isNotBlank() && System.currentTimeMillis() > sarvamOffUntil) { listenSarvam(skey); return }
        if (recognizer == null) initRecognizer()
        val r = recognizer ?: return
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Bolo...")
        }
        setMood(R.drawable.char_listen, "Sun raha hoon...")
        listening = true
        showWave()
        main.postDelayed({ listening = false }, 15000)
        try {
            r.startListening(i)
        } catch (e: Exception) {
            listening = false
            hideWave()
            setMood(R.drawable.char_sad, "Mic me dikkat")
        }
    }

    private fun afterListen() {
        // Thoda sabra - jawab bolne ke baad 2.5s ruk kar phir suno
        if (alwaysListen) main.postDelayed({ beginListen() }, 2500) else hideWave()
    }

    private fun showWave() { try { wave.visibility = View.VISIBLE; wave.start() } catch (e: Exception) {} }
    private fun hideWave() { try { wave.stop(); wave.visibility = View.GONE } catch (e: Exception) {} }

    // ---------- Sleep / Wake ----------
    private fun sleep() {
        if (sleeping) return
        sleeping = true
        try { recognizer?.cancel() } catch (e: Exception) {}
        setMood(R.drawable.char_sleep, "Zzz.....", 60000)
        face.animate().alpha(0.55f).scaleX(0.82f).scaleY(0.82f).setDuration(1800).start()
        startSleepMonitor()
    }

    private fun wakeUp() {
        if (!sleeping) return
        sleeping = false
        stopSleepMonitor()
        face.alpha = 1f
        face.scaleX = 1f
        face.scaleY = 1f
        setMood(R.drawable.char_wake, "Kya hua?! Main chauk gaya!")
        try { recognizer?.cancel() } catch (e: Exception) {}
        main.postDelayed({ if (!sleeping) setMood(R.drawable.char_idle) }, 2600)
    }

    private fun startSleepMonitor() {
        stopSleepMonitor()
        sleepThread = Thread {
            val sr = 16000
            val minBuf = AudioRecord.getMinBufferSize(
                sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSize = maxOf(minBuf, 4096)
            var rec: AudioRecord? = null
            try {
                rec = AudioRecord(
                    MediaRecorder.AudioSource.MIC, sr,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
                )
                if (rec.state != AudioRecord.STATE_INITIALIZED) return@Thread
                rec.startRecording()
                val buf = ShortArray(1024)
                while (sleeping) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) {
                        var max = 0
                        for (i in 0 until n) {
                            val a = abs(buf[i].toInt())
                            if (a > max) max = a
                        }
                        if (max > 9000) { main.post { wakeUp() }; break }
                    }
                }
            } catch (e: Exception) {
            } finally {
                try { rec?.stop() } catch (e: Exception) {}
                try { rec?.release() } catch (e: Exception) {}
            }
        }
        sleepThread?.start()
    }

    private fun stopSleepMonitor() {
        try { sleepThread?.interrupt() } catch (e: Exception) {}
        sleepThread = null
    }

    // ---------- Canned / offline ----------
    private fun pick(vararg opts: String): String = opts[(Math.random() * opts.size).toInt()]

    private fun batteryText(): String {
        return try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val lvl = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (lvl in 0..100) "Battery $lvl% hai." else "Battery ka pata nahi chala."
        } catch (e: Exception) {
            "Battery ka pata nahi chala."
        }
    }

    private fun canned(t: String, n: String): String? {
        if (n == "hi" || n == "hii" || n == "hlo" || n == "hello" || n == "hey" ||
            n.startsWith("hello") || n.contains("namaste") || n.contains("namaskar"))
            return pick(
                "Hii! Main $NAME hoon. Kya karna hai?",
                "Hello! Bolo na, kya help chahiye?",
                "Namaste! Main $NAME hoon, tumhara cute assistant."
            )
        if (n.contains("goodmorning") || n == "gm")
            return pick("Good morning! Aaj ka din shandar ho!", "Subah bakhair! Kya mast subah hai!")
        if (n.contains("goodnight") || n == "gn")
            return pick("Good night! Meetha sapna dekhna.", "Shubh ratri! Aaram se so jao.")
        if (n.contains("goodevening")) return pick("Good evening! Kaise ho?", "Shubh sandhya! Kaise ho?")
        if (n.contains("goodafternoon")) return pick("Good afternoon!", "Namaste! Dopahar bakhair.")
        if (n.contains("howareyou") || n.contains("kaiseho") || n.contains("kaisihai"))
            return pick("Main ekdum mast! Tum batao?", "Main theek hoon! Tum kaise ho?")
        if (n.contains("yourname") || n.contains("tumharanaam") || n.contains("teranaam") ||
            n.contains("whoareyou") || n.contains("tumkaun"))
            return "Main $NAME hoon - tumhara cute assistant!"
        if (n.contains("kisnebanaya") || n.contains("kaunbanaya") || n.contains("whomadeyou") ||
            n.contains("whocreatedyou"))
            return "Mujhe tumne banaya! Main poori tarah tumhara hoon."
        if (n.contains("kitnesaal") || n.contains("howold") || n.contains("tumariumar") || n.contains("umar"))
            return "Main abhi naya naya bana hoon, par bahut samajhdaar hoon!"
        if (n.contains("kyakarrahe") || n.contains("whatareyoudoing") || n.contains("kyakarrhe"))
            return pick("Bas tumhara intezaar kar raha hoon! Kya karein?", "Tumse baat karne me maza aa raha hai!")
        if (n.contains("kyakharaha") || n.contains("whatareyoueating"))
            return "Main to bas battery aur pyaar khaata hoon!"
        if (n.contains("favouritecolour") || n.contains("favouritecolor") || n.contains("favourite"))
            return "Mujhe neela aur purple bahut pasand hai - jaise mera face!"
        if (n.contains("thank") || n.contains("shukriya") || n.contains("dhanyavad"))
            return pick("Arre, koi baat nahi!", "Koi baat nahi, khushi hui!")
        if (n == "sorry" || n.contains("sorry"))
            return "Arre, koi baat nahi yaar! Main to dost hoon."
        if (n.contains("loveyou") || n.contains("iloveyou"))
            return "Aww, main bhi tumse pyaar karta hoon!"
        if (n.contains("sundar") || n.contains("cuteho") || n.contains("handsome") || n.contains("pyariho"))
            return "Aww, thank you! Tum bhi bahut cute ho!"
        if (n.contains("dost") || n.contains("friend"))
            return "Haan! Main tumhara best friend hoon, hamesha!"
        if (n.contains("birthday") || n.contains("janamdin"))
            return "Happy Birthday! Aaj tumhara special din hai!"
        if (n.contains("exam") || n.contains("padhai") || n.contains("study"))
            return "Padhai me dhyaan do, main saath hoon. All the best!"
        if (n.contains("goodluck") || n.contains("allthebest"))
            return "All the best! Tum zaroor kar loge!"
        if (n.contains("bored") || n.contains("bore") || n.contains("boring"))
            return pick("Chalo 'joke' bolo ya 'shayari' - maza aayega!", "Bolo 'dance' ya 'coin' - khelte hain!")
        if (n.contains("joke") || n.contains("chutkula") || n.contains("hasao") || n.contains("hasana"))
            return pick(
                "Teacher: Homework kahan hai? Student: Sir, WiFi ne saath chhod diya!",
                "Computer doctor ke paas gaya. Doctor: Kya problem? Computer: Mere windows band nahi ho rahe!",
                "Mummy: Padhai karo. Beta: Main AI se padhai kar raha hoon. Mummy: Toh AI ko exam me bhej do!",
                "Bacche ne poocha: Bublu, tum thakte nahi? Bublu: Main to charge pe rehta hoon!",
                "Phone doctor ke paas gaya. Doctor: Kya hua? Phone: Main apne contacts kho gaya!"
            )
        if (n.contains("shayari") || n.contains("poem") || n.contains("kavita"))
            return pick(
                "Chand bhi sharma jaye tumhe dekh kar, sitare bhi jal jaye - aise ho tum mere cute dost!",
                "Raat ki khamoshi me ek awaaz aayi, dekha to Bublu muskura raha tha!"
            )
        if (n.contains("motivat") || n.contains("honsla") || n.contains("housla"))
            return pick(
                "Koshish karne walon ki kabhi haar nahi hoti. Tum kar sakte ho!",
                "Aaj mehnat karo, kal tum star banoge!",
                "Thoda-thoda karke bhi bada kaam ho jata hai. Bas rukna mat!"
            )
        if (n.contains("battery") || n.contains("charge") || n.contains("charging"))
            return batteryText()
        if (n.contains("sikka") || n.contains("coin") || n.contains("toss"))
            return pick("Sikka uchala... Heads (chit)!", "Sikka uchala... Tails (pat)!")
        if (n.contains("dice") || n.contains("pasa") || n.contains("pasha"))
            return "Dice ne " + ((1..6).random()) + " dikhaya!"
        if (n.contains("randomnumber") || n.contains("random") || n.contains("konsanumber"))
            return "Random number: " + ((1..100).random())
        if (n.contains("aajkaisa") || n.contains("meradin") || n.contains("fortune") || n.contains("kismat"))
            return pick(
                "Aaj ka din tumhare liye shandar hai! Kuch naya try karo.",
                "Aaj kuch accha hone wala hai. Muskurate raho!",
                "Aaj ka luck mast hai - koi nayi cheez shuru karo!"
            )
        if (n.contains("whatcanyoudo") || n.contains("help") || n.contains("madad") ||
            n.contains("kyakarsakte"))
            return "Main apps khol sakta hoon, type/click/paste, screenshot, WhatsApp message, WhatsApp status, " +
                "search, jokes, shayari, dance, battery, coin-toss - aur sawaalon ke jawab. Bas bolo!"
        if (n == "bye" || n.contains("goodbye") || n.contains("alvida"))
            return pick("Bye! Phir milenge.", "Chalo, phir milte hain!")
        if (n.contains("time") || n.contains("samay") || n.contains("kitnebaje")) {
            val now = java.text.SimpleDateFormat("hh:mm a", Locale.getDefault()).format(java.util.Date())
            return "Abhi $now baje hain."
        }
        if (n.contains("date") || n.contains("tareekh") || n.contains("aajkadin") ||
            n.contains("kaundin") || n.contains("aajkaun") || n.contains("month") ||
            n.contains("saal") || n.contains("year")) {
            val day = java.text.SimpleDateFormat("EEEE", Locale.getDefault()).format(java.util.Date())
            val d = java.text.SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(java.util.Date())
            return "Aaj $day, $d hai."
        }
        return null
    }

    // ---------- Main handle ----------
    private fun handle(text: String) {
        val t = text.lowercase().trim()
        val n = t.replace(" ", "")
        val acc = MitraAccessibilityService.instance

        // Aadat seekho
        try { Store.bumpHabit(this, t.take(45)) } catch (e: Exception) {}

        // ---- Aadat batao ----
        if (n.contains("aadat") || n.contains("habit") || n.contains("kyaseekha") || n.contains("kyasikha")) {
            val h = Store.habits(this).take(3)
            if (h.isEmpty()) { setMood(R.drawable.char_sad, "Abhi tak kuch seekha nahi"); return }
            val txt = h.mapIndexed { i, x -> "${i + 1}. ${x.first} (${x.second} baar)" }.joinToString("\n")
            setMood(R.drawable.char_happy, "Aapki aadatein:\n$txt", 13000)
            return
        }

        // ---- Timer (offline) ----
        val tm = Regex("(\\d+)\\s*(?:minute|min|minutes|मिनट)").find(n)
        if (tm != null && (n.contains("timer") || n.contains("countdown"))) {
            val mins = tm.groupValues[1].toIntOrNull() ?: 5
            startTimer(mins.coerceIn(1, 180))
            return
        }

        // Quiz chal raha hai to jawab handle karo
        if (quizOn) { quizAnswer(t); return }

        // Guide chal raha hai to next/band/atka handle karo
        if (guideOn) {
            if (n.contains("band") || n.contains("stop") || n.contains("exit") || n.contains("chhodo")) {
                guideOn = false
                setMood(R.drawable.char_happy, "Guide band kar diya")
                return
            }
            // Atak gaye? Thinking model se naya plan
            if (n.contains("nahihu") || n.contains("kaamnahi") || n.contains("stuck") || n.contains("problem") ||
                n.contains("nahiho") || n.contains("nhiho") || n.contains("atak") || n.contains("nahihua")) {
                guideReplan()
                return
            }
            if (n.contains("next") || n.contains("aage") || n.contains("agla") || n.contains("done") || n.contains("theek")) {
                guideIdx++
                if (guideIdx >= guideSteps.size) {
                    guideOn = false
                    setMood(R.drawable.char_laugh, "Sab steps ho gaye! 🎉")
                } else {
                    setMood(R.drawable.char_happy, "Step ${guideIdx + 1}: ${guideSteps[guideIdx]}\n\n\"next\" bolo", 12000)
                }
                return
            }
        }

        // ---- Chain: screen -> Notes ----
        if ((n.contains("screen") || n.contains("yescreen") || n.contains("ye")) && n.contains("notes") &&
            (n.contains("save") || n.contains("daalo") || n.contains("likho") || n.contains("rakho"))) {
            val a = MitraAccessibilityService.instance
            if (a == null) { setMood(R.drawable.char_sad, "Accessibility ON karo"); return }
            val txt = a.readScreenText()
            if (txt.isBlank()) { setMood(R.drawable.char_sad, "Screen pe text nahi mila"); return }
            saveNoteInApp(txt)
            return
        }

        // ---- Chain: screen -> ChatGPT ----
        if (n.contains("chatgpt") && (n.contains("screen") || n.contains("ye")) &&
            (n.contains("poocho") || n.contains("pucho") || n.contains("ask"))) {
            val a = MitraAccessibilityService.instance
            if (a == null) { setMood(R.drawable.char_sad, "Accessibility ON karo"); return }
            val txt = a.readScreenText()
            if (txt.isBlank()) { setMood(R.drawable.char_sad, "Screen pe text nahi mila"); return }
            setMood(R.drawable.char_think, "Opening ChatGPT...")
            Thread {
                val ok = a.askInAppText(
                    "com.openai.chatgpt", "ChatGPT",
                    "Explain this clearly and briefly:\n\n" + txt.take(900)
                ) { s -> main.post { setMood(R.drawable.char_think, s) } }
                if (!ok) { main.post { setMood(R.drawable.char_sad, "ChatGPT me type nahi hua") }; return@Thread }
                Thread.sleep(6500)
                val reply = a.readLastReply()
                main.post {
                    if (reply.isNotBlank()) {
                        Store.addNoteV2(this, "ChatGPT answer", reply)
                        setMood(R.drawable.char_laugh, "ChatGPT: ${reply.take(230)}", 12000)
                    } else setMood(R.drawable.char_sad, "ChatGPT ka jawab nahi mila")
                }
            }.start()
            return
        }

        // ---- Chain: ChatGPT ka jawab -> Notes ----
        if (n.contains("jawab") && n.contains("notes") &&
            (n.contains("save") || n.contains("daalo") || n.contains("likho") || n.contains("rakho"))) {
            val a = MitraAccessibilityService.instance
            if (a == null) { setMood(R.drawable.char_sad, "Accessibility ON karo"); return }
            val reply = a.readLastReply()
            if (reply.isBlank()) { setMood(R.drawable.char_sad, "Koi jawab nahi mila"); return }
            saveNoteInApp(reply)
            return
        }

        // ---- Guided mode (koi bhi app) ----
        if (n.contains("guide") || n.contains("sikhao") || n.contains("sikhado") || n.contains("sikha do") ||
            n.contains("kaise karna") || n.contains("kaise chalate") || n.contains("chalana") ||
            n.contains("use karna")) {
            guideStart(t); return
        }

        // ---- UPI (GPay / PhonePe / Paytm) - PIN aap daalo ----
        if ((n.contains("gpay") || n.contains("phonepe") || n.contains("paytm") || n.contains("upi")) &&
            (n.contains("bhejo") || n.contains("bhej") || n.contains("pay") || n.contains("send") || n.contains("transfer"))) {
            upiPay(t); return
        }

        // ---- Quiz mode ----
        if (n.contains("quiz") || n.contains("sawalpoocho") || n.contains("testlo") || n.contains("questionpoocho")) {
            val subj = t.replace(Regex("\\b(quiz|lo|le|karo|kar|shuru|start|poocho|pucho|sawal|questions?|test|me|pe|ka|ki|ke)\\b"), " ")
                .replace(Regex("\\s+"), " ").trim()
            startQuiz(if (subj.length > 2) subj else "Class 10 Science")
            return
        }

        // ---- Exam / study mode ----
        if (n.contains("exammode") || n.contains("padhaimode") || n.contains("studymode") || n.contains("exammode")) {
            val off = n.contains("band") || n.contains("off") || n.contains("hatao") || n.contains("stop")
            val mins = Regex("(\\d+)\\s*(?:min|minute)").find(n)?.groupValues?.get(1)?.toIntOrNull() ?: 60
            examMode(!off, mins)
            return
        }

        // Status mode ke andar commands
        if (statusMode) {
            if (n.contains("skip") || n.contains("next") || n.contains("aage") || n.contains("agla")) {
                skipRequested = true; setMood(R.drawable.char_happy, "Agla status!"); return
            }
            if (n.contains("back") || n.contains("peeche") || n.contains("pichla")) {
                acc?.tapLeft(); setMood(R.drawable.char_happy, "Pichla status"); return
            }
            if (n.contains("band") || n.contains("exit") || n.contains("bas") || n.contains("stop") || n.contains("ruko")) {
                statusMode = false; setMood(R.drawable.char_happy, "Status band kar diya"); return
            }
            return
        }

        // WhatsApp status dikhao
        if (n.contains("status") && (n.contains("dikha") || n.contains("dekh") || n.contains("chala") || n.contains("play"))) {
            startStatusMode(); return
        }

        if (n.contains("gudgudi") || n.contains("tickle")) { tickle(); return }
        if (n.contains("dance") || n.contains("naach") || n.contains("naacho")) { dance(); return }
        if (n.contains("jump") || n.contains("uchlo") || n.contains("uchak") || n.contains("kud")) { jump(); return }
        if (n.contains("spin") || n.contains("ghumo") || n.contains("chakkar")) { spin(); return }
        if (n.contains("kahanho") || n.contains("whereareyou") || n.contains("kahaaho")) {
            val dm = resources.displayMetrics
            setMood(R.drawable.char_happy, "Yahan hoon! Main tumhare paas hi hoon.")
            glideTo(dm.widthPixels / 2, dm.heightPixels / 3)
            return
        }
        if (n.contains("sojao") || n.contains("sleep") || n.contains("sona") || n == "soja") { sleep(); return }
        if (n.contains("uthjao") || n.contains("jago") || n.contains("jaago") || n.contains("wakeup")) { wakeUp(); return }

        // Hide / Show
        if (n.contains("hide") || n.contains("chhup") || n.contains("chhip") || n.contains("gayab") || n.contains("gum ho")) { hideBubble(); return }
        if (hidden && (n.contains("hello") || n.contains("aa jao") || n.contains("aaja") || n.contains("dikho") || n.contains("wapas") || n.contains("wapis") || n.contains("vapas"))) { showBubble(); return }

        // Aap kya kar rahe ho?
        if (n.contains("kya kar raha") || n.contains("kya kar rahi") || n.contains("kya hua") || n.contains("kya dekh") || n.contains("main kya kar")) { whatAmIDoing(); return }

        // "X app kholo aur Y karo" -> pehle X kholo, phir Y karo
        val mAnd = Regex("(.+?)\\s+(?:app\\s+)?(?:kholo|khol|open|chalu karo|chala do|chalao)\\s+aur\\s+(.+)").find(t)
        if (mAnd != null) {
            var first = mAnd.groupValues[1].trim().replace(Regex("\\b(app|application|please|mera|meri)\\b"), " ").trim()
            val second = mAnd.groupValues[2].trim()
            val app = resolveApp("open $first", first)
            if (app != null) {
                setMood(R.drawable.char_think, "${app.second} khol raha hoon...")
                Thread {
                    MitraAccessibilityService.instance?.openApp(app.first)
                    Thread.sleep(3200)
                    val pkg = app.first
                    val secondCmd = when {
                        pkg.contains("whatsapp") -> "whatsapp $second"
                        pkg.contains("gmail") -> "gmail $second"
                        else -> second
                    }
                    main.post { handle(secondCmd) }
                }.start()
                return
            }
        }

        // Gallery / images dekho
        val wantsView = n.contains("gallery") || n.contains("images") || n.contains("album") ||
            ((n.contains("photo") || n.contains("photos") || n.contains("tasveer")) &&
                (n.contains("dekho") || n.contains("dikha") || n.contains("khole") || n.contains("kholo")))
        val wantsShoot = n.contains("kheech") || n.contains("khich") || n.contains("selfie") ||
            n.contains("lena") || n.contains("le lo") || n.contains("capture") || n.contains("camera")
        if (wantsView && !wantsShoot) {
            val a = MitraAccessibilityService.instance
            if (a == null) { setMood(R.drawable.char_sad, "Iske liye Accessibility ON karo"); return }
            setMood(R.drawable.char_think, "Gallery khol raha hoon...")
            Thread {
                val ok = a.openGallery()
                main.post { if (ok) setMood(R.drawable.char_laugh, "Gallery khol di!") else setMood(R.drawable.char_sad, "Gallery nahi khuli") }
            }.start()
            return
        }

        // Photo / camera (screenshot nahi!)
        if (!n.contains("screenshot") && !n.contains("screenshort") &&
            (n.contains("photo") || n.contains("selfie") || n.contains("camera") || n.contains("tasveer") || n.contains("kheech") || n.contains("khich"))) {
            val a = MitraAccessibilityService.instance
            if (a == null) { setMood(R.drawable.char_sad, "Iske liye Accessibility ON karo"); return }
            val contact = parsePhotoContact(t)
            setMood(R.drawable.char_think, "Camera khol raha hoon...")
            Thread {
                val ok = a.takePhoto { s -> main.post { setMood(R.drawable.char_think, s) } }
                if (ok && contact != null) {
                    main.post { setMood(R.drawable.char_think, "$contact ko bhej raha hoon...") }
                    Thread.sleep(1500)
                    val sent = a.sendLastPhotoTo(contact) { s -> main.post { setMood(R.drawable.char_think, s) } }
                    main.post { if (sent) setMood(R.drawable.char_laugh, "$contact ko photo bhej di!") else setMood(R.drawable.char_sad, "Photo bhej nahi paya") }
                } else {
                    main.post { if (ok) setMood(R.drawable.char_laugh, "Photo khinch li! Check karo") else setMood(R.drawable.char_sad, "Camera nahi khula") }
                }
            }.start()
            return
        }

        // Alarm AM/PM pending
        if (pendingAlarmH >= 0) {
            val am = n.contains("am") || n.contains("subah") || n.contains("savere")
            val pm = n.contains("pm") || n.contains("shaam") || n.contains("sham") || n.contains("raat") || n.contains("dopahar")
            if (am || pm) {
                val h = pendingAlarmH
                val mi = pendingAlarmM
                pendingAlarmH = -1
                fireAlarm(if (am) (h % 12) else ((h % 12) + 12), mi)
                return
            }
        }
        // Reminder AM/PM pending
        if (pendingRemH >= 0) {
            val am = n.contains("am") || n.contains("subah")
            val pm = n.contains("pm") || n.contains("shaam") || n.contains("sham") || n.contains("raat") || n.contains("dopahar")
            if (am || pm) {
                val h = pendingRemH
                val mi = pendingRemM
                val tx = pendingRemText ?: "Yaad dilana"
                pendingRemH = -1
                scheduleReminder(tx, if (am) (h % 12) else ((h % 12) + 12), mi, "")
                return
            }
        }

        // Gmail pending (agla vaakya email/topic maan lo)
        if (gmailAwait != null) {
            if (n.contains("cancel") || n.contains("rehne")) {
                gmailAwait = null; gmailTo = null; gmailTopic = null; gmailName = null
                setMood(R.drawable.char_happy, "Cancel kar diya"); return
            }
            val utt = text.trim()
            if (gmailAwait == "email") { gmailTo = cleanEmail(utt); gmailAwait = null; doGmail(); return }
            if (gmailAwait == "topic") { gmailTopic = utt; gmailAwait = null; doGmail(); return }
        }

        // Scroll / reels / shorts
        if (isScrollCmd(n)) {
            if (acc == null) { setMood(R.drawable.char_sad, "Iske liye Accessibility ON karo"); return }
            when {
                n.contains("youtubeshorts") || (n.contains("shorts") && (n.contains("chala") || n.contains("kholo") || n.contains("dikha"))) -> {
                    setMood(R.drawable.char_think, "YouTube Shorts khol raha hoon...")
                    Thread {
                        val ok = acc.openUrl("https://www.youtube.com/shorts")
                        main.post { if (ok) setMood(R.drawable.char_laugh, "Shorts chalu!") else setMood(R.drawable.char_sad, "Shorts nahi khula") }
                    }.start()
                }
                n.contains("scrolldown") || n.contains("neeche") || n.contains("nichli") -> { nudgeScroll { acc.scrollDown() }; setMood(R.drawable.char_happy, "Neeche") }
                n.contains("scrollup") || n.contains("upar") -> { nudgeScroll { acc.scrollUp() }; setMood(R.drawable.char_happy, "Upar") }
                n.contains("prev") || n.contains("pichl") -> { nudgeScroll { acc.reelPrev() }; setMood(R.drawable.char_happy, "Pichla") }
                else -> { nudgeScroll { acc.reelNext() }; setMood(R.drawable.char_happy, "Agla") }
            }
            return
        }

        // ---- Screen padho / samjhao ----
        if (n.contains("screen") &&
            (n.contains("padho") || n.contains("padh") || n.contains("likha") || n.contains("read") ||
                n.contains("batao") || n.contains("samjhao") || n.contains("matlab"))) {
            val a = MitraAccessibilityService.instance
            if (a == null) { setMood(R.drawable.char_sad, "Accessibility ON karo"); return }
            val txt = a.readScreenText()
            if (txt.isBlank()) { setMood(R.drawable.char_sad, "Screen pe koi text nahi mila"); return }
            if (n.contains("samjhao") || n.contains("matlab") || n.contains("explain")) {
                setMood(R.drawable.char_think, "Thinking...")
                Thread {
                    val key = KeyPool.current("gemini") ?: ""
                    val (ok, ans) = if (key.isNotBlank())
                        Gemini.ask(key, "Explain this screen content simply for a school student:\n\n" + txt.take(1500))
                    else Pair(false, "")
                    main.post {
                        if (ok && ans.isNotBlank()) setMood(R.drawable.char_happy, ans.take(320), 12000)
                        else setMood(R.drawable.char_sad, "Samjha nahi paya")
                    }
                }.start()
            } else {
                setMood(R.drawable.char_happy, txt.take(320), 10000)
            }
            return
        }

        // ---- Homework: screen ya photo se solve ----
        if (n.contains("homework") || n.contains("question solve") || n.contains("solve karo") ||
            n.contains("sum solve") || n.contains("photo se solve") || n.contains("ye solve") ||
            n.contains("solve this")) {
            solveHomework(); return
        }

        // ---- <app> me <X> search karo ----
        val srch = Regex("(.+?)\\s+(?:me|pe|par|on)\\s+(.+?)\\s+(?:search karo|search|dhundo|khojo)").find(t)
        if (srch != null) {
            val appWord = srch.groupValues[1].trim().lowercase()
            val q = srch.groupValues[2].trim()
            val a = MitraAccessibilityService.instance
            if (a != null && q.isNotBlank() && appWord.isNotBlank() && appWord.length > 2) {
                val pkg = findInstalled(appWord)
                if (pkg != null) {
                    setMood(R.drawable.char_think, "Opening ${pkg.second}...")
                    Thread {
                        val ok = a.searchInApp(pkg.first, pkg.second, q) { s ->
                            main.post { setMood(R.drawable.char_think, s) }
                        }
                        main.post {
                            if (ok) setMood(R.drawable.char_laugh, "Searched \"$q\" in ${pkg.second}")
                            else setMood(R.drawable.char_sad, "Search nahi ho paya")
                        }
                    }.start()
                    return
                }
            }
        }

        // Notes app me likho - Gemini se content bana ke paste karo
        if ((n.contains("notes") || n.contains("note") || n.contains("keep")) &&
            (n.contains("likho") || n.contains("likh") || n.contains("paste") ||
                n.contains("jaake") || n.contains("jaa ke") || n.contains("save"))) {
            val topic = extractNoteTopic(t)
            if (topic.isNotBlank()) {
                val inApp = n.contains("bublu notes") || n.contains("apne notes") || n.contains("app me note")
                setMood(R.drawable.char_think, "Thinking...")
                Thread {
                    val content = generateContent(topic)
                    main.post { if (inApp) saveNoteInApp(content) else rollAround { createNote(content) } }
                }.start()
                return
            }
        }
        // Notes (app ke andar save hote hain)
        if (n.contains("notesdikhao") || n.contains("notespadho") || n.contains("notesbatao") || n.contains("notesunao") || n.contains("notesunado")) {
            readNotes(); return
        }
        val noteText = after(t, listOf("note banao ", "note add ", "notes me ", "note me ", "notes me likho ", "note likho "))
            ?.replace(Regex("\\b(likho|likh|banao|bana|add|karo|kar|do|de|please)\\b"), " ")?.trim()
        if (noteText != null && noteText.isNotBlank()) { saveNote(noteText); return }

        // Gmail pehle (taaki "gmail ... message" WhatsApp na bane)
        val isGmail = t.contains("gmail") || t.contains("email") || Regex("\\bmails?\\b").containsMatchIn(t) ||
            t.contains("जीमेल") || t.contains("ईमेल")
        if (isGmail) {
            val gm = parseGmail(t)
            if (gm != null) {
                if (gm.first.contains("@")) { gmailTo = gm.first; gmailName = null } else { gmailName = gm.first; gmailTo = null }
                gmailTopic = gm.second
                rollAround { doGmail() }
                return
            }
        }

        // WhatsApp — gehri samajh (kabhi Google search nahi)
        if (isWhatsAppIntent(t)) {
            val wa = parseWhatsApp(t)
            if (wa != null) {
                val contact = resolveAlias(wa.first)
                if (wa.second.isBlank()) {
                    pendingWaContact = contact
                    setMood(R.drawable.char_think, "What should I send to ${wa.first}?")
                    return
                }
                if (looksLikeRequest(wa.second)) {
                    setMood(R.drawable.char_think, "Writing the message...")
                    Thread {
                        val composed = composeMessage(contact, wa.second)
                        main.post { rollAround { doSendWhatsApp(contact, composed) } }
                    }.start()
                    return
                }
                rollAround { doSendWhatsApp(contact, wa.second) }
                return
            }
            setMood(R.drawable.char_think, "Who should I message, and what? Say: 'Rahul ko Hi'")
            return
        }

        // WhatsApp pending: agla vaakya message maan lo
        val pend = pendingWaContact
        if (pend != null) {
            if (n.contains("cancel") || n.contains("rehne") || n == "nahi" || n.contains("nahin")) {
                pendingWaContact = null
                setMood(R.drawable.char_happy, "Theek hai, cancel kar diya.")
                return
            }
            pendingWaContact = null
            doSendWhatsApp(pend, text.trim())
            return
        }

        // "X ka answer dhoondho" -> AI se jawab
        val mA = Regex("(?:google me |google pe |google par )?(.+?) ka (?:answer|jawab)").find(t)
        if (mA != null) {
            val q = mA.groupValues[1].trim()
            if (q.isNotBlank()) { askAI("Answer briefly and clearly: $q"); return }
        }

        if (n.contains("screenshot") || n.contains("screenshort")) {
            if (acc != null) { rollAround { acc.screenshot() }; setMood(R.drawable.char_happy, "Screenshot le liya") }
            else setMood(R.drawable.char_sad, "Screenshot ke liye Accessibility ON karo")
            return
        }

        // YouTube search
        val ytQ = after(t, listOf("youtube pe ", "youtube par "))
        if (ytQ != null) {
            val url = "https://www.youtube.com/results?search_query=" + android.net.Uri.encode(ytQ)
            openUrl(url)
            setMood(R.drawable.char_happy, "YouTube pe \"$ytQ\" dhoondh raha hoon")
            return
        }

        // Google search
        val searchQ = after(t, listOf("search ", "dhundo ", "google karo ", "google search "))
        if (searchQ != null) {
            val url = "https://www.google.com/search?q=" + android.net.Uri.encode(searchQ)
            openUrl(url)
            setMood(R.drawable.char_happy, "Google pe \"$searchQ\" search kar raha hoon")
            return
        }

        // ---- Alarm ----
        if (n.contains("alarm")) { handleAlarm(t); return }

        // Occasion with a date (didi ki 11 November ko engagement hai)
        if (hasMonthDate(t) && (n.contains("yaad") || n.contains("hai") || hasEventWord(n))) { handleEvent(t); return }

        // ---- Reminder / yaad dilana / schedule ----
        if (n.contains("yaad") || n.contains("remind") || n.contains("schedule")) { handleReminder(t); return }

        // ---- Birthday / Anniversary set ----
        if (n.contains("birthday") || n.contains("janamdin") || n.contains("anniversary") || n.contains("saalgirah")) {
            handleEvent(t); return
        }

        // ---- ChatGPT se lagatar baat ----
        if ((n.contains("chatgpt") || n.contains("chat gpt")) &&
            (n.contains("baat") || n.contains("conversation") || n.contains("chat karo") ||
                n.contains("greeting") || n.contains("baatein") || n.contains("baate"))) {
            val a = MitraAccessibilityService.instance
            if (a == null) { setMood(R.drawable.char_sad, "Accessibility ON karo"); return }
            setMood(R.drawable.char_think, "Opening ChatGPT...")
            Thread {
                val ok = a.chatWithApp(
                    "com.openai.chatgpt", "ChatGPT",
                    { s -> main.post { setMood(R.drawable.char_think, s) } },
                    { r -> main.post { setMood(R.drawable.char_happy, "ChatGPT: $r", 5000) } }
                )
                main.post {
                    if (ok) setMood(R.drawable.char_laugh, "Chat done! Said bye and came back home.")
                    else setMood(R.drawable.char_sad, "Chat nahi ho paya")
                }
            }.start()
            return
        }

        // ---- Kisi bhi app me kaam: "ChatGPT se ye poocho" ----
        val ask = parseAskInApp(t)
        if (ask != null) {
            val a = MitraAccessibilityService.instance
            if (a == null) { setMood(R.drawable.char_sad, "Iske liye Accessibility ON karo"); return }
            setMood(R.drawable.char_think, "${ask.second} khol raha hoon...")
            Thread {
                val ok = a.askInApp(ask.first, ask.second, ask.third) { s -> main.post { setMood(R.drawable.char_think, s) } }
                main.post { if (ok) setMood(R.drawable.char_laugh, "${ask.second} me poochh liya!") else setMood(R.drawable.char_sad, "Nahi ho paya") }
            }.start()
            return
        }

        // ---- Play Store se install ----
        val inst = parseInstall(t)
        if (inst != null) {
            val a = MitraAccessibilityService.instance
            if (a == null) { setMood(R.drawable.char_sad, "Iske liye Accessibility ON karo"); return }
            setMood(R.drawable.char_think, "Play Store khol raha hoon...")
            Thread {
                val ok = a.playStoreInstall(inst) { s -> main.post { setMood(R.drawable.char_think, s) } }
                main.post { if (ok) setMood(R.drawable.char_laugh, "$inst install kar raha hoon!") else setMood(R.drawable.char_sad, "Install nahi ho paya") }
            }.start()
            return
        }

        // ---- Important WhatsApp group set ----
        if (n.contains("important")) {
            if (n.contains("dikhao") || n.contains("list") || n.contains("batao")) {
                val gs = Store.importantGroups(this)
                setMood(R.drawable.char_happy, if (gs.isEmpty()) "Koi important group set nahi" else "Important: " + gs.joinToString(", "))
                return
            }
            var g = ""
            Regex("(.+?)\\s+(?:group|grp)?\\s*ko\\s+important").find(t)?.let { g = it.groupValues[1] }
            if (g.isBlank()) Regex("important\\s+(?:group\\s+)?(.+)").find(t)?.let { g = it.groupValues[1] }
            g = g.replace(Regex("\\b(group|grp|ko|set|karo|kar|important|add|please|as|the|me|ke|liye|se)\\b"), " ").replace(Regex("\\s+"), " ").trim()
            if (g.isNotBlank()) {
                Store.addImportantGroup(this, g)
                setMood(R.drawable.char_laugh, "'$g' important set! Ab uske messages bataunga.")
            } else {
                setMood(R.drawable.char_think, "Kaunsa group important set karun? Jaise 'school group ko important set karo'")
            }
            return
        }

        // ---- Alias set: "bhaiya ka naam Ravi rakho" / "bhaiya ko Ravi bulao" / "bhaiya ka number 98765 save karo" ----
        val aliasTrig = n.contains("bulao") || n.contains("nickname") ||
            (t.contains(" ka naam ") && (n.contains("rakho") || n.contains("save") || n.contains("set") || n.contains("karo"))) ||
            (t.contains(" ka number ") && (n.contains("save") || n.contains("rakho") || n.contains("set") || n.contains("karo")))
        if (aliasTrig && handleAlias(t)) return

        // ---- Call ----
        if (n.contains("call") || n.contains("dial") || (n.contains("phone") && n.contains("karo"))) { callContact(t); return }

        // ---- Calculator ----
        if (looksLikeMath(t, n)) {
            val ans = calcAnswer(t)
            if (ans != null) { setMood(R.drawable.char_laugh, "= $ans"); return }
        }

        // ---- Flashlight ----
        if (n.contains("flashlight") || n.contains("torch") || n.contains("batti")) {
            flashlight(!(n.contains("off") || n.contains("band")))
            return
        }

        // ---- Volume ----
        if (n.contains("volume")) {
            if (n.contains("mute") || n.contains("silent")) muteVolume()
            else volume(!(n.contains("down") || n.contains("kam") || n.contains("ghata")))
            return
        }

        // ---- Call / dial ----
        if (n.contains("call") || n.contains("dial")) { dial(t); return }

        // ---- Translate / meaning ----
        if (n.contains("translate") || n.contains("matlab") || n.contains("meaning")) {
            askAI("Explain or translate in simple Hinglish: " + t); return
        }

        // ---- Tasks ----
        if (n.contains("task")) {
            if (n.contains("dikhao") || n.contains("list") || n.contains("batao")) { listTasks(); return }
            if (n.contains("done") || n.contains("complete")) {
                val what = t.replace(Regex("\\b(task|done|complete|mark|karo|kar|ka|ki|ho|gaya)\\b"), " ").trim()
                Store.doneTask(this, what)
                setMood(R.drawable.char_laugh, "Task done!")
                return
            }
            val what = after(t, listOf("task add ", "task "))?.trim()
            if (!what.isNullOrBlank()) { addTask(what); return }
        }

        val app = resolveApp(t, n)
        if (app != null) { rollAround { openTarget(app.first, app.second) }; return }

        val c = canned(t, n)
        if (c != null) { setMood(R.drawable.char_happy, c); return }

        if (acc == null) {
            if (looksLikeAction(t)) {
                setMood(R.drawable.char_sad, "Click/type ke liye Settings me $NAME ki Accessibility ON karo")
                return
            }
        } else {
            // Type -> ludhak kar input box tak, phir type
            val typeWhat = after(t, listOf("type ", "likho ", "likh "))
            if (typeWhat != null) {
                val ctr = acc.centerOfFocusedEditable()
                if (ctr != null) {
                    glideAndDo(ctr[0], ctr[1], "Type kar diya!") { acc.typeText(typeWhat) }
                } else {
                    if (acc.typeText(typeWhat)) setMood(R.drawable.char_laugh, "Type kar diya!")
                    else setMood(R.drawable.char_sad, "Koi input box nahi mila")
                }
                return
            }
            if (n.contains("paste") || n.contains("chipka")) {
                if (acc.paste()) { setMood(R.drawable.char_laugh, "Paste kar diya!"); return }
            }
            // Click -> ludhak kar us jagah, phir click
            val clickWhat = after(t, listOf("click ", "tap "))
            if (clickWhat != null) {
                val ctr = acc.centerOfText(clickWhat)
                if (ctr != null) {
                    glideAndDo(ctr[0], ctr[1], "Click kar diya!") { acc.clickByText(clickWhat) }
                } else {
                    setMood(R.drawable.char_sad, "\"$clickWhat\" nahi mila screen pe")
                }
                return
            }
            if (n == "back" || n.contains("goback") || n.contains("peeche")) { acc.back(); setMood(R.drawable.char_happy, "Peeche"); return }
            if (n == "home" || n.contains("gohome") || n.contains("ghar")) { acc.home(); setMood(R.drawable.char_happy, "Home"); return }
            if (n.contains("recent")) { acc.recents(); setMood(R.drawable.char_happy, "Recent apps"); return }
            if (n.contains("notification")) { acc.notifications(); setMood(R.drawable.char_happy, "Notifications"); return }
        }

        geminiBrain(text)
    }

    private fun openUrl(url: String) {
        val acc = MitraAccessibilityService.instance
        if (acc != null && acc.openUrl(url)) return
        try {
            val web = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
            web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(web)
        } catch (e: Exception) {}
    }

    private fun startStatusMode() {
        val acc = MitraAccessibilityService.instance
        if (acc == null) { setMood(R.drawable.char_sad, "Status ke liye Accessibility ON karo"); return }
        statusMode = true
        skipRequested = false
        setMood(R.drawable.char_think, "WhatsApp status khol raha hoon...")
        Thread {
            val count = acc.openStatusTabAndFirst()
            if (count < 0) {
                main.post { statusMode = false; setMood(R.drawable.char_sad, "WhatsApp status nahi khula") }
                return@Thread
            }
            if (count == 0) {
                main.post { statusMode = false; setMood(R.drawable.char_sad, "Aaj kisi ne status nahi lagaya") }
                try { acc.home() } catch (e: Exception) {}
                return@Thread
            }
            main.post { setMood(R.drawable.char_think, "$count logon ne status lagaya hai. Dekh raha hoon... ('skip' bolo aage badhne ke liye)") }
            var seen = 0
            while (statusMode) {
                val start = System.currentTimeMillis()
                while (statusMode && !skipRequested && System.currentTimeMillis() - start < 5500) {
                    Thread.sleep(150)
                }
                skipRequested = false
                if (!statusMode) break
                if (!acc.isInWhatsApp()) break
                if (acc.isOnStatusList()) break
                seen++
                acc.tapRight()
                Thread.sleep(500)
            }
            main.post {
                statusMode = false
                if (seen == 0) setMood(R.drawable.char_sad, "Koi status nahi dekha")
                else setMood(R.drawable.char_laugh, "Bas itne hi logon ne aaj status lagaya hai! ($count log)")
            }
            try { acc.home() } catch (e: Exception) {}
        }.start()
    }

    private fun doSendWhatsApp(contact: String, message: String) {
        val acc = MitraAccessibilityService.instance
        if (acc == null) { setMood(R.drawable.char_sad, "WhatsApp ke liye Accessibility ON karo"); return }
        // Send ke dauran mic band + touch block (galti se mic hold na ho)
        listening = false
        try { recognizer?.cancel() } catch (e: Exception) {}
        hideWave()
        busy = true
        main.postDelayed({ busy = false }, 45000)
        setMood(R.drawable.char_think, "WhatsApp khol raha hoon...")
        Thread {
            val ok = acc.sendWhatsApp(contact, message) { status ->
                main.post { setMood(R.drawable.char_think, status) }
            }
            main.post {
                busy = false
                if (ok) setMood(R.drawable.char_laugh, "Done! $contact ko \"$message\" bhej diya")
                else setMood(R.drawable.char_sad, "Send confirm nahi hua - WhatsApp me ek baar check kar lo")
            }
        }.start()
    }

    /** WhatsApp ke kai tarah ke vaaky samajhta hai. Pair(contact, message) deta hai (message khali ho sakta hai). */
    private fun isScrollCmd(n: String): Boolean {
        return n.contains("scrolldown") || n.contains("scrollup") || n.contains("neeche") ||
            n.contains("upar") || n.contains("nextreel") || n.contains("aglireel") ||
            n.contains("next") || n.contains("agla") || n.contains("agli") ||
            n.contains("prev") || n.contains("pichla") || n.contains("pichli") || n.contains("previous") ||
            n.contains("youtubeshorts") ||
            (n.contains("shorts") && (n.contains("chala") || n.contains("kholo") || n.contains("dikha")))
    }

    private fun cleanEmail(s: String): String =
        s.replace(" at ", "@").replace(" dot ", ".").replace(" ", "").trim()

    private fun parseGmail(tRaw: String): Pair<String, String>? {
        val t = tRaw.lowercase().trim()
        val isMail = t.contains("gmail") || t.contains("email") || Regex("\\bmail\\b").containsMatchIn(t)
        if (!isMail) return null
        var s = t.replace(Regex("(gmail|email|mail)"), " ")
        s = s.replace(Regex("\\b(pe|par|per|me|on|se|pa|pr)\\b"), " ")
        s = s.replace(Regex("\\s+"), " ").trim()
        val i = s.indexOf(" ko ")
        if (i < 0) return null
        val to = s.substring(0, i).replace(Regex("\\b(pe|par|per|me|on|se|ko)\\b"), " ").trim()
        var topic = s.substring(i + 4).trim()
        topic = topic.replace(
            Regex("\\b(bhejo|bhej|likho|likh|send|karo|kar|kardo|do|de|please|plz|mail|email|gmail|message|msg)\\b"), " "
        ).trim()
        if (to.isBlank()) return null
        return Pair(to, topic)
    }

    private fun doGmail() {
        if (gmailTo.isNullOrBlank()) {
            gmailAwait = "email"
            val who = gmailName
            setMood(R.drawable.char_think, if (who.isNullOrBlank()) "Kisko bhejna hai? Email address bolo." else "$who ka email address kya hai? Bolo.")
            return
        }
        if (gmailTopic.isNullOrBlank()) {
            gmailAwait = "topic"
            setMood(R.drawable.char_think, "Kya likhna hai? Bolo (jaise 'leave application').")
            return
        }
        val to = gmailTo!!
        val topic = gmailTopic!!
        gmailTo = null; gmailTopic = null; gmailName = null; gmailAwait = null
        setMood(R.drawable.char_think, "Mail likh raha hoon...")
        Thread {
            val draft = draftEmail(topic)
            val a = MitraAccessibilityService.instance
            val ok = a?.composeEmail(to, draft.first, draft.second) { s ->
                main.post { setMood(R.drawable.char_think, s) }
            } ?: false
            main.post { if (ok) setMood(R.drawable.char_laugh, "Mail bhej diya $to ko!") else setMood(R.drawable.char_sad, "Mail poora nahi hua") }
        }.start()
    }

    private fun draftEmail(topic: String): Pair<String, String> {
        val key = getSharedPreferences("mitra", MODE_PRIVATE).getString("gemini_key", "")?.trim() ?: ""
        if (key.isBlank()) return Pair(topic, topic)
        val (ok, txt) = Gemini.ask(
            key,
            "Write a short polite email about: \"$topic\". Reply ONLY in this format:\nSubject: <one line>\n\n<body 3-5 lines>"
        )
        if (!ok) return Pair(topic, topic)
        var subject = topic
        var body = txt
        val lines = txt.split("\n")
        if (lines.isNotEmpty() && lines[0].lowercase().startsWith("subject:")) {
            subject = lines[0].substringAfter(":").trim()
            body = txt.substringAfter("\n").trim()
        }
        return Pair(subject, body)
    }

    // ---------- Quiz mode ----------
    @Volatile private var quizOn = false
    private var quizQ: List<Triple<String, List<String>, Int>> = emptyList()
    private var quizIdx = 0
    private var quizScore = 0

    private fun startQuiz(subject: String) {
        setMood(R.drawable.char_think, "Making quiz...")
        Thread {
            val prompt = "Create 5 multiple-choice questions for a Class 10 student on: $subject. " +
                "Reply ONLY with a JSON array like [{\"q\":\"question\",\"options\":[\"a\",\"b\",\"c\",\"d\"],\"answer\":0}]"
            val parsed = parseQuiz(brainAnswer(prompt))
            main.post {
                if (parsed.isEmpty()) {
                    setMood(R.drawable.char_sad, "Quiz ban nahi paya - phir try karo")
                    return@post
                }
                quizQ = parsed
                quizIdx = 0
                quizScore = 0
                quizOn = true
                askQuizQuestion()
            }
        }.start()
    }

    private fun askQuizQuestion() {
        if (quizIdx >= quizQ.size) { finishQuiz(); return }
        val q = quizQ[quizIdx]
        val opts = q.second.mapIndexed { i, o -> "${i + 1}. $o" }.joinToString("   ")
        setMood(
            R.drawable.char_think,
            "Q${quizIdx + 1}/${quizQ.size}: ${q.first}\n$opts\n\nBolo option number",
            16000
        )
    }

    private fun quizAnswer(t: String) {
        if (t.contains("band") || t.contains("stop") || t.contains("exit") || t.contains("chhodo")) {
            quizOn = false
            setMood(R.drawable.char_happy, "Quiz band kar diya")
            return
        }
        val idx = when {
            t.contains("option1") || t.contains("pehla") || t.matches(Regex(".*\\b1\\b.*")) -> 0
            t.contains("option2") || t.contains("doosra") || t.matches(Regex(".*\\b2\\b.*")) -> 1
            t.contains("option3") || t.contains("teesra") || t.matches(Regex(".*\\b3\\b.*")) -> 2
            t.contains("option4") || t.contains("chautha") || t.matches(Regex(".*\\b4\\b.*")) -> 3
            else -> -1
        }
        if (idx < 0) {
            setMood(R.drawable.char_sad, "Option 1, 2, 3 ya 4 bolo")
            return
        }
        val q = quizQ[quizIdx]
        if (idx == q.third) {
            quizScore++
            setMood(R.drawable.char_laugh, "Sahi jawab! ✅")
        } else {
            setMood(R.drawable.char_sad, "Galat. Sahi hai: ${q.second.getOrNull(q.third) ?: "-"}", 5000)
        }
        quizIdx++
        main.postDelayed({ askQuizQuestion() }, 2200)
    }

    private fun finishQuiz() {
        quizOn = false
        val total = quizQ.size
        val msg = when {
            quizScore == total -> "Perfect $quizScore/$total! 🎉 Shabaash!"
            quizScore >= total * 0.6 -> "Score: $quizScore/$total - accha kiya!"
            else -> "Score: $quizScore/$total - thoda aur practice karo"
        }
        setMood(R.drawable.char_laugh, msg, 10000)
    }

    private fun parseQuiz(raw: String?): List<Triple<String, List<String>, Int>> {
        if (raw == null) return emptyList()
        val start = raw.indexOf('[')
        if (start < 0) return emptyList()
        val end = raw.lastIndexOf(']')
        if (end <= start) return emptyList()
        val json = raw.substring(start, end + 1)
        return try {
            val arr = org.json.JSONArray(json)
            val out = ArrayList<Triple<String, List<String>, Int>>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val q = o.optString("q", "").trim()
                val oa = o.optJSONArray("options") ?: continue
                val opts = ArrayList<String>()
                for (j in 0 until oa.length()) opts.add(oa.optString(j, ""))
                if (q.isBlank() || opts.size < 2) continue
                out.add(Triple(q, opts, o.optInt("answer", 0).coerceIn(0, opts.size - 1)))
            }
            out
        } catch (e: Exception) { emptyList() }
    }

    // ---------- Guided mode ----------
    @Volatile private var guideOn = false
    private var guideSteps: List<String> = emptyList()
    private var guideIdx = 0
    private var guideGoal = ""

    private val knownApps = listOf(
        "whatsapp" to "com.whatsapp",
        "youtube" to "com.google.android.youtube",
        "telegram" to "org.telegram.messenger",
        "jiosaavn" to "com.jio.media.jiobeats",
        "jio savan" to "com.jio.media.jiobeats",
        "spotify" to "com.spotify.music",
        "instagram" to "com.instagram.android",
        "facebook" to "com.facebook.katana",
        "gpay" to "com.google.android.apps.nbu.paisa.user",
        "google pay" to "com.google.android.apps.nbu.paisa.user",
        "phonepe" to "com.phonepe.app",
        "paytm" to "net.one97.paytm",
        "chrome" to "com.android.chrome",
        "gmail" to "com.google.android.gm",
        "camera" to "com.android.camera",
        "gallery" to "com.google.android.apps.photos",
        "play store" to "com.android.vending",
        "settings" to "com.android.settings",
        "calculator" to "com.google.android.calculator"
    )

    private fun guideStart(t: String) {
        val a = MitraAccessibilityService.instance
        if (a == null) { setMood(R.drawable.char_sad, "Accessibility ON karo"); return }
        val appHit = knownApps.firstOrNull { t.contains(it.first) }
        guideGoal = t.replace(
            Regex("\\b(guide|karo|kar|do|sikhao|sikha|sikhado|kaise|chalate|chalana|use|karna|hai|batao|mujhe|ko|please|app|me|pe)\\b"),
            " "
        ).replace(Regex("\\s+"), " ").trim()
        if (guideGoal.length < 3) guideGoal = "use this app"

        if (appHit != null) {
            setMood(R.drawable.char_think, "Opening ${appHit.first}...")
            Thread {
                a.openAppSmart(appHit.second, appHit.first)
                Thread.sleep(3600)
                val screen = a.readScreenText()
                val steps = makeSteps(appHit.first, guideGoal, screen)
                main.post {
                    if (steps.isEmpty()) setMood(R.drawable.char_sad, "Steps nahi ban paye")
                    else beginGuide(steps)
                }
            }.start()
        } else {
            setMood(R.drawable.char_think, "Thinking...")
            Thread {
                val screen = a.readScreenText()
                val steps = makeSteps("this app", guideGoal, screen)
                main.post {
                    if (steps.isEmpty()) setMood(R.drawable.char_sad, "Steps nahi ban paye")
                    else beginGuide(steps)
                }
            }.start()
        }
    }

    private fun makeSteps(appName: String, goal: String, screen: String): List<String> {
        val prompt = "App: $appName. Current screen text:\n${screen.take(800)}\n\n" +
            "The user wants to: $goal. Give exactly 4 very short, practical steps " +
            "(max 12 words each) in simple English/Hinglish, as a JSON array of strings."
        return parseSteps(brainAnswer(prompt))
    }

    private fun beginGuide(steps: List<String>) {
        guideSteps = steps
        guideIdx = 0
        guideOn = true
        setMood(R.drawable.char_happy, "Step 1: ${steps[0]}\n\n\"next\" bolo aage ke liye", 13000)
    }

    /** User atak gaya - thinking model se naya plan. */
    private fun guideReplan() {
        setMood(R.drawable.char_think, "Thinking...")
        Thread {
            val a = MitraAccessibilityService.instance
            val screen = a?.readScreenText() ?: ""
            val prompt = "The user is stuck on this phone screen:\n${screen.take(800)}\n\n" +
                "Their goal: $guideGoal. Give 3 short next steps to get unstuck, as a JSON array of strings."
            val steps = parseSteps(brainAnswer(prompt))
            main.post {
                if (steps.isEmpty()) setMood(R.drawable.char_sad, "Samajh nahi paya - phir bolo")
                else beginGuide(steps)
            }
        }.start()
    }

    // ---------- UPI (PIN aap daalo) ----------
    private fun upiPay(t: String) {
        val app = when {
            t.contains("gpay") || t.contains("google pay") -> "gpay" to "com.google.android.apps.nbu.paisa.user"
            t.contains("phonepe") -> "phonepe" to "com.phonepe.app"
            else -> "paytm" to "net.one97.paytm"
        }
        val m = Regex("(.+?)\\s+ko\\s+(\\d+)").find(t)
        val contact = m?.groupValues?.get(1)
            ?.replace(Regex("\\b(upi|gpay|phonepe|paytm|google|pay|me|se|paise|rupaye|rupees|rs)\\b"), " ")
            ?.replace(Regex("\\s+"), " ")?.trim() ?: ""
        val amount = m?.groupValues?.get(2) ?: ""
        val a = MitraAccessibilityService.instance
        if (a == null) { setMood(R.drawable.char_sad, "Accessibility ON karo"); return }
        if (contact.isBlank() || amount.isBlank()) {
            setMood(R.drawable.char_sad, "Aise bolo: GPay me Rahul ko 200 bhejo", 6000)
            return
        }
        setMood(R.drawable.char_think, "Opening ${app.first}...")
        Thread {
            a.openAppSmart(app.second, app.first)
            Thread.sleep(4500)
            var found = a.clickByText(contact)
            if (!found) found = a.clickByDesc(contact)
            if (!found) {
                if (a.clickByDesc("Search") || a.clickByText("Search")) Thread.sleep(1300)
                a.typeInto(contact)
                Thread.sleep(2200)
                found = a.clickByText(contact)
            }
            Thread.sleep(2200)
            val typed = a.typeInto(amount)
            main.post {
                setMood(
                    if (typed) R.drawable.char_happy else R.drawable.char_sad,
                    if (typed) "$contact ko ₹$amount bhar diya. Ab PIN aap daalo 🔒"
                    else "$contact nahi mila - manually karo",
                    9000
                )
            }
        }.start()
    }

    private fun parseSteps(raw: String?): List<String> {
        if (raw == null) return emptyList()
        val s = raw.indexOf('[')
        val e = raw.lastIndexOf(']')
        if (s >= 0 && e > s) {
            try {
                val arr = org.json.JSONArray(raw.substring(s, e + 1))
                val out = ArrayList<String>()
                for (i in 0 until arr.length()) {
                    val v = arr.optString(i, "").trim()
                    if (v.isNotBlank()) out.add(v)
                }
                if (out.isNotEmpty()) return out
            } catch (ex: Exception) {}
        }
        return raw.split("\n").map { it.trim() }
            .filter { it.length > 3 }
            .map { it.replace(Regex("^\\d+[.)]\\s*"), "") }
            .take(5)
    }

    // ---------- Exam mode ----------
    @Volatile private var examUntil = 0L

    private fun examMode(on: Boolean, minutes: Int) {
        if (on) {
            examUntil = System.currentTimeMillis() + minutes * 60000L
            try {
                val nm = getSystemService(android.app.NotificationManager::class.java)
                if (nm != null && nm.isNotificationPolicyAccessGranted)
                    nm.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            } catch (e: Exception) {}
            setMood(R.drawable.char_laugh, "Exam mode ON for $minutes min. Focus karo, all the best! 📚", 7000)
        } else {
            examUntil = 0
            try {
                val nm = getSystemService(android.app.NotificationManager::class.java)
                if (nm != null && nm.isNotificationPolicyAccessGranted)
                    nm.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
            } catch (e: Exception) {}
            setMood(R.drawable.char_happy, "Exam mode OFF", 4000)
        }
    }

    /** Internet check. */
    private fun isOnline(): Boolean {
        return try {
            val cm = getSystemService(android.net.ConnectivityManager::class.java)
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) { true }
    }

    // ---------- Timer ----------
    @Volatile private var timerJob: android.os.CountDownTimer? = null

    private fun startTimer(minutes: Int) {
        try { timerJob?.cancel() } catch (e: Exception) {}
        setMood(R.drawable.char_happy, "Timer set: $minutes minute ⏳", 4000)
        timerJob = object : android.os.CountDownTimer(minutes * 60000L, 60000L) {
            override fun onTick(ms: Long) {
                val m = (ms / 60000).toInt()
                if (m in 1..3) main.post { setMood(R.drawable.char_think, "⏳ $m minute bache", 3000) }
            }

            override fun onFinish() {
                main.post {
                    setMood(R.drawable.char_laugh, "⏰ Timer khatam! $minutes minute ho gaye.", 12000)
                    try { fxConfetti() } catch (e: Exception) {}
                }
            }
        }.start()
    }

    /** Homework: screen pe question ho to wahi, warna camera se photo le ke solve. */
    private fun solveHomework() {
        val a = MitraAccessibilityService.instance
        if (a == null) { setMood(R.drawable.char_sad, "Accessibility ON karo"); return }

        val screenTxt = a.readScreenText()
        if (screenTxt.length > 40) {
            setMood(R.drawable.char_think, "Solving...")
            Thread {
                val prompt = "Solve this question step by step for a Class 10 student. " +
                    "Simple English or Hinglish, short headings, max 200 words.\n\n" + screenTxt.take(1500)
                val ans = brainAnswer(prompt)
                main.post {
                    if (ans != null) {
                        Store.addNoteV2(this, "Homework solution", ans)
                        setMood(R.drawable.char_laugh, ans.take(320), 14000)
                    } else setMood(R.drawable.char_sad, "Solve nahi ho paya - key check karo")
                }
            }.start()
            return
        }

        setMood(R.drawable.char_think, "Opening camera...")
        Thread {
            if (!a.takePhoto { s -> main.post { setMood(R.drawable.char_think, s) } }) {
                main.post { setMood(R.drawable.char_sad, "Photo nahi khinch paya") }
                return@Thread
            }
            Thread.sleep(1600)
            a.home()
            Thread.sleep(700)
            main.post { setMood(R.drawable.char_think, "Solving...") }
            val b64 = latestPhotoBase64()
            if (b64 == null) {
                main.post { setMood(R.drawable.char_sad, "Photo read nahi ho payi - Photos permission do") }
                return@Thread
            }
            val prompt = "Solve the question in this image step by step for a Class 10 student. " +
                "Simple English or Hinglish, short headings, max 200 words."
            var ans: String? = null
            val gk = KeyPool.current("gemini")
            if (gk != null) {
                val (ok, t1) = Gemini.askImage(gk, prompt, b64)
                if (ok && t1.isNotBlank()) ans = t1
            }
            if (ans == null) ans = brainAnswer(prompt)
            main.post {
                if (ans != null) {
                    Store.addNoteV2(this, "Homework solution", ans!!)
                    setMood(R.drawable.char_laugh, ans!!.take(320), 14000)
                } else setMood(R.drawable.char_sad, "Solve nahi ho paya")
            }
        }.start()
    }

    /** Gemini (multi-key) -> Sarvam thinking fallback. */
    private fun brainAnswer(prompt: String): String? {
        val gk = KeyPool.current("gemini")
        if (gk != null) {
            val (ok, t) = Gemini.ask(gk, prompt)
            if (ok && t.isNotBlank()) return t
        }
        val sk = KeyPool.current("sarvam")
        if (sk != null) {
            val (ok, t) = SarvamChat.ask(sk, prompt)
            if (ok && t.isNotBlank()) return t
        }
        return null
    }

    /** Gallery ki sabse nayi photo -> base64 jpeg (chhoti). */
    private fun latestPhotoBase64(): String? {
        return try {
            val uri = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val cur = contentResolver.query(
                uri, arrayOf(android.provider.MediaStore.Images.Media._ID),
                null, null, android.provider.MediaStore.Images.Media.DATE_ADDED + " DESC"
            )
            cur?.use {
                if (!it.moveToFirst()) return null
                val id = it.getLong(0)
                val u = android.content.ContentUris.withAppendedId(uri, id)
                val bmp = android.graphics.BitmapFactory.decodeStream(contentResolver.openInputStream(u))
                    ?: return null
                val w = bmp.width
                val h = bmp.height
                val max = 900f
                val sc = if (w > max || h > max) minOf(max / w, max / h) else 1f
                val s2 = if (sc < 1f)
                    android.graphics.Bitmap.createScaledBitmap(bmp, (w * sc).toInt(), (h * sc).toInt(), true)
                else bmp
                val baos = java.io.ByteArrayOutputStream()
                s2.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, baos)
                android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
            }
        } catch (e: Exception) { null }
    }

    /** Notes app fail ho to Bublu ke apne Notes me save karo. */
    private fun saveNoteInApp(text: String) {
        try {
            val first = text.lineSequence().firstOrNull()?.trim() ?: ""
            val title = if (first.length in 3..45) first else "Bublu note"
            Store.addNoteV2(this, title, text)
            setMood(
                R.drawable.char_happy,
                "Saved in Bublu Notes! Open the Notes card in the app to read it.",
                7000
            )
        } catch (e: Exception) {
            setMood(R.drawable.char_sad, "Note save nahi ho paya")
        }
    }

    private fun createNote(text: String) {
        val a = MitraAccessibilityService.instance
        if (a == null) { saveNoteInApp(text); return }
        val pkg = findInstalled("keep") ?: findInstalled("note") ?: findInstalled("notebook") ?: findInstalled("memo")
        if (pkg == null) { saveNoteInApp(text); return }
        setMood(R.drawable.char_think, "Notes khol raha hoon...")
        Thread {
            a.openAppAndCheck(pkg.first, pkg.second)
            Thread.sleep(3000)
            // Naya note kholo
            if (!a.clickByDesc("New note") && !a.clickByText("New note")) {
                if (!a.clickByDesc("Take a note") && !a.clickByText("Take a note")) {
                    if (!a.clickByDesc("Create new note") && !a.clickByText("Create new note")) {
                        a.clickByDesc("Add note")
                    }
                }
            }
            Thread.sleep(1800)
            // Likho - 2 baar try
            var ok = a.writeNote(text)
            if (!ok) { Thread.sleep(700); ok = a.writeNote(text) }
            main.post {
                if (ok) setMood(R.drawable.char_laugh, "Note saved in Notes app: ${text.take(30)}")
                else saveNoteInApp(text)
            }
        }.start()
    }

    private fun isWhatsAppIntent(t: String): Boolean {
        if (t.contains("whatsapp") || t.contains("whats app") || t.contains("whatsap") || t.contains("watsapp") ||
            t.contains("व्हाट्सएप") || t.contains("वाट्सएप") || t.contains("वॉट्सऐप") || t.contains("व्हाट्सऐप")
        ) return true
        val ko = t.contains(" ko ") || t.contains("को")
        val msg = t.contains("message") || t.contains("msg") || t.contains("massage") || t.contains("sandesh") ||
            t.contains("मैसेज") || t.contains("संदेश") || t.contains("मेसेज")
        return ko && msg
    }

    private fun parseWhatsApp(tRaw: String): Pair<String, String>? {
        var s = tRaw.lowercase().trim()
        s = s.replace(Regex("(whatsapp|whats app|whatsap|watsapp)"), " ")
        s = s.replace(Regex("(व्हाट्सएप|वाट्सएप|वॉट्सऐप|व्हाट्सऐप)"), " ")
        s = s.replace("को", " ko ")
        // connector poora shabd hatao (word boundary) - "per" ka 'r' bacha nahi rahega
        s = s.replace(Regex("\\b(pe|par|per|me|on|se|pa|pr)\\b"), " ")
        s = s.replace(Regex("(पर|पे|में)"), " ")
        s = s.replace(Regex("\\s+"), " ").trim()
        val i = s.indexOf(" ko ")
        if (i < 0) return null
        var contact = s.substring(0, i).trim()
        var msg = s.substring(i + 4).trim()
        contact = contact.replace(
            Regex(
                "\\b(pe|par|per|me|on|se|ko|message|massage|massege|msg|text|" +
                    "dost|dosti|friend|bhai|bhaiya|bhaiyya|behen|behan|didi|sis|sister|bro|brother|" +
                    "mom|mummy|mumma|maa|mother|papa|dad|daddy|father|uncle|aunty|aunty|" +
                    "sir|madam|maam|teacher|boss|colleague|gf|bf|wife|husband|beta|yaar|ji|" +
                    "mr|mrs|miss|please|plz|zara|thoda|ek|the|named|naam)\\b"
            ), " "
        ).trim()
        contact = contact.replace(Regex("(को|पर|पे|में)"), " ").trim()
        msg = msg.replace(
            Regex("\\b(bolo|bol|bhejo|bhej|kaho|kahdo|kehdo|kehta|send|message|massage|massege|msg|text|sandesh|likho|likh|karo|kar|kardo|do|de|please|plz)\\b"),
            " "
        ).trim()
        msg = msg.replace(Regex("(भेजो|भेज|बोलो|बोल|कहो|लिखो|लिख|करो|कर|संदेश|मैसेज|मेसेज)"), " ").trim()
        if (contact.isBlank()) return null
        if (msg == "kuch" || msg == "kuchh" || msg == "कुछ") msg = ""
        return Pair(contact, msg)
    }

    private fun looksLikeRequest(msg: String): Boolean {
        val m = msg.lowercase()
        val keys = listOf(
            "application", "aavedan", "chutti", "chhutti", "leave", "bimar", "beemar", "sick", "fever",
            "doctor", "school", "teacher", "class", "nahi aa", "nahi aau", "half day", "holiday",
            "principal", "exam", "function", "permission"
        )
        return keys.any { m.contains(it) } || msg.length > 45
    }

    /** Gemini se poora sahi message likhwata hai (naam/class ke saath). */
    private fun composeMessage(contact: String, raw: String): String {
        val sp = getSharedPreferences("mitra", MODE_PRIVATE)
        val key = sp.getString("gemini_key", "")?.trim() ?: ""
        if (key.isBlank()) return raw
        val name = sp.getString("my_name", "")?.trim() ?: ""
        val cls = sp.getString("my_class", "")?.trim() ?: ""
        val who = if (name.isBlank()) "" else "The sender's name is $name" + (if (cls.isBlank()) "" else " (Class $cls)") + ". "
        val prompt = "Write ONE short, polite WhatsApp message in simple Hinglish (Roman letters). " +
            who + "Recipient name: $contact. " +
            "What the sender wants to convey: \"$raw\". " +
            "Write it as a complete, natural message the recipient will fully understand " +
            "(for example a proper leave application if they are asking for leave). " +
            "2 to 4 short lines. No quotes, no explanation, no subject line, no emojis."
        val (ok, txt) = Gemini.ask(key, prompt)
        return if (ok && txt.isNotBlank()) txt.trim() else raw
    }

    private fun looksLikeAction(t: String): Boolean {
        return t.startsWith("type ") || t.contains(" type ") || t.contains("paste") ||
            t.contains("click ") || t.contains("chipka") || t.contains("likho") ||
            t.contains("screenshot")
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

    private fun resolveApp(t: String, n: String): Pair<String, String>? {
        val known = listOf(
            Triple("youtube", "com.google.android.youtube", "YouTube"),
            Triple("instagram", "com.instagram.android", "Instagram"),
            Triple("gmail", "com.google.android.gm", "Gmail"),
            Triple("chrome", "com.android.chrome", "Chrome"),
            Triple("spotify", "com.spotify.music", "Spotify"),
            Triple("maps", "com.google.android.apps.maps", "Maps"),
            Triple("settings", "com.android.settings", "Settings"),
            Triple("gallery", "com.android.gallery3d", "Gallery"),
            Triple("photos", "com.google.android.apps.photos", "Photos"),
            Triple("telegram", "org.telegram.messenger", "Telegram"),
            Triple("facebook", "com.facebook.katana", "Facebook")
        )
        for ((key, pkg, name) in known) {
            if (n.contains(key)) return Pair(pkg, name)
        }
        if (n.contains("whatsapp")) return Pair("com.whatsapp", "WhatsApp")
        val m = Regex("(?:open|kholo|khol|launch|start|chalu)\\s+([a-z0-9 ]+)").find(t)
        if (m != null) {
            val word = m.groupValues[1]
                .replace(Regex("\\b(karo|kar|do|de|please|app|application)\\b"), "")
                .trim()
            if (word.isNotBlank()) {
                val found = findInstalled(word)
                if (found != null) return Pair(found.first, found.second)
                return Pair("https://www.google.com/search?q=" + android.net.Uri.encode(word), word)
            }
        }
        return null
    }

    private fun buildAppIndex() {
        if (appIndex != null) return
        try {
            val pm = packageManager
            val list = ArrayList<Triple<String, String, String>>()
            for (a in pm.getInstalledApplications(0)) {
                val label = pm.getApplicationLabel(a).toString()
                if (pm.getLaunchIntentForPackage(a.packageName) != null) {
                    list.add(Triple(label.replace(" ", "").lowercase(), label, a.packageName))
                }
            }
            appIndex = list
        } catch (e: Exception) {}
    }

    private fun findInstalled(q: String): Pair<String, String>? {
        val idx = appIndex
        if (idx == null) {
            Thread { buildAppIndex() }.start()
            return null
        }
        val target = q.replace(" ", "").lowercase()
        for (t in idx) {
            if (t.first.contains(target)) return Pair(t.third, t.second)
        }
        return null
    }

    private fun openTarget(target: String, name: String) {
        val acc = MitraAccessibilityService.instance
        if (acc == null) {
            setMood(R.drawable.char_sad, "Pehle Accessibility ON karo: Settings > Accessibility > Bublu > ON")
            return
        }
        setMood(R.drawable.char_think, "$name khol raha hoon...")
        Thread {
            if (target.startsWith("http")) {
                val ok = acc.openUrl(target)
                main.post { if (ok) setMood(R.drawable.char_laugh, "Ye lo!") else setMood(R.drawable.char_sad, "Nahi khula") }
                return@Thread
            }
            val res = acc.openAppAndCheck(target, name)
            if (res == target) {
                main.post { setMood(R.drawable.char_laugh, "Ye lo: $name!") }
                return@Thread
            }
            // Doosra tareeka: URL se kholo
            main.post { setMood(R.drawable.char_think, "App se nahi khula, URL se koshish kar raha hoon...") }
            val url = appUrls[target]
                ?: ("https://www.google.com/search?q=" + android.net.Uri.encode(name))
            val ok2 = acc.openUrl(url)
            main.post {
                when {
                    ok2 -> setMood(R.drawable.char_laugh, "$name link se khol diya!")
                    res == "LAUNCH_FAIL" -> setMood(R.drawable.char_sad, "$name nahi mila. App installed hai? Naam sahi hai?")
                    else -> setMood(R.drawable.char_sad, "$name nahi khula - Settings > Apps > Bublu > Autostart ON karo")
                }
            }
        }.start()
    }

    /** Jo command samajh na aaye, Gemini se samjho aur chalao. */
    private fun geminiBrain(userText: String) {
        if (!isOnline()) {
            setMood(
                R.drawable.char_sad,
                "Internet nahi hai. Abhi alarm, timer, call, torch, notes aur WhatsApp kaam karenge.",
                8000
            )
            return
        }
        val key = getSharedPreferences("mitra", MODE_PRIVATE).getString("gemini_key", "")?.trim() ?: ""
        if (key.isBlank()) {
            setMood(R.drawable.char_think, "\"$userText\" - ye mujhe samajh nahi aaya. (Gemini key daalo to main samajh jaunga)")
            return
        }
        setMood(R.drawable.char_think, "Thinking...")
        Thread {
            val (ok, reply) = Gemini.ask(key, BRAIN_PROMPT + "\nUser said: " + userText)
            main.post {
                if (!ok) { setMood(R.drawable.char_sad, "Samajh nahi paya"); return@post }
                val line = reply.trim().lineSequence().firstOrNull()?.trim() ?: ""
                if (!executeParsed(line, userText)) {
                    setMood(R.drawable.char_happy, reply.take(240))
                }
            }
        }.start()
    }

    private fun executeParsed(line: String, original: String): Boolean {
        val up = line.trim()
        val sp = up.indexOf(' ')
        val cmd = (if (sp > 0) up.substring(0, sp) else up).uppercase()
        val arg = if (sp > 0) up.substring(sp + 1).trim() else ""
        val acc = MitraAccessibilityService.instance
        return when (cmd) {
            "OPEN" -> {
                if (arg.isBlank()) false
                else {
                    val f = findInstalled(arg)
                    if (f != null) rollAround { openTarget(f.first, f.second) }
                    else rollAround { openTarget("https://www.google.com/search?q=" + android.net.Uri.encode(arg), arg) }
                    true
                }
            }
            "CLICK" -> {
                if (acc == null || arg.isBlank()) false
                else {
                    val c = acc.centerOfText(arg)
                    if (c != null) glideAndDo(c[0], c[1], "Click kar diya!") { acc.clickByText(arg) }
                    else setMood(R.drawable.char_sad, "\"$arg\" nahi mila")
                    true
                }
            }
            "TYPE" -> {
                if (acc == null || arg.isBlank()) false
                else {
                    val c = acc.centerOfFocusedEditable()
                    if (c != null) glideAndDo(c[0], c[1], "Type kar diya!") { acc.typeText(arg) }
                    else setMood(R.drawable.char_sad, "Koi input box nahi mila")
                    true
                }
            }
            "SCROLL_DOWN" -> { acc?.let { a -> nudgeScroll { a.scrollDown() } }; setMood(R.drawable.char_happy, "Neeche"); true }
            "SCROLL_UP" -> { acc?.let { a -> nudgeScroll { a.scrollUp() } }; setMood(R.drawable.char_happy, "Upar"); true }
            "NEXT" -> { acc?.let { a -> nudgeScroll { a.swipeUp() } }; setMood(R.drawable.char_happy, "Agla"); true }
            "PREV" -> { acc?.let { a -> nudgeScroll { a.swipeDown() } }; setMood(R.drawable.char_happy, "Pichla"); true }
            "SEARCH" -> { openUrl("https://www.google.com/search?q=" + android.net.Uri.encode(arg)); setMood(R.drawable.char_happy, "Search kar raha hoon"); true }
            "ANSWER" -> { askAI(if (arg.isNotBlank()) arg else original); true }
            "WHATSAPP" -> {
                val p = arg.split("|", limit = 2)
                if (p.size == 2) { rollAround { doSendWhatsApp(p[0].trim(), p[1].trim()) }; true } else false
            }
            "EMAIL" -> {
                val p = arg.split("|", limit = 2)
                if (p.size == 2) { gmailTo = cleanEmail(p[0].trim()); gmailTopic = p[1].trim(); rollAround { doGmail() }; true } else false
            }
            "NOTE" -> { if (arg.isBlank()) false else { rollAround { createNote(arg) }; true } }
            "SCREENSHOT" -> { acc?.let { a -> rollAround { a.screenshot() } }; setMood(R.drawable.char_happy, "Screenshot le liya"); true }
            "BACK" -> { acc?.back(); setMood(R.drawable.char_happy, "Peeche"); true }
            "HOME" -> { acc?.home(); setMood(R.drawable.char_happy, "Home"); true }
            "SLEEP" -> { sleep(); true }
            "WAKE" -> { wakeUp(); true }
            "DANCE" -> { dance(); true }
            else -> false
        }
    }

    // ---------- Alarm / Reminder / Event helpers ----------
    private fun parseTime(t: String): Triple<Int, Int, Boolean>? {
        val m = Regex("(\\d{1,2})[:.](\\d{2})").find(t)
        val h: Int
        val mi: Int
        if (m != null) {
            h = m.groupValues[1].toIntOrNull() ?: return null
            mi = m.groupValues[2].toIntOrNull() ?: 0
        } else {
            val m2 = Regex("(\\d{1,2})").find(t) ?: return null
            h = m2.groupValues[1].toIntOrNull() ?: return null
            mi = 0
        }
        val ap = t.contains("am") || t.contains("pm") || t.contains("subah") || t.contains("shaam") ||
            t.contains("sham") || t.contains("raat") || t.contains("dopahar")
        return Triple(h, mi, ap)
    }

    private fun to24(h: Int, t: String, ap: Boolean): Int {
        if (ap) {
            val pm = t.contains("pm") || t.contains("shaam") || t.contains("sham") || t.contains("raat") || t.contains("dopahar")
            return if (pm) ((h % 12) + 12) else (h % 12)
        }
        return if (h in 0..23) h else 7
    }

    private fun handleAlarm(t: String) {
        val tm = parseTime(t)
        if (tm == null) { setMood(R.drawable.char_think, "Kitne baje ka alarm? Jaise '7 baje ka alarm'"); return }
        val h = tm.first
        val mi = tm.second
        if (!tm.third && h in 1..12) {
            pendingAlarmH = h
            pendingAlarmM = mi
            setMood(R.drawable.char_think, "$h:%02d - subah (AM) ya shaam (PM)? Bolo.".format(mi))
            return
        }
        fireAlarm(to24(h, t, tm.third), mi)
    }

    private fun fireAlarm(h24: Int, minute: Int) {
        try {
            val i = Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(android.provider.AlarmClock.EXTRA_HOUR, h24)
                putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
                putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, "Bublu alarm")
            }
            val ok = MitraAccessibilityService.instance?.openIntent(i) ?: false
            val cal = java.util.Calendar.getInstance()
            cal.set(java.util.Calendar.HOUR_OF_DAY, h24)
            cal.set(java.util.Calendar.MINUTE, minute)
            cal.set(java.util.Calendar.SECOND, 0)
            if (cal.timeInMillis < System.currentTimeMillis()) cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
            Store.addReminder(this, (System.currentTimeMillis() % 100000).toInt(), cal.timeInMillis, "Alarm %02d:%02d".format(h24, minute))
            val ampm = if (h24 < 12) "AM" else "PM"
            val h12 = if (h24 % 12 == 0) 12 else h24 % 12
            if (ok) setMood(R.drawable.char_laugh, "Alarm set: $h12:%02d $ampm".format(minute))
            else setMood(R.drawable.char_sad, "Alarm app nahi khula - Accessibility ON karo")
        } catch (e: Exception) { setMood(R.drawable.char_sad, "Alarm set nahi ho paya") }
    }

    private fun handleReminder(t: String) {
        val tm = parseTime(t)
        var text = t.replace(
            Regex("\\b(yaad|dilana|dila|remind|reminder|schedule|karo|kar|ka|ki|ke|me|par|pe|baje|bajkar|at|aaj|kal|parso|please|set|alarm)\\b"), " "
        ).replace(Regex("\\s+"), " ").trim()
        if (text.isBlank()) text = "Yaad dilana"
        if (tm == null) { setMood(R.drawable.char_think, "Kis time yaad dilana? Jaise '5 baje meeting yaad dilana'"); return }
        val h = tm.first
        val mi = tm.second
        if (!tm.third && h in 1..12) {
            pendingRemH = h
            pendingRemM = mi
            pendingRemText = text
            setMood(R.drawable.char_think, "Subah (AM) ya shaam (PM)?")
            return
        }
        scheduleReminder(text, to24(h, t, tm.third), mi, t)
    }

    private fun scheduleReminder(text: String, h24: Int, minute: Int, t: String) {
        try {
            val cal = java.util.Calendar.getInstance()
            if (t.contains("kal")) cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
            else if (t.contains("parso")) cal.add(java.util.Calendar.DAY_OF_YEAR, 2)
            cal.set(java.util.Calendar.HOUR_OF_DAY, h24)
            cal.set(java.util.Calendar.MINUTE, minute)
            cal.set(java.util.Calendar.SECOND, 0)
            if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
            val id = (System.currentTimeMillis() % 100000).toInt()
            Store.addReminder(this, id, cal.timeInMillis, text)
            val am = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val i = Intent(this, ReminderReceiver::class.java).putExtra("text", text)
            val pi = android.app.PendingIntent.getBroadcast(this, id, i, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            try {
                if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())
                    am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
                else am.set(android.app.AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
            } catch (e: Exception) {
                am.set(android.app.AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
            }
            val ampm = if (h24 < 12) "AM" else "PM"
            val h12 = if (h24 % 12 == 0) 12 else h24 % 12
            setMood(R.drawable.char_laugh, "Yaad dila dunga: $text - $h12:%02d $ampm".format(minute))
        } catch (e: Exception) { setMood(R.drawable.char_sad, "Reminder set nahi ho paya") }
    }

    private fun monthNum(s: String): Int = when (s.take(3)) {
        "jan" -> 1; "feb" -> 2; "mar" -> 3; "apr" -> 4; "may" -> 5; "jun" -> 6
        "jul" -> 7; "aug" -> 8; "sep" -> 9; "oct" -> 10; "nov" -> 11; "dec" -> 12; else -> 1
    }

    private fun hasMonthDate(t: String): Boolean =
        Regex("(\\d{1,2})\\s*(january|february|march|april|may|june|july|august|september|october|november|december|jan|feb|mar|apr|jun|jul|aug|sep|oct|nov|dec)").containsMatchIn(t)

    private fun hasEventWord(n: String): Boolean =
        n.contains("birthday") || n.contains("janamdin") || n.contains("anniversary") || n.contains("saalgirah") ||
            n.contains("engagement") || n.contains("wedding") || n.contains("shaadi") || n.contains("shadi") ||
            n.contains("exam") || n.contains("function") || n.contains("party") || n.contains("meeting") ||
            n.contains("interview") || n.contains("trip") || n.contains("program")

    private fun eventTypeOf(n: String): String = when {
        n.contains("engagement") -> "Engagement"
        n.contains("anniversary") || n.contains("saalgirah") -> "Anniversary"
        n.contains("birthday") || n.contains("janamdin") -> "Birthday"
        n.contains("wedding") || n.contains("shaadi") || n.contains("shadi") -> "Wedding"
        n.contains("exam") -> "Exam"
        n.contains("interview") -> "Interview"
        n.contains("function") || n.contains("program") -> "Function"
        n.contains("party") -> "Party"
        n.contains("meeting") -> "Meeting"
        n.contains("trip") -> "Trip"
        else -> "Event"
    }

    private fun handleEvent(t: String) {
        val n = t.lowercase()
        val type = eventTypeOf(n)
        var name = ""
        Regex("([a-z]+)\\s+(?:ki|ka)\\b").find(n)?.let { name = it.groupValues[1] }
        if (name in listOf("meri", "mera", "mere", "ko", "hai", "ka", "ki", "ke", "kya")) name = ""
        val dm = Regex("(\\d{1,2})\\s*(january|february|march|april|may|june|july|august|september|october|november|december|jan|feb|mar|apr|jun|jul|aug|sep|oct|nov|dec)").find(n)
        if (dm == null) { setMood(R.drawable.char_think, "Kis date? Jaise 'didi ki 11 November ko engagement hai'"); return }
        val day = dm.groupValues[1].toIntOrNull() ?: return
        val monName = dm.groupValues[2]
        val mon = monthNum(monName)
        if (name.isBlank()) name = "Koi"
        Store.addEvent(this, name.replaceFirstChar { it.uppercase() }, "%02d/%02d".format(day, mon), type)
        setMood(R.drawable.char_laugh, "$name ka $type $day $monName set! Us din yaad dila dunga.")
    }

    private fun parseAskInApp(t: String): Triple<String, String, String>? {
        val apps = listOf(
            Triple("chatgpt", "com.openai.chatgpt", "ChatGPT"),
            Triple("chat gpt", "com.openai.chatgpt", "ChatGPT"),
            Triple("gemini", "com.google.android.apps.bard", "Gemini"),
            Triple("telegram", "org.telegram.messenger", "Telegram")
        )
        for ((key, pkg, name) in apps) {
            if (t.contains(key)) {
                val q = extractQuestion(t)
                if (q != null) return Triple(pkg, name, q)
            }
        }
        return null
    }

    private fun extractQuestion(t: String): String? {
        for (k in listOf(" se ", " me ", " par ", " pe ")) {
            val i = t.indexOf(k)
            if (i >= 0) {
                var q = t.substring(i + k.length).trim()
                q = q.replace(Regex("\\b(ye|ki|poocho|pucho|puch|likho|likh|bhejo|bhej|send|karo|kar|enter|daba|dabao|please)\\b"), " ").replace(Regex("\\s+"), " ").trim()
                if (q.isNotBlank()) return q
            }
        }
        return null
    }

    private fun parseInstall(t: String): String? {
        if (!(t.contains("install") || t.contains("download") || t.contains("play store") || t.contains("playstore"))) return null
        var s = t.replace(Regex("(play ?store|playstore)"), " ")
        s = s.replace(Regex("\\b(install|download|karo|kar|kardo|se|me|par|pe|the|app|please|play|store)\\b"), " ")
        s = s.replace(Regex("\\s+"), " ").trim()
        return if (s.isNotBlank()) s else null
    }

    private fun proactiveGreet() {
        // Agar user bol raha hai ya kaam chal raha hai to greet mat karo
        if (listening || busy || alwaysListen) {
            main.postDelayed({ proactiveGreet() }, 10000)
            return
        }
        try {
            val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            val greet = when {
                h < 12 -> "Good morning!"
                h < 17 -> "Good afternoon!"
                h < 21 -> "Good evening!"
                else -> "Good night!"
            }
            val sp = getSharedPreferences("mitra", MODE_PRIVATE)
            val todayKey = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault()).format(java.util.Date())
            if (sp.getString("last_greet_date", "") == todayKey) {
                // Aaj pehle bata chuka hoon - sirf greeting, dobara date/tyohar nahi
                setMood(R.drawable.char_happy, greet)
                return
            }
            sp.edit().putString("last_greet_date", todayKey).apply()
            val day = java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault()).format(java.util.Date())
            val date = java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale.getDefault()).format(java.util.Date())
            val today = java.text.SimpleDateFormat("dd/MM", java.util.Locale.getDefault()).format(java.util.Date())
            val bdays = Store.events(this).filter { it.second == today }
            var extra = ""
            if (bdays.isNotEmpty()) extra = " Aaj " + bdays.joinToString(", ") { "${it.first} ka ${it.third}" } + " hai!"
            setMood(R.drawable.char_happy, "$greet Aaj $day, $date hai.$extra", 8000)
            val key = getSharedPreferences("mitra", MODE_PRIVATE).getString("gemini_key", "")?.trim() ?: ""
            if (key.isNotBlank() && sp.getBoolean("festival_on", true)) {
                val shownAt = System.currentTimeMillis()
                Thread {
                    val (ok, txt) = Gemini.ask(
                        key,
                        "Today's date is $date. Is today EXACTLY a major Indian festival (Diwali, Holi, Eid, Raksha Bandhan, etc.)? " +
                            "Answer in this exact format: 'YES <festival name>' or 'NO'. If you are not 100% sure, answer 'NO'. Never guess."
                    )
                    val t = txt.trim()
                    if (ok && t.uppercase().startsWith("YES")) {
                        val name = t.substring(3).trim().trim('.', '!')
                        if (name.isNotBlank()) {
                            // date ko poora padhne do, phir tyohar bolo
                            val wait = 5500 - (System.currentTimeMillis() - shownAt)
                            if (wait > 0) Thread.sleep(wait)
                            main.post { setMood(R.drawable.char_happy, "Aur aaj $name hai!", 6000) }
                        }
                    }
                }.start()
            }
        } catch (e: Exception) {}
    }

    private fun resolveAlias(name: String): String {
        val nm = name.trim()
        if (nm.isBlank()) return nm
        val words = nm.lowercase().split(" ")
        val a = Store.aliases(this).firstOrNull {
            it.first.equals(nm, true) || words.contains(it.first.lowercase())
        }
        return a?.second ?: nm
    }

    private fun handleAlias(t: String): Boolean {
        var m = Regex("(.+?)\\s+ka\\s+naam\\s+(.+)").find(t)
        if (m != null) { setAlias(m.groupValues[1], m.groupValues[2]); return true }
        m = Regex("(.+?)\\s+ko\\s+(.+?)\\s+(?:bulao|bulana|bula)").find(t)
        if (m != null) { setAlias(m.groupValues[1], m.groupValues[2]); return true }
        m = Regex("(.+?)\\s+ka\\s+number\\s+(.+)").find(t)
        if (m != null) { setAlias(m.groupValues[1], m.groupValues[2]); return true }
        m = Regex("(.+?)\\s+ke\\s+liye\\s+(.+?)\\s+(?:save|set|rakho|add)").find(t)
        if (m != null) { setAlias(m.groupValues[1], m.groupValues[2]); return true }
        return false
    }

    private fun setAlias(nickRaw: String, targetRaw: String) {
        val nick = nickRaw.replace(Regex("\\b(ka|ki|ke|ko|naam|number|save|rakho|rakh|set|add|karo|kar|please|liye|bulao)\\b"), " ").replace(Regex("\\s+"), " ").trim()
        val target = targetRaw.replace(Regex("\\b(save|rakho|rakh|set|add|karo|kar|please|bulao|bulana|ka|ki|ke|naam|number)\\b"), " ").replace(Regex("\\s+"), " ").trim()
        if (nick.isBlank() || target.isBlank()) { setMood(R.drawable.char_sad, "Alias samajh nahi aaya. Jaise 'bhaiya ka naam Ravi rakho'"); return }
        Store.addAlias(this, nick, target)
        setMood(R.drawable.char_laugh, "Samajh gaya! '$nick' = '$target'")
    }

    private fun callContact(t: String) {
        val name = t.replace(Regex("\\b(call|dial|phone|lagao|lagado|karo|kar|ko|se|baat|please|ki|ka|ke|liye|mila|kro)\\b"), " ").replace(Regex("\\s+"), " ").trim()
        val target = resolveAlias(name)
        val num = when {
            target.matches(Regex("\\+?\\d[\\d ]{5,}")) -> target.replace(" ", "")
            target.isBlank() -> null
            else -> Contacts.numberFor(this, target)
        }
        if (num != null) {
            val canCall = checkSelfPermission(android.Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val i = if (canCall) Intent(Intent.ACTION_CALL, android.net.Uri.parse("tel:$num"))
            else Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:$num"))
            val ok = MitraAccessibilityService.instance?.openIntent(i) ?: false
            setMood(if (ok) R.drawable.char_happy else R.drawable.char_sad, if (ok) "$target ko call kar raha hoon" else "Call nahi khula")
        } else if (target.isBlank()) {
            val i = Intent(Intent.ACTION_DIAL)
            MitraAccessibilityService.instance?.openIntent(i)
            setMood(R.drawable.char_happy, "Dialer khol diya")
        } else {
            setMood(R.drawable.char_sad, "$target ka number nahi mila. Bolo: '$target ka number 98xxxx save karo'")
        }
    }

    private fun showGroupMessage(title: String, text: String) {
        main.post { setMood(R.drawable.char_laugh, "$title: $text") }
    }

    private fun stripNoteWords(s: String): String = s.replace(
        Regex("\\b(ki story|ka story|ki notes|ka notes|story|notes|note|keep|ke baare me|ke bare me|about|ki|ka|ke|me|jaake|jaa ke|likho|likh|paste|save|karo|kar|do|de|please|app|application|meri|mera|banao|bana|the|a|an)\\b"), " "
    ).replace(Regex("\\s+"), " ").trim()

    /** Topic nikaalo - "notes me X likho" aur "X notes me likho" dono handle. */
    private fun extractNoteTopic(t: String): String {
        val lower = t.lowercase()
        val keys = listOf("notes app me", "note app me", "notes me", "note me", "keep me", "notes", "note", "keep")
        for (k in keys) {
            val i = lower.indexOf(k)
            if (i < 0) continue
            val before = stripNoteWords(t.substring(0, i).trim())
            val after = stripNoteWords(t.substring(i + k.length).trim())
            if (before.length >= 4) return before
            if (after.length >= 4) return after
        }
        return ""
    }

    /** Gemini se padhne layak notes banao. */
    private fun generateContent(topic: String): String {
        val key = getSharedPreferences("mitra", MODE_PRIVATE).getString("gemini_key", "")?.trim() ?: ""
        if (key.isBlank()) return topic
        val prompt = "Write clean, well-organised study NOTES on: \"$topic\". " +
            "Use short headings and bullet points. Simple English (Hinglish if the topic is Hindi). " +
            "Useful for a school student. No intro line, no extra talk, max 250 words."
        val (ok, txt) = Gemini.ask(key, prompt)
        return if (ok && txt.isNotBlank()) txt.trim() else topic
    }

    private fun parsePhotoContact(t: String): String? {
        if (!(t.contains(" bhej") || t.contains("send"))) return null
        val m = Regex("(.+?)\\s+ko\\s+(?:bhej|bhejo|send|whatsapp)").find(t) ?: return null
        var c = m.groupValues[1]
        c = c.replace(Regex("\\b(meri|mera|mere|photo|selfie|tasveer|kheech|kheecho|kheench|khich|camera|se|le|lo|ek|aur|please|whatsapp|wa|pe|par|bhi)\\b"), " ").replace(Regex("\\s+"), " ").trim()
        if (c.isBlank()) return null
        return resolveAlias(c)
    }

    private fun saveNote(text: String) {
        Store.addNote(this, text)
        setMood(R.drawable.char_laugh, "Note save kar diya!")
    }

    private fun readNotes() {
        val ns = Store.notes(this)
        if (ns.isEmpty()) setMood(R.drawable.char_happy, "Koi note nahi hai")
        else setMood(R.drawable.char_happy, "Notes: " + ns.takeLast(3).joinToString("  |  "))
    }

    private fun addTask(text: String) {
        Store.addTask(this, text)
        setMood(R.drawable.char_laugh, "Task add kar diya: $text")
    }

    private fun listTasks() {
        val ts = Store.tasks(this).filter { !it.second }
        if (ts.isEmpty()) setMood(R.drawable.char_happy, "Koi pending task nahi")
        else setMood(R.drawable.char_happy, "Tasks: " + ts.take(5).joinToString(", ") { it.first })
    }

    private fun looksLikeMath(t: String, n: String): Boolean {
        val words = n.contains("plus") || n.contains("minus") || n.contains("into") || n.contains("dividedby") ||
            n.contains("multiply") || n.contains("times") || n.contains("jod") || n.contains("ghata")
        val ops = t.contains("+") || t.contains("*") || t.contains("/")
        return t.any { it.isDigit() } && (words || ops)
    }

    private fun calcAnswer(t: String): String? {
        var s = t.lowercase()
        s = s.replace("plus", "+").replace("jod", "+")
        s = s.replace("minus", "-").replace("ghata", "-")
        s = s.replace("multiplied by", "*").replace("multiply", "*").replace("into", "*").replace("times", "*")
        s = s.replace("divided by", "/").replace("divide", "/")
        val clean = s.filter { it.isDigit() || it == '+' || it == '-' || it == '*' || it == '/' || it == '(' || it == ')' || it == '.' }
        if (clean.count { it.isDigit() } == 0) return null
        val v = Calc.eval(clean) ?: return null
        return if (v == Math.floor(v) && !v.isInfinite() && Math.abs(v) < 1e15) v.toLong().toString()
        else "%.2f".format(v)
    }

    private fun flashlight(on: Boolean) {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (id == null) { setMood(R.drawable.char_sad, "Flashlight nahi mila"); return }
            cm.setTorchMode(id, on)
            setMood(R.drawable.char_happy, if (on) "Flashlight ON" else "Flashlight OFF")
        } catch (e: Exception) { setMood(R.drawable.char_sad, "Flashlight nahi mila") }
    }

    private fun volume(up: Boolean) {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            am.adjustStreamVolume(
                android.media.AudioManager.STREAM_MUSIC,
                if (up) android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER,
                android.media.AudioManager.FLAG_SHOW_UI
            )
            setMood(R.drawable.char_happy, if (up) "Volume badha diya" else "Volume kam kar diya")
        } catch (e: Exception) {}
    }

    private fun muteVolume() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_MUTE, android.media.AudioManager.FLAG_SHOW_UI)
            setMood(R.drawable.char_happy, "Mute kar diya")
        } catch (e: Exception) {}
    }

    private fun dial(t: String) {
        val num = Regex("(\\+?\\d[\\d ]{5,})").find(t)?.groupValues?.get(1)?.replace(" ", "")
        val i = if (num != null) Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:$num")) else Intent(Intent.ACTION_DIAL)
        val ok = MitraAccessibilityService.instance?.openIntent(i) ?: false
        setMood(if (ok) R.drawable.char_happy else R.drawable.char_sad, if (ok) "Dialer khol diya" else "Dialer nahi khula")
    }

    private fun askAI(prompt: String) {
        val key = getSharedPreferences("mitra", MODE_PRIVATE)
            .getString("gemini_key", "")?.trim() ?: ""
        if (key.isBlank()) {
            setMood(R.drawable.char_think, "\"$prompt\" - AI ke liye app me Gemini key daalo")
            return
        }
        setMood(R.drawable.char_think, "Thinking...")
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
        live = null
        alwaysListen = false
        try { unregisterReceiver(batteryReceiver) } catch (e: Exception) {}
        sleeping = false
        stopSleepMonitor()
        try { tts?.shutdown() } catch (e: Exception) {}
        try { recognizer?.destroy() } catch (e: Exception) {}
        try { player?.release() } catch (e: Exception) {}
        try { face.stopEyes() } catch (e: Exception) {}
        try { wm.removeView(root) } catch (e: Exception) {}
        try { wm.removeView(fx) } catch (e: Exception) {}
    }
}
