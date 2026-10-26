package com.mitra.assistant

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Important WhatsApp groups ke messages padhta hai (notification access chahiye). */
class NotifListener : NotificationListenerService() {

    private val scamWords = listOf(
        "lottery", "lucky draw", "you have won", "you won", "winner", "prize", "crore", "lakh",
        "kyc", "verify your account", "account will be blocked", "account blocked", "suspend",
        "click this link", "click here", "bit.ly", "tinyurl", "free recharge", "gift card",
        "share otp", "last chance", "limited offer", "aap jeet", "inaam"
    )

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            val s = sbn ?: return
            val pkg = s.packageName ?: return
            val n = s.notification ?: return
            val ex = n.extras ?: return
            val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

            // ---- Scam alert (kisi bhi app se) ----
            val body = "$title $text".lowercase()
            if (body.length > 10) {
                val hits = scamWords.count { body.contains(it) }
                val hasLink = body.contains("http") || body.contains("www.") || body.contains(".ly/")
                if (hits >= 2 || (hits >= 1 && hasLink)) {
                    val i = android.content.Intent("com.mitra.assistant.SCAM")
                    i.setPackage(packageName)
                    i.putExtra("title", title)
                    i.putExtra("text", text)
                    sendBroadcast(i)
                    return
                }
            }

            if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return
            if (title.isBlank() || text.isBlank()) return
            val groups = Store.importantGroups(this)
            if (groups.isEmpty()) return
            val isImp = groups.any { title.lowercase().contains(it.lowercase()) }
            if (!isImp) return
            OverlayService.onImportantMessage(title, text)
        } catch (e: Exception) {}
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}
}
