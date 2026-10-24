package com.mitra.assistant

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Important WhatsApp groups ke messages padhta hai (notification access chahiye). */
class NotifListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            val s = sbn ?: return
            val pkg = s.packageName ?: return
            if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return
            val n = s.notification ?: return
            val ex = n.extras ?: return
            val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
            val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return
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
