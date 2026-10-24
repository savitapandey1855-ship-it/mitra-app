package com.mitra.assistant

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayList

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
        return clickNode(n)
    }

    fun clickByDesc(desc: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val d = desc.lowercase()
        val n = findNode(root) { it.contentDescription?.toString()?.lowercase()?.contains(d) == true } ?: return false
        return clickNode(n)
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

    fun openAppAndCheck(pkg: String): Boolean {
        if (!openApp(pkg)) return false
        for (i in 0 until 5) {
            Thread.sleep(700)
            val cur = try { rootInActiveWindow?.packageName?.toString() } catch (e: Exception) { null }
            if (cur == pkg) return true
        }
        return false
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
    //  WhatsApp — careful, verified, multi-step
    // =========================================================
    fun sendWhatsApp(contact: String, message: String, onStatus: (String) -> Unit): Boolean {
        try {
            onStatus("WhatsApp khol raha hoon...")
            if (!openApp("com.whatsapp")) { onStatus("WhatsApp nahi khula"); return false }
            Thread.sleep(3000)

            // 1) Search kholo
            onStatus("Search khol raha hoon...")
            if (!clickByDesc("Search") && !clickByText("Search")) {
                clickByDesc("Search or start new chat")
            }
            Thread.sleep(1000)

            // 2) Naam search karo
            onStatus("$contact dhoondh raha hoon...")
            if (!setTextFocused(contact)) { onStatus("Search box nahi mila"); return false }
            Thread.sleep(2000)

            // 3) Result me se CHAT row kholo (search box ko NAHI)
            if (!openFirstChatResult(contact)) {
                onStatus("Thoda neeche scroll karke dekhta hoon...")
                scrollResults()
                Thread.sleep(1300)
            }
            if (!openFirstChatResult(contact)) {
                onStatus("Are! $contact mila hi nahi")
                try { back() } catch (e: Exception) {}
                return false
            }
            Thread.sleep(1900)

            // 4) Pakka karo ki CHAT khuli hai (search box nahi)
            if (!isInChat()) {
                // ek aur baar try
                if (openFirstChatResult(contact)) Thread.sleep(1800)
            }
            if (!isInChat()) {
                onStatus("$contact ki chat nahi khul payi")
                return false
            }

            // 5) Message type karo (sirf chat ke input box me)
            onStatus("Message likh raha hoon...")
            val box = findChatInput()
            if (box == null || !setNodeText(box, message)) {
                onStatus("Message box nahi mila")
                return false
            }
            Thread.sleep(900)

            // 6) Send
            onStatus("Bhej raha hoon...")
            if (!clickByDesc("Send")) clickByText("Send")
            Thread.sleep(1000)

            // 7) Check ki bhej diya (input box khali ho gaya)
            val empty = try { findChatInput()?.text?.toString().isNullOrBlank() } catch (e: Exception) { true }
            onStatus(if (empty) "Ho gaya! Wapas ja raha hoon..." else "Send shayad nahi hua...")

            home()
            return empty
        } catch (e: Exception) {
            return false
        }
    }

    /** Search results me se naam wali CHAT row kholo (input box skip karo). */
    private fun openFirstChatResult(contact: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val q = contact.lowercase().trim()
        if (q.isEmpty()) return false
        val rows = ArrayList<AccessibilityNodeInfo>()
        collectRows(root, q, rows)
        if (rows.isEmpty()) return false
        rows.sortBy {
            val r = Rect(); it.getBoundsInScreen(r); r.top
        }
        for (row in rows) {
            if (clickNode(row)) return true
        }
        return false
    }

    private fun collectRows(node: AccessibilityNodeInfo, q: String, out: ArrayList<AccessibilityNodeInfo>) {
        if (node.isEditable) return // search box aur koi input skip
        val txt = node.text?.toString()?.lowercase()?.trim() ?: ""
        if (txt.isNotEmpty() && txt.contains(q)) {
            out.add(node)
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectRows(it, q, out) }
        }
    }

    /** Chat ke message input box (hint me "message") dhoondo. */
    private fun findChatInput(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val byHint = findNode(root) { n ->
            if (!n.isEditable) return@findNode false
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) n.hintText?.toString()?.lowercase() ?: "" else ""
            val desc = n.contentDescription?.toString()?.lowercase() ?: ""
            (hint + " " + desc).contains("message")
        }
        return byHint ?: findEditable(root)
    }

    /** Chat khuli hai ya nahi (search box nahi). */
    private fun isInChat(): Boolean {
        val input = findChatInput() ?: return false
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) input.hintText?.toString()?.lowercase() ?: "" else ""
        val desc = input.contentDescription?.toString()?.lowercase() ?: ""
        val combined = "$hint $desc"
        if (combined.contains("search")) return false
        return combined.contains("message")
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
