package com.vortex.a3.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.vortex.a3.core.appstate.AppState
import com.vortex.a3.ui.MainActivity

class VortexNotification(
    private val service: Service,
    private val host: Host,
) {
    interface Host {
        fun phoneOwnsBuds(): Boolean
        fun peerState(): AppState?
        fun phoneEarbudsBattery(): Int?
        fun peerStateAgeMs(): Long
    }

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var lastOwner: String? = null
    @Volatile private var targetOwner: String? = null
    @Volatile private var lastOwnerAtMs: Long = 0L

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    fun startInForeground() {
        createChannel()
        val notification = build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            service.startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            service.startForeground(NOTIF_ID, notification)
        }
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, REFRESH_MS)
    }

    fun refresh() {
        try {
            service.getSystemService(NotificationManager::class.java)
                ?.notify(NOTIF_ID, build())
        } catch (_: Exception) {}
    }

    fun noteSwitchTarget(owner: String) {
        targetOwner = owner
        refresh()
        handler.postDelayed({ refresh() }, 700)
        handler.postDelayed({ refresh() }, 1600)
        handler.postDelayed({ refresh() }, 2800)
    }

    fun stop() {
        handler.removeCallbacks(ticker)
    }

    private fun build(): Notification {
        val phoneOwns = host.phoneOwnsBuds()
        val peer = host.peerState()
        val laptopFresh = peer != null && host.peerStateAgeMs() < PEER_FRESH_MS
        val laptopOwns = !phoneOwns && laptopFresh && peer?.earbuds?.connected == true
        val laptopBatt = if (laptopFresh) peer?.battery else null
        val budsBatt = if (phoneOwns) host.phoneEarbudsBattery()
            else if (laptopFresh) peer?.earbuds?.battery else null
        fun pct(v: Int?) = v?.let { "$it%" } ?: "--"

        val realOwner = when {
            phoneOwns -> "phone"
            laptopOwns -> "laptop"
            else -> null
        }
        val now = SystemClock.elapsedRealtime()
        if (realOwner != null) {
            lastOwner = realOwner
            lastOwnerAtMs = now
            if (targetOwner == realOwner) targetOwner = null
        }
        val pending = realOwner == null
        val displayOwner = realOwner ?: targetOwner ?: lastOwner
        val showRow = displayOwner != null &&
            (realOwner != null || now - lastOwnerAtMs < OWNER_HOLD_MS)

        val rv = android.widget.RemoteViews(
            service.packageName,
            com.vortex.a3.R.layout.notification_vortex,
        )
        val laptopCharging = laptopFresh && peer?.charging == true
        val laptopColor = if (laptopCharging) {
            service.getColor(com.vortex.a3.R.color.notification_audio_linux)
        } else {
            service.getColor(android.R.color.white)
        }
        rv.setTextViewText(
            com.vortex.a3.R.id.notification_laptop_battery,
            if (laptopCharging) pct(laptopBatt) + " (charging)" else pct(laptopBatt),
        )
        rv.setTextColor(com.vortex.a3.R.id.notification_laptop_battery, laptopColor)
        rv.setInt(com.vortex.a3.R.id.notification_laptop_icon, "setColorFilter", laptopColor)
        if (showRow) {
            rv.setViewVisibility(
                com.vortex.a3.R.id.notification_audio_group,
                android.view.View.VISIBLE,
            )
            rv.setTextViewText(
                com.vortex.a3.R.id.notification_audio_battery,
                if (pending) "…" else pct(budsBatt),
            )
            val color = if (displayOwner == "phone") {
                service.getColor(com.vortex.a3.R.color.notification_audio_android)
            } else {
                service.getColor(com.vortex.a3.R.color.notification_audio_linux)
            }
            rv.setTextColor(com.vortex.a3.R.id.notification_audio_battery, color)
            rv.setInt(com.vortex.a3.R.id.notification_audio_icon, "setColorFilter", color)
            val togglePi = PendingIntent.getService(
                service,
                3,
                Intent(service, VortexService::class.java).setAction(VortexService.ACTION_TOGGLE_AUDIO),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            rv.setOnClickPendingIntent(com.vortex.a3.R.id.notification_audio_group, togglePi)
        } else {
            rv.setViewVisibility(
                com.vortex.a3.R.id.notification_audio_group,
                android.view.View.GONE,
            )
        }
        val laptopLocked = peer?.locked?.takeIf { host.peerStateAgeMs() < PEER_FRESH_MS }
        if (laptopLocked != null) {
            rv.setViewVisibility(
                com.vortex.a3.R.id.notification_lock_group,
                android.view.View.VISIBLE,
            )
            rv.setImageViewResource(
                com.vortex.a3.R.id.notification_lock_icon,
                if (laptopLocked) {
                    com.vortex.a3.R.drawable.ic_notification_lock
                } else {
                    com.vortex.a3.R.drawable.ic_notification_lock_open
                },
            )
            val lockColor = if (laptopLocked) {
                service.getColor(com.vortex.a3.R.color.notification_audio_linux)
            } else {
                service.getColor(android.R.color.white)
            }
            rv.setInt(com.vortex.a3.R.id.notification_lock_icon, "setColorFilter", lockColor)
            val lockAction = if (laptopLocked) {
                VortexService.ACTION_UNLOCK_LAPTOP
            } else {
                VortexService.ACTION_LOCK_LAPTOP
            }
            val lockPi = PendingIntent.getService(
                service,
                if (laptopLocked) 4 else 5,
                Intent(service, VortexService::class.java).setAction(lockAction),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            rv.setOnClickPendingIntent(com.vortex.a3.R.id.notification_lock_group, lockPi)
        } else {
            rv.setViewVisibility(
                com.vortex.a3.R.id.notification_lock_group,
                android.view.View.GONE,
            )
        }
        val laptopConnected = peer != null && host.peerStateAgeMs() < PEER_FRESH_MS
        if (laptopConnected && com.vortex.a3.core.clipboard.ClipboardSyncSetting.isEnabled()) {
            rv.setViewVisibility(
                com.vortex.a3.R.id.notification_clipboard_group,
                android.view.View.VISIBLE,
            )
            val clipPi = PendingIntent.getActivity(
                service,
                6,
                Intent(service, com.vortex.a3.core.clipboard.ClipboardQuickSendActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            rv.setOnClickPendingIntent(com.vortex.a3.R.id.notification_clipboard_group, clipPi)
        } else {
            rv.setViewVisibility(
                com.vortex.a3.R.id.notification_clipboard_group,
                android.view.View.GONE,
            )
        }

        val launchPi = PendingIntent.getActivity(
            service,
            0,
            Intent(service, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(com.vortex.a3.R.drawable.ic_notification_vortex)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(launchPi)
            .setCustomContentView(rv)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = service.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Vortex background",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps Vortex reachable to your paired devices."
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "vortex_bg"
        private const val NOTIF_ID = 0x701E5
        private const val REFRESH_MS = 2_000L
        private const val OWNER_HOLD_MS = 12_000L
        private const val PEER_FRESH_MS = 30_000L
    }
}
