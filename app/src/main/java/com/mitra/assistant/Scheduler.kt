package com.mitra.assistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/** Reminder/alarm ko store karke AlarmManager se set karta hai. */
object Scheduler {
    fun schedule(context: Context, text: String, hour24: Int, minute: Int, dayOffset: Int): Long {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, dayOffset)
        cal.set(Calendar.HOUR_OF_DAY, hour24)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
        val id = (System.currentTimeMillis() % 100000).toInt()
        Store.addReminder(context, id, cal.timeInMillis, text)
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val i = Intent(context, ReminderReceiver::class.java).putExtra("text", text)
            val pi = PendingIntent.getBroadcast(context, id, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            try {
                if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
                else am.set(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
            } catch (e: Exception) {
                am.set(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
            }
        } catch (e: Exception) {}
        return cal.timeInMillis
    }
}
