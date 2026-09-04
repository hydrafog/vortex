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
import com.vortex.a3.core.crypto.NoiseRunner
import com.vortex.a3.core.identity.IdentityRecord
import com.vortex.a3.core.status.DeviceStatusReader
import com.vortex.a3.core.status.LocalStatus
import com.vortex.a3.core.status.PeerStatus
import com.vortex.a3.core.storage.PeerStore
import java.util.concurrent.ConcurrentHashMap

class ReconnectOrchestrator(
    private val identity: IdentityRecord,
    private val peerStore: PeerStore,
) {

    data class ReconnectOutcome(
        val device: BluetoothDevice,
        val peerStaticPub: ByteArray,
        val transcriptHash: ByteArray,
        val ciphers: CipherStatePair,
    )

    data class PeerStatusEvent(
        val peerStaticPub: ByteArray,
        val status: PeerStatus,
    )

    var localStatusProvider: () -> LocalStatus = {
        LocalStatus(
            battery = com.vortex.a3.core.status.BatteryPct(null),
            deviceClass = com.vortex.a3.core.status.DeviceClass.PHONE,
        )
    }

    private val statusListeners: MutableList<(PeerStatusEvent) -> Unit> =
        java.util.concurrent.CopyOnWriteArrayList()
    fun addStatusListener(listener: (PeerStatusEvent) -> Unit) {
        statusListeners.add(listener)
    }
    private fun emitStatus(event: PeerStatusEvent) {
        for (l in statusListeners) l(event)
    }

    private sealed class IkState {
        object Idle : IkState()
        class Established(
            val peerStaticPub: ByteArray,
            val transcriptHash: ByteArray,
        ) : IkState()
    }

    private val states: MutableMap<String, IkState> = ConcurrentHashMap()
    private val listeners: MutableList<(ReconnectOutcome) -> Unit> =
        java.util.concurrent.CopyOnWriteArrayList()

    fun addListener(listener: (ReconnectOutcome) -> Unit) {
        listeners.add(listener)
    }

    fun forgetDevice(device: BluetoothDevice) {
        states.remove(device.address)
    }

    fun onReconnectFrame(device: BluetoothDevice, frame: Frame): Frame? {
        return when (frame.type) {
            FrameType.RECONNECT_HANDSHAKE -> {
                if (frame.sub == HANDSHAKE_MSG1) handleIkMsg1(device, frame.payload) else null
            }
            FrameType.TRANSPORT_KEEPALIVE -> {
                if (frame.sub == FrameSub.PING) handlePing(device, frame.payload) else null
            }
            else -> {
                Log.w(TAG, "unexpected frame type=0x${"%02x".format(frame.type)} on Reconnect Control")
                null
            }
        }
    }

    private fun handleIkMsg1(device: BluetoothDevice, payload: ByteArray): Frame? {
        Log.i(TAG, "IK msg1 from ${device.address}: ${payload.size} bytes")

        val trustedList = try { peerStore.list() } catch (_: Exception) { emptyList() }
        if (trustedList.isEmpty()) {
            Log.w(TAG, "no trusted peers — rejecting IK")
            return null
        }

        var matched: Triple<HandshakeState, ByteArray, Long>? = null
        for (peer in trustedList) {
            val candidate = try {
                buildResponder(peer.prs)
            } catch (e: Exception) {
                Log.e(TAG, "IK responder build failed for peer ${peer.peerStaticPub.toHex()}", e)
                continue
            }
            val readBuf = ByteArray(Noise.MAX_PACKET_LEN)
            val ptLen = try {
                candidate.readMessage(payload, 0, payload.size, readBuf, 0)
            } catch (_: Exception) {
                candidate.destroy()
                -1
            }
            if (ptLen >= 0) {
                val pub = ByteArray(32).also { candidate.remotePublicKey.getPublicKey(it, 0) }
                if (!pub.contentEquals(peer.peerStaticPub)) {
                    Log.w(TAG, "IK static/PRS mismatch (race?); rejecting")
                    candidate.destroy()
                    continue
                }
                val peerCounter: Long = if (ptLen >= 8) {
                    java.nio.ByteBuffer.wrap(readBuf, 0, 8)
                        .order(java.nio.ByteOrder.BIG_ENDIAN)
                        .long
                } else 0L
                matched = Triple(candidate, pub, peerCounter)
                break
            }
        }

        if (matched == null) {
            Log.w(TAG, "no trusted PRS accepted msg1; rejecting reconnect")
            return null
        }
        val (handshake, peerStaticPub, peerCounter) = matched
        val localCounter = peerStore.loadCounter(peerStaticPub)
        if (peerCounter < localCounter) {
            Log.w(
                TAG,
                "possible trust rollback for ${peerStaticPub.toHexPrefix()}: " +
                    "peer=$peerCounter local=$localCounter",
            )
        }
        val nextCounter = peerStore.bumpCounter(peerStaticPub, peerCounter)

        val counterPayload = java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.BIG_ENDIAN)
            .putLong(nextCounter)
            .array()
        val writeBuf = ByteArray(Noise.MAX_PACKET_LEN)
        val n = try {
            handshake.writeMessage(writeBuf, 0, counterPayload, 0, counterPayload.size)
        } catch (e: Exception) {
            Log.e(TAG, "IK msg2 write failed", e)
            handshake.destroy()
            return null
        }

        val transcript = handshake.handshakeHash.copyOf()
        val ciphers: CipherStatePair = try {
            handshake.split()
        } catch (e: Exception) {
            Log.e(TAG, "split() failed", e)
            handshake.destroy()
            return null
        }
        handshake.destroy()
        Log.i(TAG, "✅ IK complete for ${device.address}")
        Log.i(TAG, "   peer_static_pub = ${peerStaticPub.toHexPrefix()}")
        Log.i(TAG, "   transcript_hash = ${transcript.toHexPrefix()}")

        states[device.address] = IkState.Established(peerStaticPub, transcript)
        for (l in listeners) l(ReconnectOutcome(device, peerStaticPub, transcript, ciphers))
        return Frame(FrameType.RECONNECT_HANDSHAKE, HANDSHAKE_MSG2, writeBuf.copyOf(n))
    }

    private fun handlePing(device: BluetoothDevice, nonce: ByteArray): Frame? {
        val state = states[device.address]
        if (state !is IkState.Established) {
            Log.w(TAG, "ping in unexpected state: $state")
            return null
        }
        Log.i(TAG, "ping from ${device.address} (${nonce.size} bytes); responding")
        return Frame(FrameType.TRANSPORT_KEEPALIVE, FrameSub.PONG, nonce.copyOf())
    }

    private fun buildResponder(prs: ByteArray): HandshakeState {
        require(prs.size == 32) { "PRS must be 32 bytes" }
        val prologue = ByteArray(NoiseRunner.PROLOGUE_IK.size + 32)
        System.arraycopy(NoiseRunner.PROLOGUE_IK, 0, prologue, 0, NoiseRunner.PROLOGUE_IK.size)
        System.arraycopy(prs, 0, prologue, NoiseRunner.PROLOGUE_IK.size, 32)
        val state = HandshakeState(NoiseRunner.NOISE_IK, HandshakeState.RESPONDER)
        state.setPrologue(prologue, 0, prologue.size)
        state.localKeyPair.setPrivateKey(identity.staticPriv, 0)
        state.start()
        return state
    }

    companion object {
        private const val TAG = "VortexReconnect"
        const val HANDSHAKE_MSG1: Byte = 0x01
        const val HANDSHAKE_MSG2: Byte = 0x02

        private fun ByteArray.toHex(): String =
            joinToString("") { "%02x".format(it) }

        private fun ByteArray.toHexPrefix(): String =
            take(4).joinToString("") { "%02x".format(it) } + "…"
    }
}
