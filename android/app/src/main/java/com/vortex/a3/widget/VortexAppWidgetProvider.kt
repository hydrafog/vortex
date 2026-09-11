package com.vortex.a3.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.vortex.a3.R
import com.vortex.a3.service.VortexService
import com.vortex.a3.ui.MainActivity

class VortexAppWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {
        @Volatile private var cachedPeerName: String? = null
        @Volatile private var cachedConnected: Boolean = false
        @Volatile private var cachedBattery: Int? = null
        @Volatile private var cachedLocked: Boolean? = null

        fun updateAll(
            context: Context,
            peerName: String?,
            connected: Boolean,
            battery: Int?,
            locked: Boolean?,
        ) {
            cachedPeerName = peerName
            cachedConnected = connected
            cachedBattery = battery
            cachedLocked = locked

            try {
                val appWidgetManager = AppWidgetManager.getInstance(context) ?: return
                val thisWidget = ComponentName(context, VortexAppWidgetProvider::class.java)
                val appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
                if (appWidgetIds == null || appWidgetIds.isEmpty()) return

                for (appWidgetId in appWidgetIds) {
                    updateAppWidget(context, appWidgetManager, appWidgetId)
                }
            } catch (_: Throwable) {}
        }

        fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val rv = RemoteViews(context.packageName, R.layout.widget_vortex)

            val name = cachedPeerName?.takeIf { it.isNotBlank() } ?: "Vortex"
            val connected = cachedConnected
            val battery = cachedBattery
            val locked = cachedLocked

            rv.setTextViewText(R.id.widget_peer_name, name)
            rv.setTextViewText(
                R.id.widget_peer_status,
                if (connected) "Connected" else "Not Connected",
            )
            rv.setTextColor(
                R.id.widget_peer_status,
                if (connected) 0xFF33D17A.toInt() else 0xFFA1A1AA.toInt(),
            )

            if (connected && battery != null) {
                rv.setViewVisibility(R.id.widget_battery_row, View.VISIBLE)
                rv.setTextViewText(R.id.widget_battery_text, "$battery%")
            } else {
                rv.setViewVisibility(R.id.widget_battery_row, View.GONE)
            }

            if (connected && locked != null) {
                rv.setViewVisibility(R.id.widget_quick_toggle, View.VISIBLE)
                rv.setImageViewResource(
                    R.id.widget_quick_icon,
                    if (locked) R.drawable.ic_notification_lock else R.drawable.ic_notification_lock_open,
                )
                val lockAction = if (locked) VortexService.ACTION_UNLOCK_LAPTOP else VortexService.ACTION_LOCK_LAPTOP
                val lockPi = PendingIntent.getService(
                    context,
                    101,
                    Intent(context, VortexService::class.java).setAction(lockAction),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                rv.setOnClickPendingIntent(R.id.widget_quick_toggle, lockPi)
            } else {
                rv.setViewVisibility(R.id.widget_quick_toggle, View.GONE)
            }

            val appPi = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            rv.setOnClickPendingIntent(R.id.widget_root, appPi)

            appWidgetManager.updateAppWidget(appWidgetId, rv)
        }
    }
}
