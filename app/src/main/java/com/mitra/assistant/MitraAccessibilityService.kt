package com.mitra.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
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

    fun setTextFocused(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root) ?: return false
        return setNodeText(target, text)
    }

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
        val n = findClickTarget(text) ?: return false
        return clickNodeOrTap(n)
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

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int) {
        try {
            val path = Path()
            path.moveTo(x1.toFloat(), y1.toFloat())
            path.lineTo(x2.toFloat(), y2.toFloat())
            val stroke = GestureDescription.StrokeDescription(path, 0, 300)
            val g = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(g, null, null)
        } catch (e: Exception) {}
    }

    fun swipeUp() {
        val dm = resources.displayMetrics
        swipe((dm.widthPixels * 0.5f).toInt(), (dm.heightPixels * 0.75f).toInt(),
            (dm.widthPixels * 0.5f).toInt(), (dm.heightPixels * 0.30f).toInt())
    }

    fun swipeDown() {
        val dm = resources.displayMetrics
        swipe((dm.widthPixels * 0.5f).toInt(), (dm.heightPixels * 0.30f).toInt(),
            (dm.widthPixels * 0.5f).toInt(), (dm.heightPixels * 0.75f).toInt())
    }

    fun scrollDown() { if (!scrollAction(true)) swipeUp() }
    fun scrollUp() { if (!scrollAction(false)) swipeDown() }

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
        val txt = node.text?.toString()?.lowercase()?.trim() ?: ""
        if (node.isClickable && txt.isNotEmpty() &&
            !txt.contains("my status") && !txt.contains("add status") &&
            !txt.contains("tap to") && !txt.contains("text status")
        ) {
            val r = Rect(); node.getBoundsInScreen(r)
            val key = "${r.left},${r.top},${r.right},${r.bottom}"
            if (!seen.contains(key)) {
                seen.add(key)
                out.add(node)
            }
        }
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

            var opened = false

            // 1) Home chat list: pehle jo SAMNE DIKH raha hai, phir thoda scroll
            onStatus("Chat list me $contact dhoondh raha hoon...")
            opened = openFirstChatResult(contact)
            if (!opened) opened = findInChatList(contact, 4)

            // 2) Archived: pehle jo SAMNE DIKH raha hai, phir thoda scroll
            if (!opened) {
                onStatus("Archived me dhoondh raha hoon...")
                scrollTop()
                Thread.sleep(700)
                if (clickArchived()) {
                    Thread.sleep(1800)
                    opened = openFirstChatResult(contact)
                    if (!opened) opened = findInChatList(contact, 3)
                    if (!opened) { back(); Thread.sleep(1000) }
                }
            }

            // 3) Search se dhoondho
            if (!opened) {
                onStatus("Search se dhoondh raha hoon...")
                opened = searchAndOpen(contact)
            }

            if (!opened) {
                onStatus("Are! $contact mila hi nahi")
                try { back() } catch (e: Exception) {}
                return false
            }

            Thread.sleep(1500)
            if (!isInChat()) { onStatus("$contact ki chat nahi khuli"); return false }

            onStatus("Message likh raha hoon...")
            val box = findChatInput()
            if (box == null || !setNodeText(box, message)) {
                onStatus("Message box nahi mila")
                return false
            }
            Thread.sleep(900)

            onStatus("Bhej raha hoon...")
            if (!clickByDesc("Send")) clickByText("Send")
            Thread.sleep(1000)

            val empty = try { findChatInput()?.text?.toString().isNullOrBlank() } catch (e: Exception) { true }
            onStatus(if (empty) "Ho gaya! Wapas ja raha hoon..." else "Send shayad nahi hua...")

            home()
            return empty
        } catch (e: Exception) {
            return false
        }
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
            if (clickNodeOrTap(row)) return true
        }
        return false
    }

    private fun collectRows(node: AccessibilityNodeInfo, q: String, out: ArrayList<AccessibilityNodeInfo>) {
        if (node.isEditable) return
        val txt = node.text?.toString()?.lowercase()?.trim() ?: ""
        if (txt.isNotEmpty() && txt.contains(q)) {
            out.add(node)
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectRows(it, q, out) }
        }
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
