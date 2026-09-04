package com.vortex.a3.core.media

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.vortex.a3.core.notif.LiveActivity
import com.vortex.a3.core.notif.NotificationMirror
import com.vortex.a3.core.notif.NotificationMirrorSetting
import com.vortex.a3.service.VortexService

class MediaNotificationListenerService : NotificationListenerService() {

    private val recentSends = LinkedHashMap<String, Long>()

    private val mirroredKeys = HashSet<String>()

    private val liveKeys = HashSet<String>()
    private val liveRecentMs = HashMap<String, Long>()
    private val liveContent = HashMap<String, String>()

    private val liveHeartbeatHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val liveHeartbeat = object : Runnable {
        override fun run() {
            if (NotificationMirrorSetting.isEnabled()) {
                for (live in activeLive.values) {
                    VortexService.liveActivityBus.tryEmit(live)
                }
            }
            liveHeartbeatHandler.postDelayed(this, LIVE_HEARTBEAT_MS)
        }
    }

    private val callPollHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val callPoll = object : Runnable {
        override fun run() {
            val cur = VortexService.currentCall
            if (cur == null || cur.phase != com.vortex.a3.core.call.CallEvent.PHASE_ACTIVE ||
                !cur.outgoing || cur.connected
            ) {
                return
            }
            try {
                activeNotifications?.firstOrNull { isCallNotification(it) }
                    ?.let { trackCallNotification(it) }
            } catch (_: Throwable) {
            }
            val now = VortexService.currentCall
            if (now != null && now.phase == com.vortex.a3.core.call.CallEvent.PHASE_ACTIVE &&
                now.outgoing && !now.connected
            ) {
                callPollHandler.postDelayed(this, 1500)
            }
        }
    }

