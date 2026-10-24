package com.mitra.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/** Alarm/reminder ka time aane pe notification dikhata hai. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text") ?: "Yaad dilana!"
        val ch = "bublu_rem"
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(ch, "Bublu Reminders", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val n = NotificationCompat.Builder(context, ch)
            .setContentTitle("Bublu yaad dila raha hai")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notif)
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() % 100000).toInt(), n)
    }
}
