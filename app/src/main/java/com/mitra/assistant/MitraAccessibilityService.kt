package com.mitra.assistant

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Ye service Mitra ko doosri apps me click / type / paste karne deti hai.
 * User ise Settings > Accessibility me ON karta hai.
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
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    // ---------- Paste ----------
    fun paste(): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root) ?: return false
        return target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    // ---------- Click by text ----------
    fun clickByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text) ?: return false
        for (n in nodes) {
            var node: AccessibilityNodeInfo? = n
            var depth = 0
            while (node != null && depth < 6) {
                if (node.isClickable &&
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                ) return true
                node = node.parent
                depth++
            }
        }
        return false
    }

    // ---------- Global actions ----------
    fun back() { performGlobalAction(GLOBAL_ACTION_BACK) }
    fun home() { performGlobalAction(GLOBAL_ACTION_HOME) }
    fun recents() { performGlobalAction(GLOBAL_ACTION_RECENTS) }
    fun notifications() { performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val r = findEditable(c)
            if (r != null) return r
        }
        return null
    }
}
