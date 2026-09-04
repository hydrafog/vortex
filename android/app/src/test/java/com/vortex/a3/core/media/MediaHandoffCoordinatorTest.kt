package com.vortex.a3.core.media

import com.vortex.a3.core.media.MediaHandoffCoordinator.Companion.GrabDecision
import com.vortex.a3.core.media.MediaHandoffCoordinator.Companion.decideGrab
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MediaHandoffCoordinatorTest {

    private fun decide(
        smartSwitchEnabled: Boolean = true,
        callActive: Boolean = false,
        now: Long = 10_000L,
        suppressUntilMs: Long = 0L,
        manualPreferredOwner: String? = null,
        weOwnBuds: Boolean = false,
        peerHoldsBuds: Boolean = true,
        peerPlaying: Boolean = false,
        peerPlayEpochMono: Long = 0L,
        localPlayEpochMono: Long = 0L,
        lastAutoGrabMs: Long = 0L,
        cooldownMs: Long = 4_000L,
    ): GrabDecision = decideGrab(
        smartSwitchEnabled, callActive, now, suppressUntilMs, manualPreferredOwner,
        weOwnBuds, peerHoldsBuds, peerPlaying, peerPlayEpochMono, localPlayEpochMono,
        lastAutoGrabMs, cooldownMs,
    )

    @Test
    fun `baseline grabs`() {
        assertEquals(GrabDecision.GRAB, decide())
    }

    @Test
    fun `smart-switch off never grabs`() {
        assertEquals(GrabDecision.SKIP, decide(smartSwitchEnabled = false))
    }

    @Test
    fun `an active call blocks the grab (calls are priority 1)`() {
        assertEquals(GrabDecision.SKIP, decide(callActive = true))
    }

    @Test
    fun `loss-suppress window blocks the grab`() {
        assertEquals(GrabDecision.SKIP, decide(now = 500L, suppressUntilMs = 1_000L))
    }

    @Test
    fun `manual pin to laptop blocks the grab`() {
        assertEquals(
            GrabDecision.SKIP,
            decide(
                manualPreferredOwner = "laptop",
                peerPlaying = true, peerPlayEpochMono = 100L, localPlayEpochMono = 200L,
            ),
        )
    }

    @Test
    fun `manual pin to phone does not block the grab`() {
        assertEquals(GrabDecision.GRAB, decide(manualPreferredOwner = "phone"))
    }

    @Test
    fun `already owning the buds is a no-op`() {
        assertEquals(GrabDecision.SKIP, decide(weOwnBuds = true))
    }

    @Test
    fun `nothing to grab when the peer does not hold the buds`() {
        assertEquals(GrabDecision.SKIP, decide(peerHoldsBuds = false))
    }

    @Test
    fun `peer played more recently yields`() {
        assertEquals(
            GrabDecision.YIELD,
            decide(peerPlaying = true, peerPlayEpochMono = 200L, localPlayEpochMono = 100L),
        )
    }

    @Test
    fun `we played more recently grabs`() {
        assertEquals(
            GrabDecision.GRAB,
            decide(peerPlaying = true, peerPlayEpochMono = 100L, localPlayEpochMono = 200L),
        )
    }

    @Test
    fun `missing epoch on either side disables yield (grabs)`() {
        assertEquals(
            GrabDecision.GRAB,
            decide(peerPlaying = true, peerPlayEpochMono = 500L, localPlayEpochMono = 0L),
        )
    }

    @Test
    fun `cooldown blocks a rapid re-grab`() {
        assertEquals(
            GrabDecision.SKIP,
            decide(now = 1_100L, lastAutoGrabMs = 500L, cooldownMs = 4_000L),
        )
    }

    @Test
    fun `grab is allowed once the cooldown elapses`() {
        assertEquals(
            GrabDecision.GRAB,
            decide(now = 5_000L, lastAutoGrabMs = 500L, cooldownMs = 4_000L),
        )
    }
}
