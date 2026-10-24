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

    // ---------- Smart target finding (text + content-description, best match) ----------
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

    // ---------- Click ----------
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

    // ---------- Target location (Bublu wahan ludhak kar jayega) ----------
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

    /** App kholo aur verify karo ki wo aage aaya (honest reporting). */
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

    // ---------- WhatsApp: status ke saath ----------
    fun sendWhatsApp(contact: String, message: String, onStatus: (String) -> Unit): Boolean {
        return try {
            onStatus("WhatsApp khol raha hoon...")
            if (!openApp("com.whatsapp")) { onStatus("WhatsApp nahi khula"); return false }
            Thread.sleep(3000)

            onStatus("Search khol raha hoon...")
            if (!clickByDesc("Search") && !clickByText("Search")) {
                clickByDesc("Search or start new chat")
            }
            Thread.sleep(900)

            onStatus("$contact dhoondh raha hoon...")
            if (!setTextFocused(contact)) { onStatus("Search box nahi mila"); return false }
            Thread.sleep(1900)

            onStatus("Chat khol raha hoon...")
            if (!clickByText(contact)) { onStatus("$contact nahi mila"); return false }
            Thread.sleep(1700)

            onStatus("Message likh raha hoon...")
            if (!setTextFocused(message)) { onStatus("Message box nahi mila"); return false }
            Thread.sleep(800)

            onStatus("Bhej raha hoon...")
            if (!clickByDesc("Send")) clickByText("Send")
            Thread.sleep(800)

            onStatus("Ho gaya! Home ja raha hoon...")
            home()
            true
        } catch (e: Exception) {
            false
        }
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