    private fun maybeStartCallPoll() {
        val cur = VortexService.currentCall ?: return
        if (cur.phase == com.vortex.a3.core.call.CallEvent.PHASE_ACTIVE && cur.outgoing && !cur.connected) {
            callPollHandler.removeCallbacks(callPoll)
            callPollHandler.postDelayed(callPoll, 1500)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val sbn = sbn ?: return
        try {
            trackCallNotification(sbn)
            maybeStartCallPoll()
            if (handleLiveActivity(sbn)) return
            val mirror = buildMirror(sbn) ?: return
            val key = "${mirror.app}|${mirror.title}|${mirror.text}"
            val now = android.os.SystemClock.elapsedRealtime()
            val last = recentSends[key]
            if (last != null && now - last < DEDUP_WINDOW_MS) return
            recentSends[key] = now
            pruneRecent(now)
            if (mirror.key.isNotEmpty()) {
                synchronized(mirroredKeys) { mirroredKeys.add(mirror.key) }
                persistMirroredKeys()
            }
            VortexService.notificationBus.tryEmit(mirror)
        } catch (t: Throwable) {
            Log.w(TAG, "onNotificationPosted: ${t.message}")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val key = sbn?.key ?: return
        handleCallRemoved(key)
        val wasLive = synchronized(liveKeys) {
            val had = liveKeys.remove(key)
            if (had) { liveRecentMs.remove(key); liveContent.remove(key) }
            had
        }
        activeLive.remove(key)
        if (wasLive) {
            if (NotificationMirrorSetting.isEnabled()) {
                VortexService.liveActivityBus.tryEmit(LiveActivity(key = key, ended = true))
            }
            return
        }
        val wasMirrored = synchronized(mirroredKeys) { mirroredKeys.remove(key) }
        if (wasMirrored) persistMirroredKeys()
        if (!wasMirrored) return
        if (!NotificationMirrorSetting.isEnabled()) return
        VortexService.notificationBus.tryEmit(
            NotificationMirror(app = "", title = "", text = "", ts = 0L, key = key, dismiss = true),
        )
    }

    override fun onListenerConnected() {
        instance = this
        try {
            val restored = com.vortex.a3.core.notif.MirroredKeysStore.load(this)
            if (restored.isNotEmpty()) {
                synchronized(mirroredKeys) { mirroredKeys.addAll(restored) }
                Log.i(TAG, "restored ${restored.size} mirrored keys from disk")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "restore mirrored keys: ${t.message}")
        }
        try {
            activeNotifications?.forEach { handleLiveActivity(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "seed active live activities: ${t.message}")
        }
        try {
            activeNotifications?.firstOrNull { isCallNotification(it) }?.let { trackCallNotification(it) }
            maybeStartCallPoll()
        } catch (t: Throwable) {
            Log.w(TAG, "seed call connect: ${t.message}")
        }
        liveHeartbeatHandler.postDelayed({
            try {
                val now = System.currentTimeMillis()
                activeNotifications
                    ?.filter { sbn ->
                        now - sbn.postTime < CATCHUP_WINDOW_MS &&
                            synchronized(mirroredKeys) { sbn.key !in mirroredKeys }
                    }
                    ?.sortedBy { it.postTime }
                    ?.takeLast(CATCHUP_MAX)
                    ?.forEach { sbn ->
                        val mirror = buildMirror(sbn) ?: return@forEach
                        if (mirror.key.isNotEmpty()) {
                            synchronized(mirroredKeys) { mirroredKeys.add(mirror.key) }
                            persistMirroredKeys()
                        }
                        Log.i(TAG, "catch-up mirror after listener gap: ${mirror.app}")
                        VortexService.notificationBus.tryEmit(mirror)
                    }
            } catch (t: Throwable) {
                Log.w(TAG, "catch-up mirror: ${t.message}")
            }
        }, CATCHUP_DELAY_MS)
        liveHeartbeatHandler.removeCallbacks(liveHeartbeat)
        liveHeartbeatHandler.postDelayed(liveHeartbeat, LIVE_HEARTBEAT_MS)
    }

    override fun onListenerDisconnected() {
        liveHeartbeatHandler.removeCallbacks(liveHeartbeat)
        callPollHandler.removeCallbacks(callPoll)
        if (instance === this) instance = null
        try {
            requestRebind(android.content.ComponentName(this, MediaNotificationListenerService::class.java))
        } catch (_: Throwable) {
        }
    }

    private fun persistMirroredKeys() {
        val snapshot = synchronized(mirroredKeys) { mirroredKeys.toSet() }
        com.vortex.a3.core.notif.MirroredKeysStore.save(this, snapshot)
    }

    private fun pruneRecent(now: Long) {
        val it = recentSends.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value > DEDUP_WINDOW_MS) it.remove()
        }
        while (recentSends.size > MAX_RECENT) {
            val first = recentSends.entries.iterator()
            if (first.hasNext()) { first.next(); first.remove() } else break
        }
    }

    private fun buildMirror(sbn: StatusBarNotification): NotificationMirror? {
        if (sbn.packageName == packageName) return null

        val n = sbn.notification ?: return null
        val flags = n.flags
        if (!sbn.isClearable) return null
        if (flags and Notification.FLAG_ONGOING_EVENT != 0) return null
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return null

        val extras = n.extras ?: return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extractBody(extras)
        if (title.isEmpty() && text.isEmpty()) return null

        val rawActions = n.actions?.filter { it.title?.toString()?.trim()?.isNotEmpty() == true }
            ?.take(MAX_ACTIONS)
            ?: emptyList()
        val actions = rawActions.map { it.title?.toString()?.trim().orEmpty() }
        val replyIndex = rawActions.indexOfFirst { !it.remoteInputs.isNullOrEmpty() }

        return NotificationMirror(
            app = appLabel(sbn.packageName),
            appId = sbn.packageName,
            title = normalize(title).take(MAX_TITLE),
            text = text.take(MAX_TEXT),
            ts = System.currentTimeMillis(),
            key = sbn.key ?: "",
            actions = actions,
            replyIndex = replyIndex,
        )
    }

    private fun handleLiveActivity(sbn: StatusBarNotification): Boolean {
        val live = buildLiveActivity(sbn)
        if (live == null) {
            val key = sbn.key
            if (key != null && activeLive.remove(key) != null) {
                synchronized(liveKeys) {
                    liveKeys.remove(key); liveRecentMs.remove(key); liveContent.remove(key)
                }
                if (NotificationMirrorSetting.isEnabled()) {
                    VortexService.liveActivityBus.tryEmit(LiveActivity(key = key, ended = true))
                }
                return true
            }
            return false
        }
        activeLive[live.key] = live
        if (!NotificationMirrorSetting.isEnabled()) return true
        val key = live.key
        val content = "${live.title}|${live.text}|${live.progress}|${live.playing}"
        val now = android.os.SystemClock.elapsedRealtime()
        val emit = synchronized(liveKeys) {
            liveKeys.add(key)
            val last = liveRecentMs[key]
            when {
                liveContent[key] == content -> false
                live.playing != null &&
                    liveContent[key]?.substringBeforeLast('|') ==
                    content.substringBeforeLast('|') -> {
                    liveRecentMs[key] = now
                    liveContent[key] = content
                    true
                }
                last != null && now - last < LIVE_UPDATE_MS -> false
                else -> {
                    liveRecentMs[key] = now
                    liveContent[key] = content
                    true
                }
            }
        }
        if (emit) VortexService.liveActivityBus.tryEmit(live)
        return true
    }

    private fun buildLiveActivity(sbn: StatusBarNotification): LiveActivity? {
        if (sbn.packageName == packageName) return null
        val n = sbn.notification ?: return null
        val flags = n.flags
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val extras = n.extras ?: return null
        if (extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) {
            return buildMediaLiveActivity(sbn, extras)
        }
        val ongoing = !sbn.isClearable || (flags and Notification.FLAG_ONGOING_EVENT != 0)
        if (!ongoing) return null
        val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val cur = extras.getInt(Notification.EXTRA_PROGRESS, 0)
        val indeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
        val hasProgress = max > 0 || indeterminate
        val liveCategory = n.category in LIVE_CATEGORIES
        if (!hasProgress && !liveCategory) return null
        val appLabel = appLabel(sbn.packageName)
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val sub = normalize(extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty())
        if (title.isEmpty() && text.isEmpty() && sub.isEmpty()) return null
        val progress = if (max > 0) (cur.toLong() * 100 / max).toInt().coerceIn(0, 100) else -1
        val key = sbn.key ?: return null
        return LiveActivity(
            key = key,
            app = appLabel,
            appId = sbn.packageName,
            title = normalize(title).take(MAX_TITLE),
            text = normalize(text).take(MAX_TEXT),
            sub = sub.take(MAX_TITLE),
            progress = progress,
        )
    }

    private fun buildMediaLiveActivity(
        sbn: StatusBarNotification,
        extras: android.os.Bundle,
    ): LiveActivity? {
        val key = sbn.key ?: return null
        val controller = try {
            val msm = getSystemService(MEDIA_SESSION_SERVICE)
                as? android.media.session.MediaSessionManager
            msm?.getActiveSessions(
                android.content.ComponentName(this, MediaNotificationListenerService::class.java),
            )?.firstOrNull { it.packageName == sbn.packageName }
        } catch (_: Exception) {
            null
        }
        val md = controller?.metadata
        val title = md?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val artist = md?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (title.isEmpty() && artist.isEmpty()) return null
        val state = controller?.playbackState?.state
        val playing = state == android.media.session.PlaybackState.STATE_PLAYING ||
            state == android.media.session.PlaybackState.STATE_BUFFERING
        return LiveActivity(
            key = key,
            app = appLabel(sbn.packageName),
            appId = sbn.packageName,
            title = normalize(title).take(MAX_TITLE),
            text = normalize(artist).take(MAX_TITLE),
            playing = playing,
        )
    }

    @Suppress("DEPRECATION")
    private fun extractBody(extras: android.os.Bundle): String {
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.let { lines ->
            val out = lines.mapNotNull { normalize(it.toString()).takeIf { s -> s.isNotEmpty() } }
            if (out.isNotEmpty()) return out.takeLast(MAX_LINES).joinToString("\n")
        }
        extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.let { msgs ->
            val out = msgs.mapNotNull {
                (it as? android.os.Bundle)?.getCharSequence("text")
                    ?.let { t -> normalize(t.toString()) }
                    ?.takeIf { s -> s.isNotEmpty() }
            }
            if (out.isNotEmpty()) return out.takeLast(MAX_LINES).joinToString("\n")
        }
        val single = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        return normalize(single)
    }

    private fun normalize(s: String): String =
        s.replace(Regex("\\s+"), " ").trim()

    private fun appLabel(pkg: String): String = try {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Throwable) {
        pkg
    }

    companion object {
        private const val TAG = "VortexNotifListener"

        @Volatile
        private var instance: MediaNotificationListenerService? = null

        @Volatile
        private var callNotifKey: String? = null

        private fun isCallNotification(sbn: StatusBarNotification): Boolean {
            if (sbn.notification?.category == android.app.Notification.CATEGORY_CALL) return true
            val p = sbn.packageName
            return p.contains("dialer") || p.contains("incallui") ||
                p == "com.android.server.telecom"
        }

        private fun trackCallNotification(sbn: StatusBarNotification) {
            if (!isCallNotification(sbn)) return
            val cur = VortexService.currentCall ?: return
            if (cur.phase == com.vortex.a3.core.call.CallEvent.PHASE_ENDED) return
            callNotifKey = sbn.key
            val n = sbn.notification ?: return
            val chrono = n.extras?.getBoolean(android.app.Notification.EXTRA_SHOW_CHRONOMETER, false) ?: false
            val whenMs = n.`when`
            Log.i(TAG, "call notif: chrono=$chrono when=$whenMs phase=${cur.phase} outgoing=${cur.outgoing} connected=${cur.connected}")
            if (chrono && whenMs > 0L &&
                cur.phase == com.vortex.a3.core.call.CallEvent.PHASE_ACTIVE &&
                !(cur.connected && cur.startedAt == whenMs)
            ) {
                val updated = cur.copy(connected = true, startedAt = whenMs)
                VortexService.currentCall = updated
                VortexService.callEventBus.tryEmit(updated)
                Log.i(TAG, "call connected (dialer chronometer) → started_at=$whenMs")
            }
        }

        private fun handleCallRemoved(key: String) {
            if (key != callNotifKey) return
            callNotifKey = null
            val cur = VortexService.currentCall ?: return
            if (cur.phase == com.vortex.a3.core.call.CallEvent.PHASE_ENDED) return
            val ended = cur.copy(phase = com.vortex.a3.core.call.CallEvent.PHASE_ENDED)
            VortexService.currentCall = null
            VortexService.callEventBus.tryEmit(ended)
            Log.i(TAG, "dialer call notification removed → END")
        }

        private val activeLive = java.util.concurrent.ConcurrentHashMap<String, LiveActivity>()

        fun activeLiveActivities(): List<LiveActivity> = activeLive.values.toList()

        fun rescanMediaPills() {
            val svc = instance ?: return
            try {
                svc.activeNotifications?.forEach { sbn ->
                    if (sbn.notification?.extras
                            ?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true
                    ) {
                        svc.handleLiveActivity(sbn)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "rescanMediaPills: ${t.message}")
            }
        }

        fun resendMissing(knownKeys: Set<String>) {
            if (!com.vortex.a3.core.notif.NotificationMirrorSetting.isEnabled()) return
            val svc = instance ?: return
            try {
                val now = System.currentTimeMillis()
                svc.activeNotifications
                    ?.filter { sbn ->
                        sbn.key !in knownKeys && now - sbn.postTime < CATCHUP_WINDOW_MS
                    }
                    ?.sortedBy { it.postTime }
                    ?.takeLast(CATCHUP_MAX)
                    ?.forEach { sbn ->
                        val mirror = svc.buildMirror(sbn) ?: return@forEach
                        if (mirror.key.isNotEmpty()) {
                            synchronized(svc.mirroredKeys) { svc.mirroredKeys.add(mirror.key) }
                            svc.persistMirroredKeys()
                        }
                        Log.i(TAG, "resend (laptop catch-up): ${mirror.app}")
                        VortexService.notificationBus.tryEmit(mirror)
                    }
            } catch (t: Throwable) {
                Log.w(TAG, "resendMissing: ${t.message}")
            }
        }

        fun invokeAction(key: String, index: Int, reply: String) {
            val svc = instance ?: return
            try {
                val sbn = svc.activeNotifications?.firstOrNull { it.key == key } ?: return
                val action = sbn.notification.actions?.getOrNull(index) ?: return
                val pi = action.actionIntent ?: return
                val inputs = action.remoteInputs
                if (reply.isNotEmpty() && !inputs.isNullOrEmpty()) {
                    val intent = android.content.Intent()
                    val bundle = android.os.Bundle()
                    for (ri in inputs) bundle.putCharSequence(ri.resultKey, reply)
                    android.app.RemoteInput.addResultsToIntent(inputs, intent, bundle)
                    pi.send(svc, 0, intent)
                } else {
                    pi.send()
                }
            } catch (e: Exception) {
                Log.w(TAG, "invokeAction: ${e.message}")
            }
        }

        fun fireCallAction(wantAnswer: Boolean): Boolean {
            val svc = instance ?: return false
            return try {
                val ns = svc.activeNotifications ?: return false
                val callSbn = ns.firstOrNull { sbn ->
                    val n = sbn.notification ?: return@firstOrNull false
                    val isCall = n.category == android.app.Notification.CATEGORY_CALL ||
                        sbn.packageName.contains("dialer") ||
                        sbn.packageName.contains("incallui") ||
                        sbn.packageName == "com.android.server.telecom"
                    isCall && (n.actions?.isNotEmpty() == true)
                } ?: return false
                val actions = callSbn.notification.actions ?: return false
                val answerKw = listOf(
                    "answer", "accept", "pick up", "javob", "qabul",
                    "ответить", "принять", "відповісти",
                )
                val declineKw = listOf(
                    "decline", "reject", "dismiss", "hang", "end call", "end",
                    "rad", "bekor", "tugat", "отклонить", "сбросить", "завершить",
                    "відхилити", "відхилення",
                )
                val kws = if (wantAnswer) answerKw else declineKw
                val match = actions.firstOrNull { a ->
                    val t = a.title?.toString()?.lowercase()?.trim().orEmpty()
                    t.isNotEmpty() && kws.any { t.contains(it) } && a.actionIntent != null
                }
                Log.i(TAG, "fireCallAction(answer=$wantAnswer): actions=${actions.mapNotNull { it.title?.toString() }} matched=${match?.title}")
                val pi = match?.actionIntent ?: return false
                pi.send()
                true
            } catch (e: Exception) {
                Log.w(TAG, "fireCallAction: ${e.message}")
                false
            }
        }

        fun dismissByKey(key: String) {
            val svc = instance ?: return
            val removed = synchronized(svc.mirroredKeys) { svc.mirroredKeys.remove(key) }
            if (removed) svc.persistMirroredKeys()
            try {
                svc.cancelNotification(key)
            } catch (e: Exception) {
                Log.w(TAG, "dismissByKey: ${e.message}")
            }
        }
        private const val MAX_TITLE = 120
        private const val MAX_TEXT = 280
        private const val MAX_ACTIONS = 3
        private const val MAX_LINES = 6
        private const val DEDUP_WINDOW_MS = 4_000L
        private const val LIVE_UPDATE_MS = 1_200L
        private const val LIVE_HEARTBEAT_MS = 25_000L
        private val LIVE_CATEGORIES = setOf(
            Notification.CATEGORY_NAVIGATION,
            Notification.CATEGORY_TRANSPORT,
            Notification.CATEGORY_PROGRESS,
            Notification.CATEGORY_STOPWATCH,
            Notification.CATEGORY_WORKOUT,
            Notification.CATEGORY_LOCATION_SHARING,
        )
        private const val MAX_RECENT = 64
        private const val CATCHUP_WINDOW_MS = 30 * 60 * 1000L
        private const val CATCHUP_MAX = 10
        private const val CATCHUP_DELAY_MS = 4_000L
    }
}
