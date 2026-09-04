package com.vortex.a3.core.media

import android.content.ComponentName
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent

class MediaHandoffCoordinator(
    private val context: Context,
    private val weOwnBuds: () -> Boolean,
    private val peerHoldsBuds: () -> Boolean,
    private val isCallActive: () -> Boolean,
    private val requestGrab: () -> Boolean,
    private val requestReturnToPeer: () -> Unit,
    private val onMediaPlayingChanged: (Boolean) -> Unit,
) {
    @Volatile var smartSwitchEnabled: Boolean = true
    @Volatile var manualPreferredOwner: String? = null

    @Volatile var peerPlaying: Boolean = false
    @Volatile var peerPlayEpochMono: Long = 0L
    @Volatile var localPlayEpochMono: Long = 0L

    private val handler = Handler(Looper.getMainLooper())
    private var sessionManager: MediaSessionManager? = null
    private var audioManager: AudioManager? = null
    private val componentName = ComponentName(context, MediaNotificationListenerService::class.java)

    private var sessionPathOk = false

    private var lastPlaying = false
    private var lastOwn = false
    private var lastAutoGrabMs = 0L
    private var suppressUntilMs = 0L
    private var running = false

    private var havePausedMedia = false
    private var grabbing = false
    private var grabLate = false
    private var pausedAtMs = 0L
    private var returnTimerAt = 0L
    private var grabbedFromPeer = false
    private var gainedAtMs = 0L
    private var outputReadySinceMs = 0L
    private var lastAdvertised = false
    private val pausedPackages = mutableSetOf<String>()
    private val lastPlayingPackages = mutableSetOf<String>()
    private var audioFocusHeldForHandoff = false
    private var audioFocusRequest: AudioFocusRequest? = null
    private var enforcerActive = false
    private var enforcerStartedMs = 0L

    private val sessionsChanged =
        MediaSessionManager.OnActiveSessionsChangedListener { handler.post(::tick) }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<android.media.AudioPlaybackConfiguration>?) {
            handler.post(::tick)
        }
    }

    fun start() {
        if (running) return
        running = true
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        sessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        sessionPathOk = try {
            sessionManager?.getActiveSessions(componentName)
            sessionManager?.addOnActiveSessionsChangedListener(sessionsChanged, componentName, handler)
            Log.i(TAG, "MediaSession detection active (notification access granted)")
            true
        } catch (e: SecurityException) {
            Log.i(TAG, "notification access not granted; using AudioManager fallback")
            false
        }
        audioManager?.registerAudioPlaybackCallback(playbackCallback, handler)
        lastOwn = weOwnBuds()
        handler.postDelayed(ticker, POLL_MS)
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(ticker)
        stopPauseEnforcer()
        abandonAudioFocusIfHeld()
        try {
            sessionManager?.removeOnActiveSessionsChangedListener(sessionsChanged)
        } catch (_: Exception) {}
        audioManager?.unregisterAudioPlaybackCallback(playbackCallback)
    }

    fun noteManualSwitch() {
        suppressUntilMs = SystemClock.elapsedRealtime() + MANUAL_SUPPRESS_MS
    }

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            if (running) handler.postDelayed(this, POLL_MS)
        }
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        val playing = computePlaying()
        val own = weOwnBuds()

        if (playing) {
            lastPlayingPackages.clear()
            lastPlayingPackages.addAll(currentPlayingPackages())
        }

        if (lastOwn && !own) {
            suppressUntilMs = now + LOSS_SUPPRESS_MS
            if (lastPlaying && !havePausedMedia) {
                pausePhoneMediaForLoss()
                Log.i(TAG, "buds left this phone while playing → remember ${pausedPackages.size} pkg")
                havePausedMedia = true
                pausedAtMs = now
            }
            grabbing = false
            grabbedFromPeer = false
        }
        if (!lastOwn && own) {
            gainedAtMs = now
            if (!grabbing) grabbedFromPeer = false
        }
        lastOwn = own

        if (playing && !lastPlaying && !havePausedMedia) {
            localPlayEpochMono = now
        } else if (!playing && !havePausedMedia) {
            localPlayEpochMono = 0L
        }

        if (havePausedMedia && !own && playing) {
            reSilenceLeakingMedia()
        }

        if (own && outputReady()) {
            if (outputReadySinceMs == 0L) outputReadySinceMs = now
        } else {
            outputReadySinceMs = 0L
        }
        val outputSettled =
            outputReadySinceMs != 0L && now - outputReadySinceMs >= OUTPUT_SETTLE_MS

        if (havePausedMedia) {
            if (!grabbing && !own) {
                val peerStillWinner = peerPlaying && peerPlayEpochMono != 0L &&
                    (localPlayEpochMono == 0L || peerPlayEpochMono > localPlayEpochMono)
                if (peerStillWinner) pausedAtMs = now
            }
            val limit = when {
                grabbing -> RESUME_TIMEOUT_MS
                grabLate -> GRAB_LATE_WINDOW_MS
                else -> LOSS_RESUME_TIMEOUT_MS
            }
            if (own && outputReady() && outputSettled) {
                resumePhoneMedia()
                havePausedMedia = false
                grabbing = false
                grabLate = false
            } else if (now - pausedAtMs > limit) {
                if (grabbing) {
                    grabbing = false
                    grabLate = true
                    pausedAtMs = now
                    Log.w(TAG, "buds slow to arrive; holding pause for a late resume")
                } else {
                    Log.w(TAG, "hand-off resume timeout; staying paused (sound only in earbuds)")
                    havePausedMedia = false
                    grabLate = false
                    pausedPackages.clear()
                }
            }
        }

        if (playing && !own) {
            maybeGrab(now)
        }

        if (playing) {
            returnTimerAt = 0L
        } else if (returnTimerAt == 0L && own && grabbedFromPeer &&
            now - gainedAtMs >= RETURN_GRACE_MS
        ) {
            returnTimerAt = now
        }
        if (returnTimerAt != 0L && own && !playing &&
            now - returnTimerAt >= RETURN_DELAY_MS
        ) {
            returnTimerAt = 0L
            if (grabbedFromPeer && smartSwitchEnabled && !isCallActive() &&
                manualPreferredOwner != "phone"
            ) {
                Log.i(TAG, "media stopped ${RETURN_DELAY_MS}ms ago → hand grabbed buds back to laptop")
                grabbedFromPeer = false
                requestReturnToPeer()
            }
        }
        if (playing != lastPlaying) {
            MediaNotificationListenerService.rescanMediaPills()
        }
        lastPlaying = playing

        val advertised = grabbing || (playing && own)
        if (advertised != lastAdvertised) {
            lastAdvertised = advertised
            onMediaPlayingChanged(advertised)
        }
    }

    private fun maybeGrab(now: Long) {
        when (decideGrab(
            smartSwitchEnabled, isCallActive(), now, suppressUntilMs, manualPreferredOwner,
            weOwnBuds(), peerHoldsBuds(), peerPlaying, peerPlayEpochMono, localPlayEpochMono,
            lastAutoGrabMs,
        )) {
            GrabDecision.SKIP -> return
            GrabDecision.YIELD -> {
                if (!havePausedMedia) {
                    pausePhoneMedia()
                    havePausedMedia = true
                    pausedAtMs = now
                    grabbing = false
                    Log.i(TAG, "peer played more recently → yield buds + pause local media")
                } else {
                    pausedPackages.addAll(lastPlayingPackages)
                }
                return
            }
            GrabDecision.GRAB -> {}
        }
        if (!havePausedMedia) {
            Log.i(TAG, "media on phone & buds elsewhere → pause + grab to phone")
            pausePhoneMedia()
            havePausedMedia = true
        } else {
            pausedPackages.addAll(lastPlayingPackages)
        }
        pausedAtMs = now
        grabbing = true
        grabLate = false
        grabbedFromPeer = true
        if (requestGrab()) {
            lastAutoGrabMs = now
        }
    }

    private fun outputReady(): Boolean {
        val am = audioManager ?: return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
    }

    private fun pausePhoneMedia() {
        pausedPackages.clear()
        val sessions = activeSessions()
        for (c in sessions) {
            if (c.isPlaying()) {
                try { c.transportControls.pause() } catch (_: Exception) {}
                c.packageName?.let { pausedPackages.add(it) }
            }
        }
        if (pausedPackages.isEmpty() && audioManager?.isMusicActive == true) {
            audioFocusHeldForHandoff = requestAudioFocusForHandoff()
        }
        startPauseEnforcer()
    }

    private fun currentPlayingPackages(): Set<String> =
        activeSessions()
            .filter { it.isPlaying() }
            .mapNotNull { it.packageName?.trim()?.takeIf { p -> p.isNotEmpty() } }
            .toSet()

    private fun pausePhoneMediaForLoss() {
        pausedPackages.clear()
        pausedPackages.addAll(lastPlayingPackages)
        for (c in activeSessions()) {
            val pkg = c.packageName?.trim().orEmpty()
            if (pkg.isNotEmpty() && pkg in lastPlayingPackages && c.isPlaying()) {
                try { c.transportControls.pause() } catch (_: Exception) {}
            }
        }
        if (pausedPackages.isEmpty() && audioManager?.isMusicActive == true) {
            audioFocusHeldForHandoff = requestAudioFocusForHandoff()
        }
        startPauseEnforcer()
    }

    private fun reSilenceLeakingMedia() {
        var anySession = false
        for (c in activeSessions()) {
            if (c.isPlaying()) {
                anySession = true
                try { c.transportControls.pause() } catch (_: Exception) {}
            }
        }
        if (!anySession && audioManager?.isMusicActive == true && !audioFocusHeldForHandoff) {
            audioFocusHeldForHandoff = requestAudioFocusForHandoff()
        }
    }

    private fun startPauseEnforcer() {
        enforcerStartedMs = SystemClock.elapsedRealtime()
        enforcerActive = true
        handler.removeCallbacks(enforcer)
        handler.post(enforcer)
    }

    private fun stopPauseEnforcer() {
        enforcerActive = false
        handler.removeCallbacks(enforcer)
    }

    private val enforcer = object : Runnable {
        override fun run() {
            if (!enforcerActive) return
            val elapsed = SystemClock.elapsedRealtime() - enforcerStartedMs
            if (weOwnBuds() || elapsed >= ENFORCER_TIMEOUT_MS) {
                enforcerActive = false
                return
            }
            if (pausedPackages.isNotEmpty()) {
                for (c in activeSessions()) {
                    val pkg = c.packageName?.trim().orEmpty()
                    if (pkg.isNotEmpty() && pkg in pausedPackages) {
                        try { c.transportControls.pause() } catch (_: Exception) {}
                    }
                }
            }
            handler.postDelayed(this, ENFORCER_TICK_MS)
        }
    }

    private fun resumePhoneMedia() {
        stopPauseEnforcer()
        val hadFocus = audioFocusHeldForHandoff
        if (hadFocus) abandonAudioFocusIfHeld()
        val wanted = pausedPackages.toSet()
        pausedPackages.clear()

        fun playPackages(targets: Set<String>): Int {
            var resumed = 0
            for (c in activeSessions()) {
                val pkg = c.packageName?.trim().orEmpty()
                if (pkg.isEmpty() || pkg !in targets) continue
                try { c.transportControls.play(); resumed++ } catch (_: Exception) {}
            }
            return resumed
        }

        fun mediaKeyFallback() {
            audioManager?.dispatchMediaKeyEvent(
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY),
            )
            audioManager?.dispatchMediaKeyEvent(
                KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY),
            )
        }

        for (delay in RESUME_REPLAY_CHECKPOINTS_MS) {
            handler.postDelayed({
                if (wanted.isEmpty()) {
                    if (!computePlaying()) mediaKeyFallback()
                } else {
                    val notPlaying = wanted - currentPlayingPackages()
                    if (notPlaying.isNotEmpty()) {
                        val resumed = playPackages(notPlaying)
                        if (resumed == 0) mediaKeyFallback()
                        else Log.i(TAG, "resumed $resumed media session(s) on phone")
                    }
                }
            }, delay)
        }
    }

    private fun requestAudioFocusForHandoff(): Boolean {
        val am = audioManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setOnAudioFocusChangeListener {}
                .build()
            audioFocusRequest = req
            am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocusIfHeld() {
        if (!audioFocusHeldForHandoff) return
        audioFocusHeldForHandoff = false
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
    }

    private fun activeSessions(): List<MediaController> {
        if (!sessionPathOk) return emptyList()
        return try {
            (sessionManager?.getActiveSessions(componentName) ?: emptyList())
                .filter { it.packageName != context.packageName }
        } catch (e: SecurityException) {
            sessionPathOk = false
            emptyList()
        }
    }

    private fun computePlaying(): Boolean = audioManager?.isMusicActive == true

    private fun MediaController.isPlaying(): Boolean {
        val s = playbackState?.state ?: return false
        return s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING
    }

    companion object {
        enum class GrabDecision { SKIP, YIELD, GRAB }

        fun decideGrab(
            smartSwitchEnabled: Boolean,
            callActive: Boolean,
            now: Long,
            suppressUntilMs: Long,
            manualPreferredOwner: String?,
            weOwnBuds: Boolean,
            peerHoldsBuds: Boolean,
            peerPlaying: Boolean,
            peerPlayEpochMono: Long,
            localPlayEpochMono: Long,
            lastAutoGrabMs: Long,
            cooldownMs: Long = GRAB_COOLDOWN_MS,
        ): GrabDecision {
            if (!smartSwitchEnabled) return GrabDecision.SKIP
            if (callActive) return GrabDecision.SKIP
            if (now < suppressUntilMs) return GrabDecision.SKIP
            if (manualPreferredOwner == "laptop") return GrabDecision.SKIP
            if (weOwnBuds) return GrabDecision.SKIP
            if (!peerHoldsBuds) return GrabDecision.SKIP
            if (peerPlaying && peerPlayEpochMono != 0L && localPlayEpochMono != 0L &&
                peerPlayEpochMono > localPlayEpochMono
            ) {
                return GrabDecision.YIELD
            }
            if (now - lastAutoGrabMs < cooldownMs) return GrabDecision.SKIP
            return GrabDecision.GRAB
        }

        private const val TAG = "MediaHandoff"

        private const val GRAB_COOLDOWN_MS = 4_000L
        private const val LOSS_SUPPRESS_MS = 4_000L
        private const val MANUAL_SUPPRESS_MS = 8_000L
        private const val POLL_MS = 150L
        private const val RETURN_DELAY_MS = 2_000L
        private const val RETURN_GRACE_MS = 3_000L
        private const val RESUME_TIMEOUT_MS = 6_000L
        private const val OUTPUT_SETTLE_MS = 1_200L
        private const val LOSS_RESUME_TIMEOUT_MS = 90_000L
        private const val GRAB_LATE_WINDOW_MS = 15_000L
        private const val ENFORCER_TICK_MS = 120L
        private const val ENFORCER_TIMEOUT_MS = 2_800L
        private val RESUME_REPLAY_CHECKPOINTS_MS =
            longArrayOf(0L, 300L, 600L, 1_000L, 1_500L, 2_200L, 3_000L, 4_000L, 5_000L, 6_000L, 7_000L)
    }
}
