package com.vortex.a3.core.pairing

import android.bluetooth.BluetoothDevice
import android.util.Log
import com.southernstorm.noise.protocol.CipherState
import com.southernstorm.noise.protocol.CipherStatePair
import com.southernstorm.noise.protocol.HandshakeState
import com.southernstorm.noise.protocol.Noise
import com.vortex.a3.core.ble.Frame
import com.vortex.a3.core.ble.FrameSub
import com.vortex.a3.core.ble.FrameType
import com.vortex.a3.core.crypto.Derive
import com.vortex.a3.core.crypto.NoiseRunner
import com.vortex.a3.core.crypto.Sas
import com.vortex.a3.core.identity.IdentityRecord
import java.util.Collections
import java.util.LinkedHashMap

class PairingOrchestrator(private val identity: IdentityRecord) {

    data class HandshakeOutcome(
        val device: BluetoothDevice,
        val transcriptHash: ByteArray,
        val peerStaticPub: ByteArray,
        val sasString: String,
        val state: PhaseState,
        val peerName: String? = null,
    )

    enum class PhaseState {
        XxComplete,
        BothApproved,
        PeerRejected,
    }

    private enum class Decision { Pending, Approve, Reject }

    private sealed class XxState {
        object Idle : XxState()
        class WaitingForMsg3(val handshake: HandshakeState) : XxState()
        class AwaitingApprovals(
            val transcriptHash: ByteArray,
            val peerStaticPub: ByteArray,
            val sasString: String,
            val sender: CipherState,
            val receiver: CipherState,
            val deadlineMs: Long = System.currentTimeMillis() + APPROVAL_TIMEOUT_MS,
            var localDecision: Decision = Decision.Pending,
            var peerDecision: Decision = Decision.Pending,
            var peerName: String? = null,
        ) : XxState()
        class Completed(
            val transcriptHash: ByteArray,
            val peerStaticPub: ByteArray,
            val prs: ByteArray,
            val peerName: String? = null,
        ) : XxState()
        class Rejected(val transcriptHash: ByteArray) : XxState()
    }

    var autoApprove: Boolean = false

