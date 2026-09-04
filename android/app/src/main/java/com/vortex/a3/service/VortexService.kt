package com.vortex.a3.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.vortex.a3.core.lan.LanServer

class VortexService : Service() {

    private val tag = "VortexService"

    private val stack = VortexStack(this)
    private val notification = VortexNotification(this, stack)
    private val receivers = VortexReceivers(
        context = this,
        onBluetoothReenabled = { onBluetoothReenabled() },
        onBatteryChanged = {
            stack.pushStateViaBle()
            liveLan?.nudge()
        },
    )

    override fun onCreate() {
        super.onCreate()
        Log.i(tag, "service onCreate")
        notification.startInForeground()
        try {
            android.service.notification.NotificationListenerService.requestRebind(
                android.content.ComponentName(
                    this,
                    com.vortex.a3.core.media.MediaNotificationListenerService::class.java,
                ),
            )
        } catch (e: Exception) {
            Log.w(tag, "listener requestRebind: ${e.message}")
        }
        ensureStackStarted()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(tag, "onStartCommand flags=$flags startId=$startId action=${intent?.action}")
        if (!stack.isStarted()) {
            ensureStackStarted()
        } else when (intent?.action) {
            ACTION_REFRESH_CALLFLOW -> stack.refreshCallFlow()
            ACTION_TOGGLE_AUDIO -> toggleAudio()
            ACTION_LOCK_LAPTOP -> requestLaptopLock(applicationContext, "lock")
            ACTION_UNLOCK_LAPTOP -> requestLaptopLock(applicationContext, "unlock")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(tag, "service onDestroy")
        retryHandler.removeCallbacks(retryStart)
        notification.stop()
        stack.stop()
        receivers.unregister()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private val retryHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val retryStart = Runnable { ensureStackStarted() }

    private fun ensureStackStarted() {
        if (stack.isStarted()) return
        receivers.register()
        if (stack.start(onStateChanged = { notification.refresh() })) {
            retryHandler.removeCallbacks(retryStart)
            Log.i(tag, "stack started")
        } else {
            Log.w(tag, "stack start failed (Bluetooth down?); staying up, retry in ${STACK_RETRY_MS}ms")
            retryHandler.removeCallbacks(retryStart)
            retryHandler.postDelayed(retryStart, STACK_RETRY_MS)
        }
    }

    private fun onBluetoothReenabled() {
        if (stack.isStarted()) stack.restartBleComponents() else ensureStackStarted()
    }

    private fun toggleAudio() {
        stack.toggleAudio { owner -> notification.noteSwitchTarget(owner) }
    }

    companion object {
        private const val STACK_RETRY_MS = 3000L

        const val ACTION_REFRESH_CALLFLOW = "com.vortex.a3.REFRESH_CALLFLOW"
        const val ACTION_TOGGLE_AUDIO = "com.vortex.a3.TOGGLE_AUDIO"
        const val ACTION_LOCK_LAPTOP = "com.vortex.a3.LOCK_LAPTOP"
        const val ACTION_UNLOCK_LAPTOP = "com.vortex.a3.UNLOCK_LAPTOP"

        val peerStateBus: kotlinx.coroutines.flow.MutableSharedFlow<
            Pair<String, com.vortex.a3.core.appstate.AppState>
        > = kotlinx.coroutines.flow.MutableSharedFlow(
            replay = 1,
            extraBufferCapacity = 32,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
        )

        val notificationBus: kotlinx.coroutines.flow.MutableSharedFlow<
            com.vortex.a3.core.notif.NotificationMirror
        > = kotlinx.coroutines.flow.MutableSharedFlow(
            replay = 0,
            extraBufferCapacity = 64,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
        )

        val clipboardBus: kotlinx.coroutines.flow.MutableSharedFlow<String> =
            kotlinx.coroutines.flow.MutableSharedFlow(
                replay = 0,
                extraBufferCapacity = 8,
                onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
            )

        val clipboardImageBus: kotlinx.coroutines.flow.MutableSharedFlow<ByteArray> =
            kotlinx.coroutines.flow.MutableSharedFlow(
                replay = 0,
                extraBufferCapacity = 4,
                onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
            )

        val clipboardFileBus: kotlinx.coroutines.flow.MutableSharedFlow<
            com.vortex.a3.core.clipboard.ClipboardOutgoingFile> =
            kotlinx.coroutines.flow.MutableSharedFlow(
                replay = 0,
                extraBufferCapacity = 4,
                onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
            )

        val handoffBus: kotlinx.coroutines.flow.MutableSharedFlow<
            com.vortex.a3.core.handoff.HandoffEvent> =
            kotlinx.coroutines.flow.MutableSharedFlow(
                replay = 0,
                extraBufferCapacity = 4,
                onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
            )

        val liveActivityBus: kotlinx.coroutines.flow.MutableSharedFlow<
            com.vortex.a3.core.notif.LiveActivity
        > = kotlinx.coroutines.flow.MutableSharedFlow(
            replay = 0,
            extraBufferCapacity = 64,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
        )

        val callEventBus: kotlinx.coroutines.flow.MutableSharedFlow<
            com.vortex.a3.core.call.CallEvent
        > = kotlinx.coroutines.flow.MutableSharedFlow(
            replay = 1,
            extraBufferCapacity = 16,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
        )

        val contactsBus: kotlinx.coroutines.flow.MutableSharedFlow<
            List<com.vortex.a3.core.contacts.Contact>
        > = kotlinx.coroutines.flow.MutableSharedFlow(
            replay = 1,
            extraBufferCapacity = 4,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
        )

        val callLogBus: kotlinx.coroutines.flow.MutableSharedFlow<
            List<com.vortex.a3.core.calllog.CallLogEntry>
        > = kotlinx.coroutines.flow.MutableSharedFlow(
            replay = 1,
            extraBufferCapacity = 4,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
        )

        val smsBus: kotlinx.coroutines.flow.MutableSharedFlow<
            List<com.vortex.a3.core.sms.SmsMessage>
        > = kotlinx.coroutines.flow.MutableSharedFlow(
            replay = 1,
            extraBufferCapacity = 4,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
        )

        val revokedByPeerBus: kotlinx.coroutines.flow.MutableSharedFlow<String> =
            kotlinx.coroutines.flow.MutableSharedFlow(
                replay = 0,
                extraBufferCapacity = 16,
                onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
            )

        val pendingRevokes: java.util.concurrent.ConcurrentHashMap.KeySetView<String, Boolean> =
            java.util.concurrent.ConcurrentHashMap.newKeySet()

        val pendingAudioClaim: java.util.concurrent.atomic.AtomicBoolean =
            java.util.concurrent.atomic.AtomicBoolean(false)

        @Volatile
        var pendingCallPhase: String? = null

        @Volatile
        var currentCall: com.vortex.a3.core.call.CallEvent? = null

        fun callGateActive(): Boolean = currentCall != null || pendingCallPhase != null

        @Volatile
        var currentHandoff: com.vortex.a3.core.handoff.HandoffEvent? = null

        @Volatile
        var cameraOffer: com.vortex.a3.core.appstate.CameraOffer? = null

        @Volatile
        internal var liveLan: LanServer? = null

        @Volatile
        internal var liveStack: VortexStack? = null

        fun requestPeerToClaim() {
            pendingAudioClaim.set(true)
            liveLan?.nudge()
        }

        fun requestStatePush() {
            liveStack?.pushStateViaBle()
            liveLan?.nudge()
        }

        data class PendingLock(val op: String, val seq: Long, val expiresAtMs: Long)

        val pendingLock =
            java.util.concurrent.atomic.AtomicReference<PendingLock?>(null)

        private const val LOCK_CMD_TTL_MS = 20_000L

        private val lockPushHandler =
            android.os.Handler(android.os.Looper.getMainLooper())

        fun requestLaptopLock(context: Context, op: String) {
            val seq = com.vortex.a3.core.appstate.LockCommandSeq.next(context)
            pendingLock.set(
                PendingLock(
                    op = op,
                    seq = seq,
                    expiresAtMs = android.os.SystemClock.elapsedRealtime() + LOCK_CMD_TTL_MS,
                ),
            )
            requestStatePush()
            for (delayMs in longArrayOf(2_000L, 6_000L)) {
                lockPushHandler.postDelayed(
                    { if (pendingLock.get()?.seq == seq) requestStatePush() },
                    delayMs,
                )
            }
        }

        data class PendingMediaControl(val op: String, val seq: Long, val expiresAtMs: Long)

        val pendingMediaControl =
            java.util.concurrent.atomic.AtomicReference<PendingMediaControl?>(null)

        private const val MEDIA_CMD_TTL_MS = 8_000L

        fun requestLaptopMedia(context: Context, op: String) {
            val seq = com.vortex.a3.core.appstate.LockCommandSeq.next(context)
            pendingMediaControl.set(
                PendingMediaControl(
                    op = op,
                    seq = seq,
                    expiresAtMs = android.os.SystemClock.elapsedRealtime() + MEDIA_CMD_TTL_MS,
                ),
            )
            requestStatePush()
            lockPushHandler.postDelayed(
                { if (pendingMediaControl.get()?.seq == seq) requestStatePush() },
                2_000L,
            )
        }

        fun requestLanNudge() {
            liveLan?.nudge()
        }

        fun start(context: Context) {
            val intent = Intent(context, VortexService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VortexService::class.java))
        }

        fun startOrRefreshCallFlow(context: Context) {
            val intent = Intent(context, VortexService::class.java)
                .setAction(ACTION_REFRESH_CALLFLOW)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
