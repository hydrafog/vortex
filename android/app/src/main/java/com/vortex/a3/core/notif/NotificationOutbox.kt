package com.vortex.a3.core.notif

import android.os.SystemClock
import android.util.Log

class NotificationOutbox {

    private data class Entry(val mirror: NotificationMirror, val queuedAtMs: Long)

    private val queues = HashMap<String, ArrayDeque<Entry>>()
    private val lock = Any()

    fun enqueue(peerHex: String, mirror: NotificationMirror) {
        synchronized(lock) {
            val q = queues.getOrPut(peerHex) { ArrayDeque() }
            if (mirror.dismiss && mirror.key.isNotEmpty()) {
                val before = q.size
                q.removeAll { it.mirror.key == mirror.key && !it.mirror.dismiss }
                if (q.size != before) return
            }
            pruneExpired(q)
            q.addLast(Entry(mirror, SystemClock.elapsedRealtime()))
            while (q.size > MAX_PER_PEER) q.removeFirst()
            Log.i(TAG, "buffered notification for ${peerHex.take(8)}… (queue=${q.size})")
        }
    }

    suspend fun flush(peerHex: String, send: suspend (NotificationMirror) -> Boolean) {
        val pending: List<Entry> = synchronized(lock) {
            val q = queues[peerHex] ?: return
            pruneExpired(q)
            val snapshot = q.toList()
            q.clear()
            snapshot
        }
        if (pending.isEmpty()) return
        Log.i(TAG, "flushing ${pending.size} buffered notification(s) to ${peerHex.take(8)}…")
        val leftover = ArrayList<Entry>()
        var stalled = false
        for (e in pending) {
            if (stalled) { leftover.add(e); continue }
            val ok = try { send(e.mirror) } catch (ex: Exception) {
                Log.w(TAG, "flush send threw: ${ex.message}"); false
            }
            if (!ok) { stalled = true; leftover.add(e) }
        }
        if (leftover.isNotEmpty()) synchronized(lock) {
            val q = queues.getOrPut(peerHex) { ArrayDeque() }
            for (e in leftover.asReversed()) q.addFirst(e)
            while (q.size > MAX_PER_PEER) q.removeFirst()
            Log.i(TAG, "${leftover.size} still pending for ${peerHex.take(8)}… (link not ready)")
        }
    }

    private fun pruneExpired(q: ArrayDeque<Entry>) {
        val now = SystemClock.elapsedRealtime()
        while (q.isNotEmpty() && now - q.first().queuedAtMs > TTL_MS) q.removeFirst()
    }

    companion object {
        private const val TAG = "VortexNotifOutbox"
        private const val MAX_PER_PEER = 50
        private const val TTL_MS = 12 * 60 * 60 * 1000L
    }
}
