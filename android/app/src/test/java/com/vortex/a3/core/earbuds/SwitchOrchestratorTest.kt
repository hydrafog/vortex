package com.vortex.a3.core.earbuds

import com.vortex.a3.core.storage.PeerStore
import com.vortex.a3.core.storage.TrustedPeer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

class SwitchOrchestratorTest {

    private val peerPub = ByteArray(32) { (it + 1).toByte() }
    private val mac = "AC:47:1B:25:71:C2"

    @Test
    fun `happy path - initiator request reaches Idle after connect`() = runBlocking {
        val ctrl = FakeController(connectOk = true)
        val store = FakePeerStore()
        val sent = ConcurrentLinkedQueue<AudioOpFrame>()
        val orch = SwitchOrchestrator(
            controller = ctrl,
            peerStore = store,
            sender = { _, f -> sent.add(f); Result.success(Unit) },
        )

        assertTrue(orch.request(peerPub, mac))
        waitFor { sent.any { it.op is AudioOp.Done } }
        assertEquals(SwitchState.Idle, orch.state.value)
        assertTrue(ctrl.connectCount.get() >= 1L, "controller.connect should have fired at least once")
    }

    @Test
    fun `peer Reject ends the initiator flow with Failed then Idle`() = runBlocking {
        val ctrl = FakeController(connectOk = false)
        val store = FakePeerStore()
        val sent = ConcurrentLinkedQueue<AudioOpFrame>()
        val orch = SwitchOrchestrator(ctrl, store, sender = { _, f -> sent.add(f); Result.success(Unit) })

        orch.request(peerPub, mac)
        waitForFrameKind(sent, "request")

        orch.onIncoming(peerPub, AudioOpFrame(2L, AudioOp.Reject(RejectReason.InCall), mac, 0L))

        waitFor { orch.state.value is SwitchState.Failed }
        val s = orch.state.value as SwitchState.Failed
        assertTrue(s.reason.contains("in_call", ignoreCase = true) ||
                   s.reason.contains("InCall", ignoreCase = true))
    }

    @Test
    fun `replay rejection - duplicate nonce on Released is dropped`() = runBlocking {
        val ctrl = FakeController(connectOk = true)
        val store = FakePeerStore()
        val sent = ConcurrentLinkedQueue<AudioOpFrame>()
        val orch = SwitchOrchestrator(ctrl, store, sender = { _, f -> sent.add(f); Result.success(Unit) })

        orch.request(peerPub, mac)
        waitForFrameKind(sent, "request")
        waitFor { sent.any { it.op is AudioOp.Done } }

        orch.onIncoming(peerPub, AudioOpFrame(0L, AudioOp.Released, mac, 0L))
        delay(50)
        assertEquals(SwitchState.Idle, orch.state.value)
    }

    @Test
    fun `responder flow - incoming Request emits Approve then Released`() = runBlocking {
        val ctrl = FakeController(connectOk = true)
        val store = FakePeerStore()
        val sent = ConcurrentLinkedQueue<AudioOpFrame>()
        val orch = SwitchOrchestrator(ctrl, store, sender = { _, f -> sent.add(f); Result.success(Unit) })

        orch.onIncoming(peerPub, AudioOpFrame(10L, AudioOp.Request, mac, 0L))

        waitFor { sent.any { it.op is AudioOp.Released } }
        val ops = sent.toList().map { it.op::class.simpleName }
        val approveIdx = ops.indexOf("Approve")
        val releasedIdx = ops.indexOf("Released")
        assertTrue(approveIdx >= 0)
        assertTrue(releasedIdx > approveIdx, "Approve must come before Released")
        assertEquals(1L, ctrl.disconnectCount.get(), "responder must disconnect once")
    }

    @Test
    fun `concurrent initiator and responder - second request rejected with Busy`() = runBlocking {
        val ctrl = FakeController(connectOk = false)
        val store = FakePeerStore()
        val sent = ConcurrentLinkedQueue<AudioOpFrame>()
        val orch = SwitchOrchestrator(ctrl, store, sender = { _, f -> sent.add(f); Result.success(Unit) })

        orch.request(peerPub, mac)
        waitForFrameKind(sent, "request")

        orch.onIncoming(peerPub, AudioOpFrame(20L, AudioOp.Request, mac, 0L))

        waitFor { sent.any { it.op is AudioOp.Reject } }
        val reject = sent.first { it.op is AudioOp.Reject }.op as AudioOp.Reject
        assertEquals(RejectReason.Busy, reject.reason)
    }

    @Test
    fun `acceptance Reject - external policy denies incoming Request`() = runBlocking {
        val ctrl = FakeController(connectOk = true)
        val store = FakePeerStore()
        val sent = ConcurrentLinkedQueue<AudioOpFrame>()
        val orch = SwitchOrchestrator(
            ctrl, store,
            sender = { _, f -> sent.add(f); Result.success(Unit) },
            acceptanceProvider = { SwitchOrchestrator.Acceptance.Reject(RejectReason.InCall) },
        )

        orch.onIncoming(peerPub, AudioOpFrame(30L, AudioOp.Request, mac, 0L))

        waitFor { sent.any { it.op is AudioOp.Reject } }
        val r = sent.first { it.op is AudioOp.Reject }.op as AudioOp.Reject
        assertEquals(RejectReason.InCall, r.reason)
        assertEquals(0L, ctrl.disconnectCount.get(), "must NOT disconnect when rejected")
    }


    private suspend fun waitFor(timeoutMs: Long = 1500, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            delay(20)
        }
        throw AssertionError("predicate not satisfied within ${timeoutMs}ms")
    }

    private suspend fun waitForFrameKind(q: ConcurrentLinkedQueue<AudioOpFrame>, kind: String) {
        waitFor { q.any { it.op::class.simpleName?.equals(kind, ignoreCase = true) == true } }
    }

    private class FakeController(
        private val connectOk: Boolean,
        private val slowDisconnect: Long = 0L,
    ) : AudioDeviceHandle {
        val connectCount = AtomicLong(0)
        val disconnectCount = AtomicLong(0)
        override fun prewarm() {  }
        override fun isConnected(mac: String): Boolean = false
        override fun invalidate(mac: String) {  }
        override fun close() {  }
        override suspend fun connect(mac: String): Result<Unit> {
            connectCount.incrementAndGet()
            return if (connectOk) Result.success(Unit)
                else Result.failure(IllegalStateException("fake connect refused"))
        }
        override suspend fun disconnect(mac: String): Result<Unit> {
            if (slowDisconnect > 0) delay(slowDisconnect)
            disconnectCount.incrementAndGet()
            return Result.success(Unit)
        }
    }

    private class FakePeerStore : PeerStore {
        private val outN = AtomicLong(0)
        private val inN = AtomicLong(0)
        override fun save(peer: TrustedPeer) {}
        override fun load(peerStaticPub: ByteArray): TrustedPeer? = null
        override fun list(): List<TrustedPeer> = emptyList()
        override fun forget(peerStaticPub: ByteArray) {}
        override fun nextAudioOutNonce(peerStaticPub: ByteArray): Long = outN.incrementAndGet()
        override fun loadAudioInNonce(peerStaticPub: ByteArray): Long = inN.get()
        override fun commitAudioInNonce(peerStaticPub: ByteArray, nonce: Long) {
            inN.updateAndGet { maxOf(it, nonce) }
        }
    }
}