    private val states: MutableMap<String, XxState> = Collections.synchronizedMap(
        object : LinkedHashMap<String, XxState>(16, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, XxState>): Boolean {
                return size > MAX_CONCURRENT_PEERS
            }
        }
    )
    private val listeners: MutableList<(HandshakeOutcome) -> Unit> = mutableListOf()

    fun addListener(listener: (HandshakeOutcome) -> Unit) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun forgetDevice(device: BluetoothDevice) {
        states.remove(device.address)
    }

    fun forgetDeviceOnDisconnect(device: BluetoothDevice) {
        val s = states[device.address] ?: return
        if (s is XxState.Completed) return
        states.remove(device.address)
        Log.i(TAG, "pairing state cleared on disconnect for ${device.address}")
    }

    fun peerPrs(deviceAddress: String): ByteArray? {
        val s = states[deviceAddress]
        return if (s is XxState.Completed) s.prs.copyOf() else null
    }

    fun isAwaitingUserDecision(deviceAddress: String): Boolean =
        states[deviceAddress] is XxState.AwaitingApprovals

    fun userApprove(device: BluetoothDevice, localName: String? = null): Frame? {
        val frame = buildLocalApprovalFrame(device, approve = true, localName = localName)
            ?: run {
                Log.w(TAG, "userApprove for ${device.address} in unexpected state")
                return null
            }
        commitLocalDecision(device, approve = true)
        return frame
    }

    fun userReject(device: BluetoothDevice): Frame? {
        val frame = buildLocalApprovalFrame(device, approve = false)
            ?: run {
                Log.w(TAG, "userReject for ${device.address} in unexpected state")
                return null
            }
        commitLocalDecision(device, approve = false)
        return frame
    }

    fun buildLocalApprovalFrame(
        device: BluetoothDevice,
        approve: Boolean,
        localName: String? = null,
    ): Frame? {
        val s = states[device.address] as? XxState.AwaitingApprovals ?: return null
        val sub = if (approve) APPROVAL_APPROVE else APPROVAL_REJECT
        val plain = if (approve && !localName.isNullOrBlank()) {
            sanitizePeerName(localName).toByteArray(Charsets.UTF_8)
        } else {
            ByteArray(0)
        }
        val payload = aeadSeal(s.sender, plain)
        return Frame(FrameType.PAIRING_APPROVAL, sub, payload)
    }

    fun commitLocalDecision(device: BluetoothDevice, approve: Boolean) {
        val s = states[device.address] as? XxState.AwaitingApprovals ?: return
        synchronized(s) {
            if (states[device.address] !== s) return
            Log.i(
                TAG,
                "user ${if (approve) "approved" else "rejected"} pairing for ${device.address}",
            )
            s.localDecision = if (approve) Decision.Approve else Decision.Reject
            finalizeIfReady(device, s)
        }
    }

    fun onPairingControlFrame(device: BluetoothDevice, frame: Frame): Frame? {
        sweepExpiredApprovals()
        return when (frame.type) {
            FrameType.PAIRING_HANDSHAKE -> when (frame.sub) {
                HANDSHAKE_MSG1 -> handleMsg1(device, frame.payload)
                HANDSHAKE_MSG3 -> handleMsg3(device, frame.payload)
                else -> {
                    Log.w(TAG, "unexpected handshake sub=0x${"%02x".format(frame.sub)} for ${device.address}")
                    null
                }
            }
            FrameType.PAIRING_APPROVAL -> handlePeerApproval(device, frame.sub, frame.payload)
            else -> {
                Log.w(TAG, "ignoring frame type=0x${"%02x".format(frame.type)} on Pairing Control")
                null
            }
        }
    }

    private fun handleMsg1(device: BluetoothDevice, payload: ByteArray): Frame? {
        Log.i(TAG, "handshake msg1 from ${device.address}: ${payload.size} bytes")
        when (val cur = states[device.address]) {
            is XxState.WaitingForMsg3 -> {
                Log.w(TAG, "msg1 dropped: already WaitingForMsg3 for ${device.address}")
                return null
            }
            is XxState.AwaitingApprovals -> {
                Log.w(TAG, "msg1 dropped: already AwaitingApprovals for ${device.address}")
                return null
            }
            else -> {  Unit }
        }
        val handshake = try {
            buildResponder()
        } catch (e: Exception) {
            Log.e(TAG, "responder build failed", e)
            states[device.address] = XxState.Idle
            return null
        }

        val readBuf = ByteArray(Noise.MAX_PACKET_LEN)
        try {
            handshake.readMessage(payload, 0, payload.size, readBuf, 0)
        } catch (e: Exception) {
            Log.e(TAG, "read msg1 failed", e)
            handshake.destroy()
            return null
        }

        val writeBuf = ByteArray(Noise.MAX_PACKET_LEN)
        val n = try {
            handshake.writeMessage(writeBuf, 0, null, 0, 0)
        } catch (e: Exception) {
            Log.e(TAG, "write msg2 failed", e)
            handshake.destroy()
            return null
        }
        val msg2Bytes = writeBuf.copyOf(n)
        Log.i(TAG, "handshake msg2 to ${device.address}: $n bytes")

        states[device.address] = XxState.WaitingForMsg3(handshake)
        return Frame(FrameType.PAIRING_HANDSHAKE, HANDSHAKE_MSG2, msg2Bytes)
    }

    private fun handleMsg3(device: BluetoothDevice, payload: ByteArray): Frame? {
        Log.i(TAG, "handshake msg3 from ${device.address}: ${payload.size} bytes")
        val state = states[device.address]
        if (state !is XxState.WaitingForMsg3) {
            Log.w(TAG, "msg3 in unexpected state: $state")
            return null
        }

        val readBuf = ByteArray(Noise.MAX_PACKET_LEN)
        try {
            state.handshake.readMessage(payload, 0, payload.size, readBuf, 0)
        } catch (e: Exception) {
            Log.e(TAG, "read msg3 failed", e)
            state.handshake.destroy()
            states[device.address] = XxState.Idle
            return null
        }

        val transcript = state.handshake.handshakeHash.copyOf()
        val peerStaticPub = ByteArray(32).also { state.handshake.remotePublicKey.getPublicKey(it, 0) }
        val (_, sasString) = Sas.derive(transcript)

        val pair: CipherStatePair = try {
            state.handshake.split()
        } catch (e: Exception) {
            Log.e(TAG, "split() failed", e)
            state.handshake.destroy()
            states[device.address] = XxState.Idle
            return null
        }
        val sender: CipherState = pair.sender
        val receiver: CipherState = pair.receiver
        state.handshake.destroy()

        Log.i(TAG, "XX complete for ${device.address}")
        Log.i(TAG, "   transcript_hash = ${transcript.toHexPrefix()}")
        Log.i(TAG, "   peer_static_pub = ${peerStaticPub.toHexPrefix()}")
        Log.i(TAG, "   SAS             = [redacted; shown in UI only]")

        val approvals = XxState.AwaitingApprovals(
            transcriptHash = transcript,
            peerStaticPub = peerStaticPub,
            sasString = sasString,
            sender = sender,
            receiver = receiver,
        )
        if (autoApprove) {
            approvals.localDecision = Decision.Approve
        }
        states[device.address] = approvals
        emitOutcome(HandshakeOutcome(device, transcript, peerStaticPub, sasString, PhaseState.XxComplete))

        return if (autoApprove) {
            Log.i(TAG, "auto-approving (dev mode)")
            val ct = aeadSeal(sender, ByteArray(0))
            Frame(FrameType.PAIRING_APPROVAL, APPROVAL_APPROVE, ct)
        } else null
    }

    private fun sweepExpiredApprovals() {
        val now = System.currentTimeMillis()
        val expired = synchronized(states) {
            states.entries
                .filter { (_, s) -> s is XxState.AwaitingApprovals && now > s.deadlineMs }
                .map { it.key }
        }
        for (addr in expired) {
            val s = states[addr] as? XxState.AwaitingApprovals ?: continue
            Log.w(TAG, "approval timeout for $addr — auto-reject")
            synchronized(s) {
                if (states[addr] !== s) return@synchronized
                s.localDecision = Decision.Reject
                states[addr] = XxState.Rejected(s.transcriptHash)
            }
        }
    }

    private fun aeadSeal(cipher: CipherState, plaintext: ByteArray): ByteArray {
        val out = ByteArray(plaintext.size + cipher.macLength)
        val n = cipher.encryptWithAd(null, plaintext, 0, out, 0, plaintext.size)
        return out.copyOf(n)
    }

    private fun aeadOpen(cipher: CipherState, ciphertext: ByteArray): ByteArray {
        if (ciphertext.size < cipher.macLength) {
            throw IllegalArgumentException("ciphertext shorter than MAC")
        }
        val out = ByteArray(ciphertext.size)
        val n = cipher.decryptWithAd(null, ciphertext, 0, out, 0, ciphertext.size)
        return out.copyOf(n)
    }

    private fun handlePeerApproval(device: BluetoothDevice, sub: Byte, payload: ByteArray): Frame? {
        val s = states[device.address]
        if (s !is XxState.AwaitingApprovals) {
            Log.w(TAG, "peer approval in unexpected state: $s")
            return null
        }
        synchronized(s) {
            if (states[device.address] !== s) return null
            val pending: Decision = when (sub) {
                APPROVAL_APPROVE -> Decision.Approve
                APPROVAL_REJECT -> Decision.Reject
                else -> {
                    Log.w(TAG, "unexpected approval sub=0x${"%02x".format(sub)}")
                    return null
                }
            }
            val plain: ByteArray? = runCatching { aeadOpen(s.receiver, payload) }.getOrNull()
            if (plain == null) {
                Log.w(TAG, "peer approval AEAD-decrypt failed for ${device.address}")
                return null
            }
            s.peerDecision = pending
            when (pending) {
                Decision.Approve -> Log.i(TAG, "peer approved pairing for ${device.address}")
                Decision.Reject -> Log.w(TAG, "peer rejected pairing for ${device.address}")
                else -> {}
            }
            if (pending == Decision.Approve && plain.isNotEmpty()) {
                s.peerName = runCatching {
                    sanitizePeerName(String(plain, Charsets.UTF_8))
                }.getOrNull()?.takeIf { it.isNotEmpty() }
            }
            finalizeIfReady(device, s)
        }
        return null
    }

    private fun finalizeIfReady(device: BluetoothDevice, s: XxState.AwaitingApprovals) {
        val anyReject = s.localDecision == Decision.Reject || s.peerDecision == Decision.Reject
        val bothApprove = s.localDecision == Decision.Approve && s.peerDecision == Decision.Approve
        when {
            anyReject -> {
                states[device.address] = XxState.Rejected(s.transcriptHash)
                emitOutcome(
                    HandshakeOutcome(
                        device, s.transcriptHash, s.peerStaticPub,
                        s.sasString, PhaseState.PeerRejected,
                    )
                )
            }
            bothApprove -> {
                val prs = Derive.prs(s.transcriptHash)
                states[device.address] = XxState.Completed(
                    transcriptHash = s.transcriptHash,
                    peerStaticPub = s.peerStaticPub,
                    prs = prs,
                    peerName = s.peerName,
                )
                Log.i(TAG, "both approved; trust derived for ${device.address}")
                emitOutcome(
                    HandshakeOutcome(
                        device, s.transcriptHash, s.peerStaticPub,
                        s.sasString, PhaseState.BothApproved,
                        peerName = s.peerName,
                    )
                )
            }
        }
    }

    private fun emitOutcome(outcome: HandshakeOutcome) {
        synchronized(listeners) {
            for (listener in listeners) listener(outcome)
        }
    }

    private fun buildResponder(): HandshakeState {
        val state = HandshakeState(NoiseRunner.NOISE_XX, HandshakeState.RESPONDER)
        state.setPrologue(NoiseRunner.PROLOGUE_XX, 0, NoiseRunner.PROLOGUE_XX.size)
        state.localKeyPair.setPrivateKey(identity.staticPriv, 0)
        state.start()
        return state
    }

    companion object {
        private const val TAG = "VortexPairing"
        const val HANDSHAKE_MSG1: Byte = 0x01
        const val HANDSHAKE_MSG2: Byte = 0x02
        const val HANDSHAKE_MSG3: Byte = 0x03
        const val APPROVAL_APPROVE: Byte = 0x01
        const val APPROVAL_REJECT: Byte = 0x02

        const val PEER_NAME_MAX_CHARS: Int = 64

        const val MAX_CONCURRENT_PEERS: Int = 8

        const val APPROVAL_TIMEOUT_MS: Long = 60_000L

        private fun ByteArray.toHex(): String =
            joinToString("") { "%02x".format(it) }

        private fun ByteArray.toHexPrefix(): String =
            take(4).joinToString("") { "%02x".format(it) } + "…"

        fun sanitizePeerName(input: String): String {
            val out = StringBuilder()
            var count = 0
            val iter = input.codePointIterator()
            while (iter.hasNext() && count < PEER_NAME_MAX_CHARS) {
                val cp = iter.next()
                if (cp < 0x20 || cp == 0x7F) continue
                if (cp in 0x80..0x9F) continue
                if (cp in 0x202A..0x202E) continue
                if (cp in 0x2066..0x2069) continue
                out.appendCodePoint(cp)
                count += 1
            }
            return out.toString().trim()
        }

        private fun String.codePointIterator(): IntIterator = object : IntIterator() {
            private var idx = 0
            override fun hasNext(): Boolean = idx < this@codePointIterator.length
            override fun nextInt(): Int {
                val cp = this@codePointIterator.codePointAt(idx)
                idx += Character.charCount(cp)
                return cp
            }
        }
    }
}
