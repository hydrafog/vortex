package com.vortex.a3.service

import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull


internal class PendingOffer(
    val token: String,
    val name: String,
    val offer: ByteArray,
    val seq: Long = 0L,
) {
    var attempts: Int = 0

    var lastSentAtMs: Long = 0L

    var deadlineFromMs: Long = 0L
}

internal suspend fun VortexStack.offerFileToLaptop(token: String, name: String, offer: ByteArray) {
    val pending = PendingOffer(token, name, offer, seq = ++offerSeq)
    pendingOffers[token] = pending
    if (!tryDeliverOffer(pending)) {
        Log.w(
            VortexStack.TAG,
            "file offer for '$name' couldn't go out (BLE link down?); retrying",
        )
        if (!offerUnreachableToasted) {
            offerUnreachableToasted = true
            toastOffer("Laptop unreachable — keeping the file(s) queued")
        }
    }
    startOfferWatchdog()
}

private suspend fun VortexStack.tryDeliverOffer(pending: PendingOffer): Boolean {
    pending.attempts++
    val server = gattServer ?: return false
    var delivered = false
    for (peer in peerStore.list()) {
        if (server.sendClipboardImageOfferEncrypted(peer.peerStaticPub, pending.offer)) {
            delivered = true
        }
    }
    if (!delivered) return false
    val now = android.os.SystemClock.elapsedRealtime()
    pending.lastSentAtMs = now
    if (pending.deadlineFromMs == 0L) pending.deadlineFromMs = now
    offerUnreachableToasted = false
    Log.i(
        VortexStack.TAG,
        "file offer for '${pending.name}' sent (attempt ${pending.attempts})",
    )
    kotlinx.coroutines.delay(OFFER_PACING_MS)
    scheduleLanWarm()
    return true
}

private fun VortexStack.scheduleLanWarm() {
    lanWarmJob?.cancel()
    lanWarmJob = scope.launch {
        kotlinx.coroutines.delay(LAN_WARM_SETTLE_MS)
        if (lanServer?.keepLanHot() == true) {
            if (android.os.SystemClock.elapsedRealtime() - lastBleStatePushAtMs
                >= STATE_PUSH_DEDUP_MS
            ) {
                pushStateViaBle()
            }
        }
    }
}

internal fun VortexStack.noteFileServed(token: String) {
    val done = pendingOffers.remove(token) ?: return
    Log.i(VortexStack.TAG, "file '${done.name}' fetched by the laptop")
    toastOffer("File sent: ${done.name}")
    val now = android.os.SystemClock.elapsedRealtime()
    for (still in pendingOffers.values) {
        if (still.deadlineFromMs != 0L) still.deadlineFromMs = now
    }
    val dropped = offersPresumedDropped(pendingOffers.values, done.seq)
    for (lost in dropped) {
        lost.lastSentAtMs = 0L
        lost.attempts = 0
        Log.w(
            VortexStack.TAG,
            "offer for '${lost.name}' was dropped in flight (the laptop fetched a " +
                "later one first); re-announcing now",
        )
    }
    if (dropped.isNotEmpty()) kickOfferRetry()
}

internal fun offersPresumedDropped(
    pending: Collection<PendingOffer>,
    fetchedSeq: Long,
): List<PendingOffer> = pending.filter { it.lastSentAtMs != 0L && it.seq < fetchedSeq }

internal fun VortexStack.kickOfferRetry() {
    if (pendingOffers.isEmpty()) return
    offerRetryKick.trySend(Unit)
}

private fun VortexStack.startOfferWatchdog() {
    if (offerRetryJob?.isActive == true) return
    offerRetryJob = scope.launch { offerWatchdog() }
}

private suspend fun VortexStack.offerWatchdog() {
    while (pendingOffers.isNotEmpty()) {
        withTimeoutOrNull(OFFER_RETRY_TICK_MS) { offerRetryKick.receive() }
        val now = android.os.SystemClock.elapsedRealtime()
        val lost = mutableListOf<String>()
        for (pending in pendingOffers.values.toList()) {
            if (offerVerdict(pending, now) == OfferVerdict.DELIVER && tryDeliverOffer(pending)) {
                continue
            }
            if (offerVerdict(pending, now) != OfferVerdict.GIVE_UP) continue
            pendingOffers.remove(pending.token)
            lost += pending.name
            Log.w(VortexStack.TAG, "giving up on '${pending.name}': ${giveUpReason(pending)}")
        }
        if (lost.isNotEmpty()) toastOffersLost(lost)
    }
}

private fun VortexStack.toastOffersLost(lost: List<String>) {
    toastOffer(
        if (lost.size == 1) {
            "Laptop didn't get '${lost.first()}'"
        } else {
            "Laptop didn't get ${lost.size} files"
        },
    )
}

private fun VortexStack.toastOffer(msg: String) {
    android.os.Handler(android.os.Looper.getMainLooper()).post {
        try {
            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
        } catch (t: Throwable) {
            Log.w(VortexStack.TAG, "offer-failure toast suppressed: ${t.message}")
        }
    }
}

internal enum class OfferVerdict {
    DELIVER,

    WAIT,

    GIVE_UP,
}

internal fun offerVerdict(pending: PendingOffer, nowMs: Long): OfferVerdict = when {
    pending.lastSentAtMs == 0L ->
        if (pending.attempts >= OFFER_MAX_ATTEMPTS) OfferVerdict.GIVE_UP else OfferVerdict.DELIVER
    nowMs - pending.deadlineFromMs >= OFFER_PULL_GRACE_MS -> OfferVerdict.GIVE_UP
    nowMs - pending.lastSentAtMs >= OFFER_RESEND_MS -> OfferVerdict.DELIVER
    else -> OfferVerdict.WAIT
}

internal fun giveUpReason(pending: PendingOffer): String =
    if (pending.lastSentAtMs == 0L) {
        "the offer never reached the laptop in ${pending.attempts} attempts"
    } else {
        "the laptop accepted the offer but never fetched it " +
            "(asleep, no LAN route, or declined)"
    }

internal const val OFFER_RETRY_TICK_MS = 3_000L

internal const val OFFER_MAX_ATTEMPTS = 20

internal const val OFFER_PULL_GRACE_MS = 120_000L

internal const val OFFER_PACING_MS = 60L

internal const val OFFER_RESEND_MS = 30_000L

internal const val LAN_WARM_SETTLE_MS = 250L

internal const val STATE_PUSH_DEDUP_MS = 5_000L

internal fun newOfferRetryKick(): Channel<Unit> = Channel(Channel.CONFLATED)
