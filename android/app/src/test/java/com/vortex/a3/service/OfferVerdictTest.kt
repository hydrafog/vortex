package com.vortex.a3.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OfferVerdictTest {

    private fun offer(
        attempts: Int = 0,
        lastSentAtMs: Long = 0L,
        deadlineFromMs: Long = lastSentAtMs,
    ) = PendingOffer("tok", "file.bin", ByteArray(0)).also {
        it.attempts = attempts
        it.lastSentAtMs = lastSentAtMs
        it.deadlineFromMs = deadlineFromMs
    }

    @Test
    fun `an offer that never went out is retried until its budget runs out`() {
        assertEquals(OfferVerdict.DELIVER, offerVerdict(offer(attempts = 0), 0L))
        assertEquals(
            OfferVerdict.DELIVER,
            offerVerdict(offer(attempts = OFFER_MAX_ATTEMPTS - 1), 0L),
        )
        assertEquals(
            OfferVerdict.GIVE_UP,
            offerVerdict(offer(attempts = OFFER_MAX_ATTEMPTS), 0L),
        )
    }

    @Test
    fun `a sent offer waits for the pull`() {
        val sent = offer(attempts = 1, lastSentAtMs = 1_000L)
        assertEquals(OfferVerdict.WAIT, offerVerdict(sent, 1_000L))
        assertEquals(OfferVerdict.WAIT, offerVerdict(sent, 1_000L + OFFER_RESEND_MS - 1))
    }

    @Test
    fun `a sent offer nothing came to collect is re-announced`() {
        val sent = offer(attempts = 1, lastSentAtMs = 1_000L)
        assertEquals(OfferVerdict.DELIVER, offerVerdict(sent, 1_000L + OFFER_RESEND_MS))
    }

    @Test
    fun `re-announcing does not postpone giving up`() {
        val stubborn = offer(attempts = 5, lastSentAtMs = 119_000L, deadlineFromMs = 0L)
        assertEquals(OfferVerdict.GIVE_UP, offerVerdict(stubborn, OFFER_PULL_GRACE_MS))
    }

    @Test
    fun `progress in the batch slides the deadline and buys more time`() {
        val queued = offer(attempts = 1, lastSentAtMs = 1_000L, deadlineFromMs = 1_000L)
        val past = 1_000L + OFFER_PULL_GRACE_MS
        assertEquals(OfferVerdict.GIVE_UP, offerVerdict(queued, past))
        queued.deadlineFromMs = past
        queued.lastSentAtMs = past
        assertEquals(OfferVerdict.WAIT, offerVerdict(queued, past))
    }

    @Test
    fun `send attempts stop mattering once it has gone out`() {
        val sent = offer(attempts = OFFER_MAX_ATTEMPTS, lastSentAtMs = 500L)
        assertEquals(OfferVerdict.WAIT, offerVerdict(sent, 500L))
    }

    @Test
    fun `the give-up reason distinguishes never-sent from never-fetched`() {
        assertEquals(
            "the offer never reached the laptop in 20 attempts",
            giveUpReason(offer(attempts = 20)),
        )
        assertTrue(
            giveUpReason(offer(attempts = 1, lastSentAtMs = 5L)).contains("never fetched it"),
        )
    }
}

class PresumedDroppedTest {

    private fun offer(seq: Long, lastSentAtMs: Long) =
        PendingOffer("tok$seq", "file$seq.bin", ByteArray(0), seq = seq).also {
            it.lastSentAtMs = lastSentAtMs
            it.deadlineFromMs = lastSentAtMs
        }

    @Test
    fun `an earlier offer skipped over was dropped in flight`() {
        val first = offer(seq = 1, lastSentAtMs = 100L)
        val third = offer(seq = 3, lastSentAtMs = 100L)
        val dropped = offersPresumedDropped(listOf(first, third), fetchedSeq = 2)
        assertEquals(listOf(first), dropped)
    }

    @Test
    fun `a later offer is simply waiting its turn`() {
        val third = offer(seq = 3, lastSentAtMs = 100L)
        assertEquals(emptyList<PendingOffer>(), offersPresumedDropped(listOf(third), 2))
    }

    @Test
    fun `an offer that never went out is left to the send retry`() {
        val unsent = offer(seq = 1, lastSentAtMs = 0L)
        assertEquals(emptyList<PendingOffer>(), offersPresumedDropped(listOf(unsent), 2))
    }
}
