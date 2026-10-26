package com.mitra.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayList
import java.util.HashSet

/**
 * Bublu ko doosri apps me click / type / paste / screenshot / app kholne deti hai.
 */
class MitraAccessibilityService : AccessibilityService() {

    companion object {
        var instance: MitraAccessibilityService? = null
        fun isReady(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    @Volatile var currentPkg: String? = null
    @Volatile var currentLabel: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                val p = event.packageName?.toString() ?: return
                if (p == packageName) return
                if (p == currentPkg) return
                currentPkg = p
                currentLabel = try {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString()
                } catch (e: Exception) { p }
            }
        } catch (e: Exception) {}
    }
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // ---------- Type ----------
    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root) ?: return false
        val existing = target.text?.toString() ?: ""
        val newText = if (existing.isBlank()) text else "$existing $text"
        return setNodeText(target, newText)
    }

    /** Notes app me likhne ke liye: sahi (bada) input box dhoondho, likho, phir verify karo. */
    fun writeNote(text: String): Boolean {
        return try {
            val root = rootInActiveWindow ?: return false
            val dm = resources.displayMetrics
            var target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (target == null || !target.isEditable) {
                val all = ArrayList<AccessibilityNodeInfo>()
                collectEditables(root, all)
                target = all.filter {
                    val r = Rect(); it.getBoundsInScreen(r)
                    r.top > (dm.heightPixels * 0.12f).toInt() && r.width() > (dm.widthPixels * 0.35f).toInt()
                }.maxByOrNull { val r = Rect(); it.getBoundsInScreen(r); r.width().toLong() * r.height() }
            }
            if (target == null) return false
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Thread.sleep(450)
            val all2 = ArrayList<AccessibilityNodeInfo>()
            rootInActiveWindow?.let { collectEditables(it, all2) }
            var got = all2.any { it.text?.toString()?.contains(text.take(5)) == true }
            if (!ok && !got) {
                // set-text fail hua to clipboard paste try karo
                setTextViaPaste(target, text)
                Thread.sleep(350)
                val all3 = ArrayList<AccessibilityNodeInfo>()
                rootInActiveWindow?.let { collectEditables(it, all3) }
                got = all3.any { it.text?.toString()?.contains(text.take(5)) == true }
            }
            ok || got
        } catch (e: Exception) { false }
    }

    fun setTextFocused(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root) ?: return false
        return setNodeText(target, text)
    }

    fun setNodeTextPub(node: AccessibilityNodeInfo, text: String): Boolean = setNodeText(node, text)

    private fun setNodeText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    // ---------- Paste ----------
    fun paste(): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root) ?: return false
        return target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    // ---------- Smart target finding ----------
    private fun findClickTarget(query: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val q = query.lowercase().trim()
        if (q.isEmpty()) return null
        val matches = ArrayList<Pair<AccessibilityNodeInfo, Int>>()
        collect(root, q, matches)
        if (matches.isEmpty()) return null
        matches.sortBy { it.second }
        return matches.first().first
    }

    private fun collect(
        node: AccessibilityNodeInfo,
        q: String,
        out: ArrayList<Pair<AccessibilityNodeInfo, Int>>
    ) {
        val txt = node.text?.toString()?.lowercase()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase()?.trim() ?: ""
        var score = -1
        if (txt == q || desc == q) score = 0
        else if (txt.startsWith(q) || desc.startsWith(q)) score = 2
        else if (txt.contains(q) || desc.contains(q)) score = 4
        if (score >= 0) {
            if (node.isClickable) score -= 1
            out.add(Pair(node, score))
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collect(it, q, out) }
        }
    }

    fun clickByText(text: String): Boolean {
        for (q in Buttons.expand(text)) {
            val n = findClickTarget(q) ?: continue
            if (clickNodeOrTap(n)) return true
        }
        return false
    }

    fun clickByDesc(desc: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val d = desc.lowercase()
        val n = findNode(root) { it.contentDescription?.toString()?.lowercase()?.contains(d) == true } ?: return false
        return clickNodeOrTap(n)
    }

    private fun clickNode(n: AccessibilityNodeInfo): Boolean {
        var node: AccessibilityNodeInfo? = n
        var depth = 0
        while (node != null && depth < 8) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            node = node.parent
            depth++
        }
        return false
    }

    /** Click karo; agar clickable ancestor na mile to us jagah seedha tap karo. */
    private fun clickNodeOrTap(n: AccessibilityNodeInfo): Boolean {
        if (clickNode(n)) return true
        val r = Rect()
        n.getBoundsInScreen(r)
        if (r.width() > 0 && r.height() > 0) {
            tap(r.centerX(), r.centerY())
            return true
        }
        return false
    }

    // ---------- Location ----------
    fun centerOfText(text: String): IntArray? {
        val n = findClickTarget(text) ?: return null
        return centerOf(n)
    }

    fun centerOfFocusedEditable(): IntArray? {
        val root = rootInActiveWindow ?: return null
        val n = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root) ?: return null
        return centerOf(n)
    }

    private fun centerOf(n: AccessibilityNodeInfo): IntArray? {
        val r = Rect()
        n.getBoundsInScreen(r)
        if (r.width() <= 0 || r.height() <= 0) return null
        return intArrayOf(r.centerX(), r.centerY())
    }

    // ---------- Global ----------
    fun back() { performGlobalAction(GLOBAL_ACTION_BACK) }
    fun home() { performGlobalAction(GLOBAL_ACTION_HOME) }
    fun recents() { performGlobalAction(GLOBAL_ACTION_RECENTS) }
    fun notifications() { performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) }

    fun screenshot(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            } else false
        } catch (e: Exception) {
            false
        }
    }

    // ---------- Touch gesture (status aage/peeche) ----------
    fun tap(x: Int, y: Int) {
        try {
            val path = Path()
            path.moveTo(x.toFloat(), y.toFloat())
            val stroke = GestureDescription.StrokeDescription(path, 0, 60)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {}
    }

    fun tapRight() {
        val dm = resources.displayMetrics
        tap((dm.widthPixels * 0.85f).toInt(), (dm.heightPixels * 0.5f).toInt())
    }

    fun tapLeft() {
        val dm = resources.displayMetrics
        tap((dm.widthPixels * 0.15f).toInt(), (dm.heightPixels * 0.5f).toInt())
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long = 260) {
        try {
            val path = Path()
            path.moveTo(x1.toFloat(), y1.toFloat())
            path.lineTo(x2.toFloat(), y2.toFloat())
            val stroke = GestureDescription.StrokeDescription(path, 0, ms)
            val g = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(g, null, null)
        } catch (e: Exception) {}
    }

    /** Poora page swipe (chhota nahi) - normal scroll ke liye. */
    fun swipeUp() {
        val dm = resources.displayMetrics
        swipe((dm.widthPixels * 0.5f).toInt(), (dm.heightPixels * 0.82f).toInt(),
            (dm.widthPixels * 0.5f).toInt(), (dm.heightPixels * 0.18f).toInt(), 220)
    }

    fun swipeDown() {
        val dm = resources.displayMetrics
        swipe((dm.widthPixels * 0.5f).toInt(), (dm.heightPixels * 0.22f).toInt(),
            (dm.widthPixels * 0.5f).toInt(), (dm.heightPixels * 0.82f).toInt(), 220)
    }

    /** Reels / Shorts: ek poora reel aage (bada aur tez swipe). */
    fun reelNext() {
        val dm = resources.displayMetrics
        swipe((dm.widthPixels * 0.5f).toInt(), (dm.heightPixels * 0.86f).toInt(),
            (dm.widthPixels * 0.5f).toInt(), (dm.heightPixels * 0.12f).toInt(), 160)
    }

    fun reelPrev() {
        val dm = resources.displayMetrics
        swipe((dm.widthPixels * 0.5f).toInt(), (dm.heightPixels * 0.16f).toInt(),
            (dm.widthPixels * 0.5f).toInt(), (dm.heightPixels * 0.86f).toInt(), 160)
    }

    // Scroll: page ho ya reel, poora swipe hi karo (chhota scroll kaam nahi karta)
    fun scrollDown() { swipeUp() }
    fun scrollUp() { swipeDown() }

    private fun scrollAction(forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val s = findNode(root) { it.isScrollable } ?: return false
        return s.performAction(
            if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        )
    }

    // ---------- Gmail compose (AI draft ke saath) ----------
    fun composeEmail(to: String, subject: String, body: String, onStatus: (String) -> Unit): Boolean {
        return try {
            onStatus("Gmail khol raha hoon...")
            val i = Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:" + android.net.Uri.encode(to)))
            i.putExtra(Intent.EXTRA_SUBJECT, subject)
            i.putExtra(Intent.EXTRA_TEXT, body)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            Thread.sleep(3000)
            onStatus("Bhej raha hoon...")
            val sent = clickByDesc("Send") || clickByText("Send")
            Thread.sleep(1000)
            home()
            sent
        } catch (e: Exception) {
            false
        }
    }

    // ---------- App / URL ----------
    fun openApp(pkg: String): Boolean {
        return try {
            val i = packageManager.getLaunchIntentForPackage(pkg) ?: return false
            i.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )
            startActivity(i)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun foregroundPackage(): String? =
        try { rootInActiveWindow?.packageName?.toString() } catch (e: Exception) { null }

    fun openAppSmart(pkg: String, name: String): Boolean {
        try {
            val i = packageManager.getLaunchIntentForPackage(pkg)
            if (i != null) {
                i.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                )
                startActivity(i)
                return true
            }
        } catch (e: Exception) {}
        try {
            val target = name.replace(" ", "").lowercase()
            for (a in packageManager.getInstalledApplications(0)) {
                val label = packageManager.getApplicationLabel(a).toString()
                if (label.replace(" ", "").lowercase().contains(target)) {
                    val i = packageManager.getLaunchIntentForPackage(a.packageName)
                    if (i != null) {
                        i.addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                        )
                        startActivity(i)
                        return true
                    }
                }
            }
        } catch (e: Exception) {}
        return false
    }

    /** returns actual foreground package, or "LAUNCH_FAIL". */
    fun openAppAndCheck(pkg: String, name: String): String {
        val before = foregroundPackage()
        if (!openAppSmart(pkg, name)) return "LAUNCH_FAIL"
        for (i in 0 until 6) {
            Thread.sleep(600)
            val cur = foregroundPackage()
            if (cur == pkg) return pkg
            if (cur != null && cur != before) return cur
        }
        return foregroundPackage() ?: ""
    }

    fun openUrl(url: String): Boolean {
        return try {
            val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun openIntent(i: Intent): Boolean {
        return try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun pressEnter(): Boolean {
        val root = rootInActiveWindow ?: return false
        val box = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root) ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                box.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            else false
        } catch (e: Exception) {
            false
        }
    }

    /** Kisi bhi app me jaake input box me likho aur send/enter kar do (jaise ChatGPT se poochna). */
    fun askInApp(pkg: String, appName: String, question: String, onStatus: (String) -> Unit): Boolean {
        return try {
            onStatus("$appName khol raha hoon...")
            if (!openAppSmart(pkg, appName)) { onStatus("$appName nahi khula"); return false }
            Thread.sleep(3800)
            onStatus("Sawaal likh raha hoon...")
            if (!setTextFocused(question)) {
                onStatus("Input box nahi mila")
                return false
            }
            Thread.sleep(900)
            onStatus("Bhej raha hoon...")
            var ok = clickByDesc("Send") || clickByText("Send")
            if (!ok) ok = pressEnter()
            Thread.sleep(1300)
            onStatus("Ho gaya!")
            home()
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Play Store me app search karke Install dabao. */
    fun playStoreInstall(appName: String, onStatus: (String) -> Unit): Boolean {
        return try {
            onStatus("Play Store khol raha hoon...")
            if (!openAppSmart("com.android.vending", "Play Store")) { onStatus("Play Store nahi khula"); return false }
            Thread.sleep(3800)
            onStatus("Search khol raha hoon...")
            if (!clickByDesc("Search") && !clickByText("Search")) {
                clickByDesc("Search apps & games")
            }
            Thread.sleep(1400)
            onStatus("$appName dhoondh raha hoon...")
            if (!setTextFocused(appName)) { onStatus("Search box nahi mila"); return false }
            Thread.sleep(2400)
            if (!openFirstChatResult(appName)) { onStatus("$appName nahi mila"); return false }
            Thread.sleep(2600)
            onStatus("Install kar raha hoon...")
            val ok = clickByText("Install") || clickByDesc("Install")
            Thread.sleep(1600)
            home()
            ok
        } catch (e: Exception) {
            false
        }
    }

    // =========================================================
    //  WhatsApp Status (Updates) — sabke status chalao
    // =========================================================
    fun isInWhatsApp(): Boolean =
        try { rootInActiveWindow?.packageName?.toString() == "com.whatsapp" } catch (e: Exception) { false }

    /** Updates tab kholo aur pehla status chalao. Return: kitne logon ne status lagaya hai (ya -1 = fail). */
    fun openStatusTabAndFirst(): Int {
        try {
            if (!openApp("com.whatsapp")) return -1
            Thread.sleep(3200)

            // Updates tab pe jao
            if (!clickByDesc("Updates") && !clickByDesc("Status")) {
                // tab na mile to bottom nav me try
                clickByText("Updates")
            }
            Thread.sleep(1600)

            val rows = statusRows()
            if (rows.isEmpty()) return 0
            val count = rows.size
            clickNode(rows.first())
            Thread.sleep(1600)
            return count
        } catch (e: Exception) {
            return -1
        }
    }

    /** Status list (Updates) par wapas aa gaye? */
    fun isOnStatusList(): Boolean {
        val root = rootInActiveWindow ?: return true
        return findNode(root) {
            it.contentDescription?.toString()?.lowercase()?.contains("updates") == true
        } != null
    }

    private fun statusRows(): ArrayList<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return ArrayList()
        val out = ArrayList<AccessibilityNodeInfo>()
        val seen = HashSet<String>()
        collectStatusRows(root, out, seen)
        return out
    }

    private fun collectStatusRows(
        node: AccessibilityNodeInfo,
        out: ArrayList<AccessibilityNodeInfo>,
        seen: HashSet<String>
    ) {
        try {
            val txt = node.text?.toString()?.lowercase()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase()?.trim() ?: ""
            val all = "$txt $desc"
            // Apna status / add status / hints hata do (text AUR description dono me dekho)
            val isMine = all.contains("my status") || all.contains("add status") ||
                all.contains("tap to") || all.contains("text status") ||
                all.contains("status update") || all.contains("my status update") ||
                all.contains("add to my status") || all.contains("my status, tap")
            if (node.isClickable && all.isNotBlank() && !isMine) {
                val r = Rect(); node.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() > 0) {
                    val key = "${r.left},${r.top},${r.right},${r.bottom}"
                    if (!seen.contains(key)) {
                        seen.add(key)
                        out.add(node)
                    }
                }
            }
        } catch (e: Exception) {}
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectStatusRows(it, out, seen) }
        }
    }

    // =========================================================
    //  WhatsApp — message bhejo
    // =========================================================
    fun sendWhatsApp(contact: String, message: String, onStatus: (String) -> Unit): Boolean {
        try {
            onStatus("WhatsApp khol raha hoon...")
            if (!openApp("com.whatsapp")) { onStatus("WhatsApp nahi khula"); return false }
            Thread.sleep(3200)

            if (isInChat()) { back(); Thread.sleep(1200) }

            // WhatsApp last-used tab pe khulta hai (Status/Calls) - pehle CHATS tab pe jao
            goToChats()
            Thread.sleep(900)

            onStatus("Looking for $contact...")
            val opened = findContactAny(contactVariants(contact), onStatus)

            if (!opened) {
                onStatus("Are! $contact mila hi nahi")
                try { back() } catch (e: Exception) {}
                return false
            }

            Thread.sleep(1500)
            if (!isInChat()) { onStatus("$contact ki chat nahi khuli"); return false }

            onStatus("Message likh raha hoon...")
            val box = findChatInput()
            if (box == null || !setNodeText(box, capitalize(message))) {
                onStatus("Message box nahi mila")
                return false
            }
            Thread.sleep(900)

            onStatus("Bhej raha hoon...")
            if (!clickByDesc("Send")) clickByText("Send")
            Thread.sleep(1300)
            var sent = boxEmptyOrGone()
            if (!sent) {
                if (!clickByDesc("Send")) clickByText("Send")
                Thread.sleep(1300)
                sent = boxEmptyOrGone()
            }
            if (!sent) sent = lastMessageIs(message)
            onStatus(if (sent) "Ho gaya! Wapas ja raha hoon..." else "Send confirm nahi hua...")

            home()
            return sent
        } catch (e: Exception) {
            return false
        }
    }

    // =========================================================
    //  Camera / Photo / Gallery
    // =========================================================
    fun openGallery(): Boolean {
        try {
            val pkgs = listOf(
                "com.google.android.apps.photos", "com.miui.gallery", "com.sec.android.gallery3d",
                "com.android.gallery3d", "com.oppo.gallery3d", "com.vivo.gallery",
                "com.coloros.gallery3d", "com.oneplus.gallery", "com.transsion.gallery"
            )
            for (p in pkgs) {
                try {
                    if (packageManager.getLaunchIntentForPackage(p) != null) {
                        openAppAndCheck(p, "Gallery")
                        return true
                    }
                } catch (e: Exception) {}
            }
            val i = Intent(Intent.ACTION_VIEW).apply { type = "image/*"; addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            startActivity(i)
            return true
        } catch (e: Exception) { return false }
    }

    fun takePhoto(onStatus: (String) -> Unit): Boolean {
        return try {
            val camPkg = listOf(
                "com.android.camera", "com.android.camera2", "com.google.android.GoogleCamera",
                "com.sec.android.app.camera", "com.miui.camera", "com.oppo.camera",
                "com.vivo.camera", "com.oneplus.camera", "com.transsion.camera"
            ).firstOrNull { p ->
                try { packageManager.getLaunchIntentForPackage(p) != null } catch (e: Exception) { false }
            }
            if (camPkg == null) { onStatus("Camera app nahi mila"); return false }
            openAppAndCheck(camPkg, "Camera")
            Thread.sleep(3000)
            onStatus("Photo khinch raha hoon...")
            cameraShutter()
            Thread.sleep(2200)
            true
        } catch (e: Exception) { false }
    }

    fun cameraShutter(): Boolean {
        try {
            val root = rootInActiveWindow
            if (root != null) {
                val n = findNode(root) {
                    val d = it.contentDescription?.toString()?.lowercase() ?: ""
                    val tx = it.text?.toString()?.lowercase() ?: ""
                    d.contains("shutter") || d.contains("take photo") || d.contains("capture") ||
                        tx.contains("shutter") || tx.contains("capture")
                }
                if (n != null && clickNodeOrTap(n)) return true
            }
        } catch (e: Exception) {}
        val dm = resources.displayMetrics
        tap((dm.widthPixels * 0.5f).toInt(), (dm.heightPixels * 0.86f).toInt())
        return true
    }

    /** WhatsApp me contact kholkar gallery se sabse nayi photo bhejta hai (best-effort). */
    fun sendLastPhotoTo(contact: String, onStatus: (String) -> Unit): Boolean {
        return try {
            onStatus("WhatsApp khol raha hoon...")
            if (!openApp("com.whatsapp")) { onStatus("WhatsApp nahi khula"); return false }
            Thread.sleep(3200)
            if (isInChat()) { back(); Thread.sleep(1000) }
            goToChats()
            Thread.sleep(800)
            var opened = findVisibleWithRetry(contact, 2)
            if (!opened) opened = findInChatList(contact, 3)
            if (!opened) opened = searchAndOpen(contact)
            if (!opened) { onStatus("$contact nahi mila"); return false }
            Thread.sleep(1500)
            onStatus("Attach kar raha hoon...")
            if (!clickByDesc("Attach") && !clickByDesc("Attachment") && !clickByText("Attach")) { clickByDesc("More options") }
            Thread.sleep(1300)
            if (!clickByText("Gallery") && !clickByDesc("Gallery")) { clickByText("Photos") }
            Thread.sleep(1900)
            val root = rootInActiveWindow
            if (root != null) {
                val imgs = ArrayList<AccessibilityNodeInfo>()
                collectPhotos(root, imgs)
                if (imgs.isNotEmpty()) clickNodeOrTap(imgs.first())
            }
            Thread.sleep(1500)
            if (!clickByDesc("Send")) clickByText("Send")
            Thread.sleep(1600)
            home()
            true
        } catch (e: Exception) { false }
    }

    private fun collectPhotos(node: AccessibilityNodeInfo, out: ArrayList<AccessibilityNodeInfo>) {
        try {
            val d = node.contentDescription?.toString()?.lowercase() ?: ""
            if (node.isClickable && (d.contains("photo") || d.contains("image") || d.contains("picture"))) out.add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let { collectPhotos(it, out) }
        } catch (e: Exception) {}
    }

    /** Focused input me likho: pehle set-text, phir clipboard paste. */
    fun typeInto(text: String): Boolean {
        return try {
            val root = rootInActiveWindow ?: return false
            val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root) ?: return false
            if (setNodeText(target, text)) return true
            setTextViaPaste(target, text)
        } catch (e: Exception) { false }
    }

    private fun setTextViaPaste(target: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("bublu", text))
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            Thread.sleep(180)
            target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            Thread.sleep(250)
            true
        } catch (e: Exception) { false }
    }

    private fun collectTexts(node: AccessibilityNodeInfo, out: ArrayList<String>) {
        try {
            val t = node.text?.toString()?.trim() ?: ""
            if (t.isNotEmpty() && !node.isEditable) out.add(t)
            for (i in 0 until node.childCount) node.getChild(i)?.let { collectTexts(it, out) }
        } catch (e: Exception) {}
    }

    /** Screen pe last lamba jawab (ChatGPT ka reply). */
    fun readLastReply(): String {
        return try {
            val root = rootInActiveWindow ?: return ""
            val texts = ArrayList<String>()
            collectTexts(root, texts)
            texts.lastOrNull { it.length > 18 } ?: ""
        } catch (e: Exception) { "" }
    }

    /** ChatGPT jaisi app se lagatar baat - 4 message bhejo, reply padho, phir bye. */
    fun chatWithApp(pkg: String, appName: String, onStatus: (String) -> Unit, onReply: (String) -> Unit): Boolean {
        return try {
            onStatus("Opening $appName...")
            if (!openAppSmart(pkg, appName)) { onStatus("$appName nahi khula"); return false }
            Thread.sleep(4200)
            val msgs = listOf(
                "Hello! How are you?",
                "Nice. Tell me one interesting fact.",
                "What should a class 10 student study today?",
                "Bye! Talk to you later."
            )
            for ((i, m) in msgs.withIndex()) {
                onStatus("Typing message ${i + 1}...")
                if (!typeInto(m)) { onStatus("Type nahi ho paya"); return false }
                Thread.sleep(700)
                var sent = clickByDesc("Send")
                if (!sent) sent = clickByText("Send")
                if (!sent) pressEnter()
                Thread.sleep(6500)
                val reply = readLastReply()
                if (reply.isNotBlank()) onReply(reply.take(230))
                Thread.sleep(900)
            }
            home()
            true
        } catch (e: Exception) { false }
    }

    /** Pehla letter capital karo. */
    private fun capitalize(s: String): String =
        if (s.isBlank()) s
        else s.replaceFirstChar { if (it.isLowerCase()) it.titlecaseChar() else it }

    /** Naam ke kai variants (poora, pehla shabd, aakhri shabd, shuruaat). */
    private fun contactVariants(name: String): List<String> {
        val out = ArrayList<String>()
        val n = name.trim()
        if (n.isBlank()) return out
        out.add(n)
        val words = n.split(" ").filter { it.isNotBlank() }
        if (words.size > 1) {
            if (!out.contains(words.first())) out.add(words.first())
            if (!out.contains(words.last())) out.add(words.last())
        }
        if (n.length > 5) {
            val p = n.substring(0, 5)
            if (!out.contains(p)) out.add(p)
        }
        return out
    }

    /** Home list -> Archived -> Search, har naam variant ke saath. */
    private fun findContactAny(variants: List<String>, onStatus: (String) -> Unit): Boolean {
        if (variants.isEmpty()) return false

        // 1) Home chat list (jo dikh raha hai, phir scroll)
        for (v in variants) if (findVisibleWithRetry(v, 1)) return true
        for (v in variants) if (findInChatList(v, 2)) return true

        // 2) Archived
        onStatus("Checking Archived...")
        scrollTop()
        Thread.sleep(600)
        if (clickArchived()) {
            Thread.sleep(1800)
            for (v in variants) if (findVisibleWithRetry(v, 2)) return true
            for (v in variants) if (findInChatList(v, 2)) return true
            back()
            Thread.sleep(900)
        }

        // 3) Search
        onStatus("Searching...")
        for (v in variants) if (searchAndOpen(v)) return true
        return false
    }

    /** Bina scroll kiye, jo saamne dikh raha hai wahi dhoondho (thoda intezaar ke saath). */
    private fun findVisibleWithRetry(contact: String, tries: Int): Boolean {
        for (i in 0 until tries) {
            if (openFirstChatResult(contact)) return true
            Thread.sleep(600)
        }
        return false
    }

    /** Chat list me naam dhoondho (scroll karke bhi). Mile to chat kholo. */
    private fun findInChatList(contact: String, maxTries: Int): Boolean {
        for (i in 0 until maxTries) {
            if (openFirstChatResult(contact)) return true
            scrollResults()
            Thread.sleep(900)
        }
        return false
    }

    /** WhatsApp ko Home (Chats) tab pe le aao. */
    private fun goToChats(): Boolean {
        if (clickByDesc("Chats")) return true
        if (clickByText("Chats")) return true
        // fallback: neeche wale nav ka pehla (left) tab
        val dm = resources.displayMetrics
        tap((dm.widthPixels * 0.12f).toInt(), (dm.heightPixels * 0.965f).toInt())
        return true
    }

    private fun clickArchived(): Boolean {
        val root = rootInActiveWindow ?: return false
        val n = findNode(root) {
            val tx = it.text?.toString()?.lowercase() ?: ""
            val dc = it.contentDescription?.toString()?.lowercase() ?: ""
            tx.contains("archived") || dc.contains("archived")
        } ?: return false
        return clickNodeOrTap(n)
    }

    private fun scrollTop() {
        val root = rootInActiveWindow ?: return
        val s = findNode(root) { it.isScrollable } ?: return
        for (i in 0 until 4) {
            s.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            Thread.sleep(350)
        }
    }

    /** Search se kholo. */
    private fun searchAndOpen(contact: String): Boolean {
        if (!clickByDesc("Search") && !clickByText("Search")) {
            clickByDesc("Search or start new chat")
        }
        Thread.sleep(1100)
        if (!setTextFocused(contact)) return false
        Thread.sleep(2000)
        if (!openFirstChatResult(contact)) {
            scrollResults()
            Thread.sleep(1200)
        }
        return openFirstChatResult(contact)
    }

    private fun openFirstChatResult(contact: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val q = contact.lowercase().trim()
        if (q.isEmpty()) return false
        val rows = ArrayList<AccessibilityNodeInfo>()
        try {
            val byText = root.findAccessibilityNodeInfosByText(contact)
            if (byText != null) for (n in byText) if (!n.isEditable) rows.add(n)
        } catch (e: Exception) {}
        collectRows(root, q, rows)
        if (rows.isEmpty()) return false
        rows.sortBy {
            val r = Rect(); it.getBoundsInScreen(r); r.top
        }
        val dm = resources.displayMetrics
        for (row in rows) {
            val r = Rect(); row.getBoundsInScreen(r)
            if (r.top < (dm.heightPixels * 0.10f).toInt()) continue // toolbar/header skip
            if (r.top > (dm.heightPixels * 0.88f).toInt()) continue // neeche ka tab bar skip (Status/Calls)
            if (clickNodeOrTap(row)) return true
        }
        return false
    }

    private fun collectRows(node: AccessibilityNodeInfo, q: String, out: ArrayList<AccessibilityNodeInfo>) {
        if (node.isEditable) return
        val txt = node.text?.toString()?.lowercase()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase()?.trim() ?: ""
        if ((txt.isNotEmpty() && txt.contains(q)) || (desc.isNotEmpty() && desc.contains(q))) {
            out.add(node)
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectRows(it, q, out) }
        }
    }

    private fun boxEmptyOrGone(): Boolean {
        val box = findChatInput()
        return box == null || box.text?.toString().isNullOrBlank()
    }

    /** Chat me hamara bheja hua message (bubble) dikh raha hai? */
    private fun lastMessageIs(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val dm = resources.displayMetrics
        val list = try { root.findAccessibilityNodeInfosByText(text) } catch (e: Exception) { null } ?: return false
        for (n in list) {
            if (n.isEditable) continue
            val t = n.text?.toString()?.trim() ?: continue
            if (!t.equals(text.trim(), true)) continue
            val r = Rect(); n.getBoundsInScreen(r)
            if (r.top > dm.heightPixels * 0.45) return true
        }
        return false
    }

    private fun findChatInput(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val byHint = findNode(root) { n ->
            if (!n.isEditable) return@findNode false
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) n.hintText?.toString()?.lowercase() ?: "" else ""
            val desc = n.contentDescription?.toString()?.lowercase() ?: ""
            (hint + " " + desc).contains("message")
        }
        if (byHint != null) return byHint
        // fallback: sabse NEECHE wala editable = message box (search box upar hota hai)
        val editables = ArrayList<AccessibilityNodeInfo>()
        collectEditables(root, editables)
        if (editables.isEmpty()) return null
        editables.sortBy { val r = Rect(); it.getBoundsInScreen(r); r.top }
        val bottom = editables.last()
        val r = Rect(); bottom.getBoundsInScreen(r)
        val dm = resources.displayMetrics
        return if (r.top > dm.heightPixels / 2) bottom else null
    }

    private fun collectEditables(node: AccessibilityNodeInfo, out: ArrayList<AccessibilityNodeInfo>) {
        if (node.isEditable) { out.add(node); return }
        for (i in 0 until node.childCount) node.getChild(i)?.let { collectEditables(it, out) }
    }

    private fun isInChat(): Boolean {
        val input = findChatInput() ?: return false
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) input.hintText?.toString()?.lowercase() ?: "" else ""
        val desc = input.contentDescription?.toString()?.lowercase() ?: ""
        if ("$hint $desc".contains("search")) return false
        return true
    }

    private fun scrollResults(): Boolean {
        val root = rootInActiveWindow ?: return false
        val s = findNode(root) { it.isScrollable } ?: return false
        return s.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    // ---------- Helpers ----------
    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val r = findEditable(c)
            if (r != null) return r
        }
        return null
    }

    private fun findNode(node: AccessibilityNodeInfo, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (pred(node)) return node
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val r = findNode(c, pred)
            if (r != null) return r
        }
        return null
    }
}
