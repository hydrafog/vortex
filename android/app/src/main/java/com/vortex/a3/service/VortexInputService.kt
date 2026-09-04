package com.vortex.a3.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlin.math.abs

class VortexInputService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())

    private var dispW = 0f
    private var dispH = 0f

    private var lastHandoffUrl: String? = null
    private var lastHandoffPkg: String? = null
    private var lastHandoffAt = 0L

    private var downActive = false
    private var movedSinceDown = false
    private var downX = 0f
    private var downY = 0f
    private var downAtMs = 0L

    private var lastStroke: GestureDescription.StrokeDescription? = null
    private var gestureInFlight = false
    private var touchActive = false
    private var pendingLift = false
    private var curX = 0f
    private var curY = 0f
    private var tgtX = 0f
    private var tgtY = 0f

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        cacheDisplaySize()
        main.postDelayed(handoffHeartbeat, HANDOFF_HEARTBEAT_MS)
        Log.i(TAG, "input service connected (${dispW.toInt()}x${dispH.toInt()})")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        main.removeCallbacks(watchdog)
        main.removeCallbacks(idleClose)
        main.removeCallbacks(handoffHeartbeat)
        if (instance === this) instance = null
        super.onDestroy()
    }

    private val handoffHeartbeat = object : Runnable {
        override fun run() {
            val url = lastHandoffUrl
            if (url != null) {
                VortexService.handoffBus.tryEmit(
                    com.vortex.a3.core.handoff.HandoffEvent(
                        url = url, appId = lastHandoffPkg ?: "", openNow = false,
                    ),
                )
            }
            main.postDelayed(this, HANDOFF_HEARTBEAT_MS)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            handleHandoff(event)
        } catch (_: Throwable) {
        }
    }

    override fun onInterrupt() {}

    private fun handleHandoff(event: AccessibilityEvent?) {
        event ?: return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastHandoffAt < HANDOFF_THROTTLE_MS) return
        lastHandoffAt = now

        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        val urlBarId = BROWSER_URL_BARS[pkg]
        if (urlBarId == null) {
            if (lastHandoffUrl != null) {
                lastHandoffUrl = null
                lastHandoffPkg = null
                VortexService.handoffBus.tryEmit(
                    com.vortex.a3.core.handoff.HandoffEvent(url = "", openNow = false),
                )
            }
            return
        }
        val raw = try {
            root.findAccessibilityNodeInfosByViewId(urlBarId)
                ?.firstOrNull()?.text?.toString()?.trim()
        } catch (_: Throwable) {
            null
        }
        val url = normalizeUrl(raw) ?: return
        if (url == lastHandoffUrl) return
        lastHandoffUrl = url
        lastHandoffPkg = pkg
        VortexService.handoffBus.tryEmit(
            com.vortex.a3.core.handoff.HandoffEvent(url = url, appId = pkg, openNow = false),
        )
    }

    private fun normalizeUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim()
        if (s.startsWith("http://") || s.startsWith("https://")) return s
        if (!s.any { it.isWhitespace() } && s.contains('.')) return "https://$s"
        return null
    }

    private fun cacheDisplaySize() {
        try {
            val wm = getSystemService(WindowManager::class.java)
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            dispW = dm.widthPixels.toFloat()
            dispH = dm.heightPixels.toFloat()
        } catch (e: Exception) {
            Log.w(TAG, "display size: ${e.message}")
        }
    }

    private val watchdog = Runnable {
        if (touchActive || gestureInFlight || downActive) {
            Log.i(TAG, "watchdog: clearing stuck input state")
            forceReset()
        }
    }

    private val idleClose = Runnable {
        if (touchActive && !pendingLift) {
            pendingLift = true
            if (!gestureInFlight) tick()
        }
    }

    fun onPacket(pkt: ByteArray) {
        if (pkt.size != 5) return
        val type = pkt[0].toInt() and 0xFF
        val nx = ((pkt[1].toInt() and 0xFF) shl 8) or (pkt[2].toInt() and 0xFF)
        val ny = ((pkt[3].toInt() and 0xFF) shl 8) or (pkt[4].toInt() and 0xFF)
        main.post {
            main.removeCallbacks(watchdog)
            main.postDelayed(watchdog, WATCHDOG_MS)
            dispatch(type, nx, ny)
        }
    }

    private fun dispatch(type: Int, nx: Int, ny: Int) {
        when (type) {
            T_DOWN -> onDown(toX(nx), toY(ny))
            T_MOVE -> onMove(toX(nx), toY(ny))
            T_UP -> onUp(toX(nx), toY(ny))
            T_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            T_HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            T_RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        }
    }

    private fun onDown(x: Float, y: Float) {
        downActive = true
        movedSinceDown = false
        downX = x; downY = y
        downAtMs = SystemClock.uptimeMillis()
        curX = x; curY = y
        tgtX = x; tgtY = y
    }

    private fun onMove(x: Float, y: Float) {
        tgtX = x; tgtY = y
        movedSinceDown = true
        when {
            !touchActive && downActive -> beginDrag()
            touchActive -> { main.removeCallbacks(idleClose); tick() }
        }
    }

    private fun onUp(x: Float, y: Float) {
        if (touchActive) {
            tgtX = x; tgtY = y
            pendingLift = true
            main.removeCallbacks(idleClose)
            tick()
        } else if (downActive && !movedSinceDown) {
            val held = SystemClock.uptimeMillis() - downAtMs
            if (held >= LONG_PRESS_MS) longPress(downX, downY, held) else quickTap(downX, downY)
        }
        downActive = false
    }

    private fun beginDrag() {
        touchActive = true
        pendingLift = false
        lastStroke = null
        tick()
    }

    private fun tick() {
        if (!touchActive || gestureInFlight) return
        val needMove = abs(tgtX - curX) >= 0.5f || abs(tgtY - curY) >= 0.5f
        if (!needMove && !pendingLift) {
            main.removeCallbacks(idleClose)
            main.postDelayed(idleClose, IDLE_CLOSE_MS)
            return
        }

        val willContinue = !pendingLift
        val nx = if (needMove) tgtX else curX + 1f
        val ny = if (needMove) tgtY else curY
        val p = Path()
        p.moveTo(curX, curY)
        p.lineTo(nx, ny)
        val stroke = lastStroke?.let { prev ->
            try {
                prev.continueStroke(p, 0, SEG_MS, willContinue)
            } catch (e: Exception) {
                null
            }
        } ?: GestureDescription.StrokeDescription(p, 0, SEG_MS, willContinue)
        lastStroke = if (willContinue) stroke else null

        gestureInFlight = true
        val ok = dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) {
                    gestureInFlight = false
                    curX = nx; curY = ny
                    if (!willContinue) endDrag() else tick()
                }

                override fun onCancelled(d: GestureDescription?) {
                    gestureInFlight = false
                    curX = nx; curY = ny
                    lastStroke = null
                    endDrag()
                }
            },
            main,
        )
        if (!ok) {
            gestureInFlight = false
            lastStroke = null
            endDrag()
        }
    }

    private fun endDrag() {
        touchActive = false
        pendingLift = false
        lastStroke = null
        main.removeCallbacks(idleClose)
    }

    private fun quickTap(x: Float, y: Float, retry: Boolean = true) {
        if (!dispatchStationary(x, y, TAP_MS) && retry) {
            forceReset()
            main.postDelayed({ quickTap(x, y, retry = false) }, RETRY_MS)
        }
    }

    private fun longPress(x: Float, y: Float, heldMs: Long) {
        dispatchStationary(x, y, heldMs.coerceIn(LONG_PRESS_MS, MAX_PRESS_MS))
    }

    private fun dispatchStationary(x: Float, y: Float, durMs: Long): Boolean {
        val p = Path()
        p.moveTo(x, y)
        p.lineTo(x + 1f, y)
        return try {
            val stroke = GestureDescription.StrokeDescription(p, 0, durMs, false)
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        } catch (e: Exception) {
            Log.w(TAG, "press: ${e.message}")
            true
        }
    }

    private fun forceReset() {
        gestureInFlight = false
        touchActive = false
        pendingLift = false
        downActive = false
        movedSinceDown = false
        lastStroke = null
        main.removeCallbacks(idleClose)
    }

    private fun toX(nx: Int) = if (dispW > 0f) nx / 65535f * dispW else 0f
    private fun toY(ny: Int) = if (dispH > 0f) ny / 65535f * dispH else 0f

    companion object {
        private const val TAG = "VortexInput"

        private const val HANDOFF_THROTTLE_MS = 1200L

        private const val HANDOFF_HEARTBEAT_MS = 25_000L

        private val BROWSER_URL_BARS = mapOf(
            "com.android.chrome" to "com.android.chrome:id/url_bar",
            "com.chrome.beta" to "com.chrome.beta:id/url_bar",
            "com.chrome.dev" to "com.chrome.dev:id/url_bar",
            "com.microsoft.emmx" to "com.microsoft.emmx:id/url_bar",
            "com.brave.browser" to "com.brave.browser:id/url_bar",
            "org.mozilla.firefox" to "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
        )

        @Volatile
        var instance: VortexInputService? = null

        const val T_DOWN = 0
        const val T_MOVE = 1
        const val T_UP = 2
        const val T_BACK = 10
        const val T_HOME = 11
        const val T_RECENTS = 12

        private const val SEG_MS = 5L
        private const val TAP_MS = 1L
        private const val LONG_PRESS_MS = 400L
        private const val MAX_PRESS_MS = 1_500L
        private const val IDLE_CLOSE_MS = 150L
        private const val WATCHDOG_MS = 700L
        private const val RETRY_MS = 12L

        fun isEnabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val cn = ComponentName(ctx, VortexInputService::class.java).flattenToString()
            return flat.split(':').any { it.equals(cn, ignoreCase = true) }
        }
    }
}
