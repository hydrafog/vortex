package com.vortex.a3.core.earbuds

import android.util.Log
import com.vortex.a3.core.storage.PeerStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

class SwitchOrchestrator(
    private val controller: AudioDeviceHandle,
    private val peerStore: PeerStore,
    private val sender: suspend (peerStaticPub: ByteArray, frame: AudioOpFrame) -> Result<Unit>,
    private val acceptanceProvider: () -> Acceptance = { Acceptance.Allow },
    private val persistence: SwitchPersistence? = null,
) {

    sealed class Acceptance {
        object Allow : Acceptance()
        data class Reject(val reason: RejectReason) : Acceptance()
    }

    private val stateRef = AtomicReference<SwitchState>(SwitchState.Idle)
    private val flowState = MutableStateFlow<SwitchState>(SwitchState.Idle)
    val state: StateFlow<SwitchState> = flowState.asStateFlow()

    @Volatile private var activeInitiator: ActiveFlow? = null

    @Volatile private var activeResponder: ActiveFlow? = null

    @Volatile private var currentPeer: ByteArray? = null
    @Volatile private var currentMac: String? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)


    fun claim(peerStaticPub: ByteArray, mac: String) {
        scope.launch {
            try {
                val nonce = peerStore.nextAudioOutNonce(peerStaticPub)
                sender(
                    peerStaticPub,
                    AudioOpFrame(nonce, AudioOp.Claim, mac, nowSec()),
                )
            } catch (e: Throwable) {
                Log.w(TAG, "claim: sender failed: ${e.message}")
            }
        }
        scope.launch {
            val disc = withContext(Dispatchers.IO) { controller.disconnect(mac) }
            if (disc.isFailure) {
                Log.w(
                    TAG,
                    "claim: local disconnect failed: ${disc.exceptionOrNull()?.message}",
                )
            }
        }
    }

    fun request(peerStaticPub: ByteArray, mac: String): Boolean {
        currentPeer = peerStaticPub
        currentMac = mac
        if (!transition(SwitchState.Idle, SwitchState.Preparing)) {
            Log.w(TAG, "request ignored: already in $stateRef.get()")
            currentPeer = null
            currentMac = null
            return false
        }
        armFlowWatchdog()
        scope.launch { runInitiator(peerStaticPub, mac) }
        return true
    }

    private fun armFlowWatchdog() {
        scope.launch {
            delay(FLOW_WATCHDOG_MS)
            val s = stateRef.get()
            if (s == SwitchState.Preparing ||
                s == SwitchState.Connecting ||
                s == SwitchState.AlmostDone
            ) {
                Log.w(TAG, "flow watchdog: in-flight too long ($s); forcing Idle")
                stateRef.set(SwitchState.Idle)
                flowState.value = SwitchState.Idle
                activeInitiator = null
                currentPeer = null
                currentMac = null
            }
        }
    }

    suspend fun onIncoming(peerStaticPub: ByteArray, frame: AudioOpFrame) {
        if (!peerStore.tryAcceptAudioInNonce(peerStaticPub, frame.nonce)) {
            Log.w(TAG, "audio_op nonce ${frame.nonce} replayed; dropping")
            return
        }

        when (frame.op) {
            AudioOp.Request -> startResponderFlow(peerStaticPub, frame.mac)
            AudioOp.Claim -> onClaim(peerStaticPub, frame.mac)
            AudioOp.Approve -> initiatorOnApprove(peerStaticPub, frame.mac)
            AudioOp.Released -> initiatorOnReleased(peerStaticPub, frame.mac)
            is AudioOp.Reject -> initiatorOnReject(peerStaticPub, frame.op.reason)
            AudioOp.Done -> responderOnDone(peerStaticPub)
            is AudioOp.Failed -> peerReportedFailure(peerStaticPub, frame.op)
        }
    }

    private fun onClaim(peerPub: ByteArray, mac: String) {
        if (stateRef.get() != SwitchState.Idle) {
            Log.i(TAG, "onClaim: already in flow; dropping")
            return
        }
        Log.i(TAG, "onClaim: peer asked us to claim mac=$mac")
        request(peerPub, mac)
    }

    fun close() {
        scope.coroutineContext[Job]?.cancel()
    }


    private suspend fun runInitiator(peerPub: ByteArray, mac: String) {
        val nonce = peerStore.nextAudioOutNonce(peerPub)
        activeInitiator = ActiveFlow(peerPub, mac, nonce)
        Log.i(TAG, "initiator: request peer=${peerPub.toHexPrefix()} mac=$mac nonce=$nonce")

        scope.launch {
            if (controller.isConnected(mac)) {
                Log.i(TAG, "initiator: local already has buds; releasing first")
                controller.disconnect(mac)
            }
            controller.prewarm()
        }

        scope.launch {
            val sendResult = sender(peerPub, AudioOpFrame(nonce, AudioOp.Request, mac, nowSec()))
            if (sendResult.isFailure) {
                Log.w(TAG, "send Request: ${sendResult.exceptionOrNull()?.message}")
            }
        }

        if (!transition(SwitchState.Preparing, SwitchState.Connecting)) {
            activeInitiator = null
            return
        }
        attemptConnect(peerPub, mac)
    }

    private fun initiatorOnApprove(peerPub: ByteArray, mac: String) {
        val flow = activeInitiator ?: return
        if (!flow.matches(peerPub, mac)) return
        Log.i(TAG, "informational: peer Approve received")
    }

    private fun initiatorOnReleased(peerPub: ByteArray, mac: String) {
        val flow = activeInitiator ?: return
        if (!flow.matches(peerPub, mac)) return
        Log.i(TAG, "peer Released received → buds free, connecting now + optimistic UI → AlmostDone")
        flow.releasedSignal.complete(Unit)
        transition(SwitchState.Connecting, SwitchState.AlmostDone)
    }

    private fun initiatorOnReject(peerPub: ByteArray, reason: RejectReason) {
        val flow = activeInitiator ?: return
        if (!peerPub.contentEquals(flow.peerPub)) return
        failInitiator(peerPub, "peer rejected: $reason")
    }

    private suspend fun attemptConnect(peerPub: ByteArray, mac: String) {
        if (!controller.isBluetoothEnabled()) {
            Log.w(TAG, "attemptConnect: phone Bluetooth is OFF — cannot grab buds; aborting")
            failInitiator(peerPub, "phone Bluetooth off")
            return
        }

        val flow = activeInitiator
        if (flow != null && flow.matches(peerPub, mac)) {
            val released = withTimeoutOrNull(RELEASED_WAIT_CEILING_MS) {
                flow.releasedSignal.await()
            } != null
            Log.i(
                TAG,
                if (released) "attemptConnect: Released observed → connecting now"
                else "attemptConnect: Released ceiling (${RELEASED_WAIT_CEILING_MS}ms) hit → connecting anyway",
            )
        } else {
            delay(PRE_CONNECT_HEAD_START_MS)
        }
        var lastErr = "connect not attempted"
        for (attempt in 1..CONNECT_RETRY_COUNT) {
            val s = stateRef.get()
            if (s is SwitchState.Failed || s == SwitchState.Idle) {
                Log.i(TAG, "attemptConnect: state already terminal; stopping retries")
                return
            }
            val result = withContext(Dispatchers.IO) { controller.connect(mac) }
            if (result.isSuccess) {
                Log.i(TAG, "initiator: switch complete attempt=$attempt peer=${peerPub.toHexPrefix()}")
                val nonce = peerStore.nextAudioOutNonce(peerPub)
                sender(peerPub, AudioOpFrame(nonce, AudioOp.Done, mac, nowSec()))
                val cur = stateRef.get()
                if (cur == SwitchState.Connecting || cur == SwitchState.AlmostDone) {
                    transition(cur, SwitchState.Idle)
                }
                activeInitiator = null
                return
            }
            lastErr = "attempt#$attempt: ${result.exceptionOrNull()?.message ?: "connect failed"}"
            Log.i(TAG, "connect retry: $lastErr")
            if (attempt < CONNECT_RETRY_COUNT) {
                delay(CONNECT_RETRY_PAUSE_MS)
            }
        }
        val nonce = peerStore.nextAudioOutNonce(peerPub)
        sender(peerPub, AudioOpFrame(nonce, AudioOp.Failed(Stage.Connect, lastErr), mac, nowSec()))
        failInitiator(peerPub, lastErr)
    }

    private fun failInitiator(peerPub: ByteArray, reason: String) {
        Log.w(TAG, "initiator: failed peer=${peerPub.toHexPrefix()} reason=$reason")
        val failed = SwitchState.Failed(reason)
        stateRef.set(failed)
        flowState.value = failed
        persistence?.save(failed, currentPeer, currentMac)
        activeInitiator = null
        scope.launch {
            delay(FAILED_RESET_MS)
            if (stateRef.compareAndSet(failed, SwitchState.Idle)) {
                flowState.value = SwitchState.Idle
                currentPeer = null
                currentMac = null
                persistence?.clear()
            }
        }
    }


    private fun startResponderFlow(peerPub: ByteArray, mac: String) {
        when (val accept = acceptanceProvider()) {
            is Acceptance.Reject -> {
                scope.launch {
                    val nonce = peerStore.nextAudioOutNonce(peerPub)
                    sender(
                        peerPub,
                        AudioOpFrame(nonce, AudioOp.Reject(accept.reason), mac, nowSec()),
                    )
                }
                return
            }
            Acceptance.Allow -> {  }
        }
        if (stateRef.get() != SwitchState.Idle) {
            scope.launch {
                val nonce = peerStore.nextAudioOutNonce(peerPub)
                sender(
                    peerPub,
                    AudioOpFrame(nonce, AudioOp.Reject(RejectReason.Busy), mac, nowSec()),
                )
            }
            return
        }
        activeResponder = ActiveFlow(peerPub, mac, 0)
        scope.launch { runResponder(peerPub, mac) }
    }

    private suspend fun runResponder(peerPub: ByteArray, mac: String) {
        val approveNonce = peerStore.nextAudioOutNonce(peerPub)
        sender(peerPub, AudioOpFrame(approveNonce, AudioOp.Approve, mac, nowSec()))

        val disc = withContext(Dispatchers.IO) { controller.disconnect(mac) }
        if (disc.isFailure) {
            val msg = disc.exceptionOrNull()?.message ?: "local disconnect failed"
            val nonce = peerStore.nextAudioOutNonce(peerPub)
            sender(
                peerPub,
                AudioOpFrame(nonce, AudioOp.Failed(Stage.Disconnect, msg), mac, nowSec()),
            )
            activeResponder = null
            return
        }
        val releasedNonce = peerStore.nextAudioOutNonce(peerPub)
        sender(peerPub, AudioOpFrame(releasedNonce, AudioOp.Released, mac, nowSec()))

        delay(DONE_WAIT_MS)
        activeResponder = null
    }

    private fun responderOnDone(peerPub: ByteArray) {
        val flow = activeResponder ?: return
        if (!peerPub.contentEquals(flow.peerPub)) return
        activeResponder = null
    }

    private fun peerReportedFailure(peerPub: ByteArray, failed: AudioOp.Failed) {
        Log.w(TAG, "peer reported failure: ${failed.stage} ${failed.message}")
        if (activeInitiator?.peerPub?.contentEquals(peerPub) == true) {
            failInitiator(peerPub, "peer: ${failed.message}")
        }
        activeResponder = null
    }


    private fun transition(from: SwitchState, to: SwitchState): Boolean {
        val ok = stateRef.compareAndSet(from, to)
        if (ok) {
            flowState.value = to
            if (to == SwitchState.Idle) {
                currentPeer = null
                currentMac = null
                persistence?.clear()
            } else {
                persistence?.save(to, currentPeer, currentMac)
            }
        }
        return ok
    }

    fun recoverOnStart() {
        val p = persistence ?: return
        when (val act = p.recover()) {
            SwitchPersistence.Action.None -> {  }
            is SwitchPersistence.Action.Rollback -> {
                Log.w(TAG, "recover: rollback — ${act.previousReason}")
                stateRef.set(SwitchState.Failed(act.previousReason))
                flowState.value = SwitchState.Failed(act.previousReason)
                p.clear()
                scope.launch {
                    delay(FAILED_RESET_MS)
                    stateRef.compareAndSet(SwitchState.Failed(act.previousReason), SwitchState.Idle)
                    if (stateRef.get() == SwitchState.Idle) flowState.value = SwitchState.Idle
                }
            }
            is SwitchPersistence.Action.ResumeConnect -> {
                Log.i(TAG, "recover: resume Connecting mac=${act.mac}")
                currentPeer = act.peerPub
                currentMac = act.mac
                stateRef.set(SwitchState.Connecting)
                flowState.value = SwitchState.Connecting
                activeInitiator = ActiveFlow(act.peerPub, act.mac, 0)
                scope.launch { attemptConnect(act.peerPub, act.mac) }
            }
        }
    }

    private fun nowSec(): Long = System.currentTimeMillis() / 1000L

    private fun ByteArray.toHexPrefix(): String =
        take(4).joinToString("") { "%02x".format(it) } + "…"

    private data class ActiveFlow(val peerPub: ByteArray, val mac: String, val nonce: Long) {
        val releasedSignal = CompletableDeferred<Unit>()

        fun matches(p: ByteArray, m: String) = peerPub.contentEquals(p) && mac == m
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ActiveFlow) return false
            return peerPub.contentEquals(other.peerPub) && mac == other.mac && nonce == other.nonce
        }
        override fun hashCode(): Int {
            var r = peerPub.contentHashCode()
            r = 31 * r + mac.hashCode()
            r = 31 * r + nonce.hashCode()
            return r
        }
    }

    companion object {
        private const val TAG = "VortexSwitch"

        const val FLOW_WATCHDOG_MS: Long = 10_000

        const val CONNECT_RETRY_COUNT: Int = 3

        const val CONNECT_RETRY_PAUSE_MS: Long = 280

        const val PRE_CONNECT_HEAD_START_MS: Long = 600

        const val RELEASED_WAIT_CEILING_MS: Long = 250

        const val DONE_WAIT_MS: Long = 4_000

        const val FAILED_RESET_MS: Long = 3_000
    }
}

sealed class SwitchState {
    object Idle : SwitchState()
    object Preparing : SwitchState()
    object WaitingApproval : SwitchState()
    object WaitingReleased : SwitchState()
    object Connecting : SwitchState()
    object AlmostDone : SwitchState()
    data class Failed(val reason: String) : SwitchState()
}
