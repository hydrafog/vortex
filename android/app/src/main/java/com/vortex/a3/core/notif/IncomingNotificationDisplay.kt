package com.vortex.a3.core.notif

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.atomic.AtomicInteger

object IncomingNotificationDisplay {
    private const val CHANNEL_ID = "vortex_mirror"
    private val seq = AtomicInteger(20_000)
    @Volatile private var channelReady = false

    fun show(context: Context, m: NotificationMirror) {
        val ctx = context.applicationContext
        ensureChannel(ctx)
        val title = when {
            m.app.isNotBlank() && m.title.isNotBlank() -> "${m.app} · ${m.title}"
            m.title.isNotBlank() -> m.title
            else -> m.app.ifBlank { "Notification" }
        }
        val id = seq.incrementAndGet()
        val tapIntent = android.content.Intent("com.vortex.a3.MIRROR_TAP")
            .setPackage(ctx.packageName)
        val piFlags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
            android.app.PendingIntent.FLAG_IMMUTABLE
        val tapPi = android.app.PendingIntent.getBroadcast(ctx, id, tapIntent, piFlags)
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(m.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(m.text))
            .setAutoCancel(true)
            .setContentIntent(tapPi)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (_: SecurityException) {
        }
    }

    private fun ensureChannel(ctx: Context) {
        if (channelReady) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Laptop notifications",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Notifications mirrored from the paired laptop" }
            ctx.getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(ch)
        }
        channelReady = true
    }
}
