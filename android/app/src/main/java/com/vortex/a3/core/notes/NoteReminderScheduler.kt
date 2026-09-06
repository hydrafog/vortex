package com.vortex.a3.core.notes

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object NoteReminderScheduler {
    const val ACTION_FIRE = "com.vortex.a3.NOTE_REMINDER"
    private const val CHANNEL_ID = "vortex_note_reminders"
    @Volatile private var channelReady = false

    fun reschedule(context: Context, items: List<Note>) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val now = System.currentTimeMillis()
        for (n in items) {
            if (n.kind != "todo") continue
            val pi = firePendingIntent(context, n)
            am.cancel(pi)
            if (!n.done && !n.deleted && n.dueAt > now) {
                try {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, n.dueAt, pi)
                } catch (_: SecurityException) {
                    am.set(AlarmManager.RTC_WAKEUP, n.dueAt, pi)
                }
            }
        }
    }

    private fun firePendingIntent(context: Context, n: Note): PendingIntent {
        val i = Intent(context, NoteReminderReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra("id", n.id)
            putExtra("title", n.title)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, n.id.hashCode(), i, flags)
    }

    fun fireNotification(context: Context, id: String, title: String) {
        ensureChannel(context)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.vortex.a3.R.drawable.ic_notification_note)
            .setContentTitle(title.ifBlank { "Reminder" })
            .setContentText(if (title.isBlank()) "Todo" else "Reminder")
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id.hashCode(), n)
        } catch (_: SecurityException) {
        }
    }

    private fun ensureChannel(context: Context) {
        if (channelReady) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Note reminders",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Todo due-date reminders" }
            context.getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(ch)
        }
        channelReady = true
    }
}
