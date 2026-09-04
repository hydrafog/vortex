package com.vortex.a3.hardening

import android.bluetooth.BluetoothDevice
import com.southernstorm.noise.protocol.CipherState
import com.southernstorm.noise.protocol.CipherStatePair
import com.southernstorm.noise.protocol.HandshakeState
import com.southernstorm.noise.protocol.Noise
import com.vortex.a3.core.ble.Frame
import com.vortex.a3.core.ble.FrameType
import com.vortex.a3.core.crypto.NoiseRunner
import com.vortex.a3.core.crypto.X25519
import com.vortex.a3.core.identity.IdentityRecord
import com.vortex.a3.core.identity.Platform
import com.vortex.a3.core.pairing.PairingOrchestrator
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

class RejectPathTest {

    private val testAddress = "AA:BB:CC:DD:EE:01"

    @Test
    fun `peer reject does not derive PRS or persist trust`() {
        val orch = newOrchestratorAutoApproveOff()
        val device = newFakeDevice(testAddress)
        val outcomes = CopyOnWriteArrayList<PairingOrchestrator.HandshakeOutcome>()
        orch.addListener { outcomes.add(it) }

        val initiator = newInitiator()

        val msg1Bytes = ByteArray(Noise.MAX_PACKET_LEN)
        val n1 = initiator.writeMessage(msg1Bytes, 0, null, 0, 0)
        val msg2Frame = orch.onPairingControlFrame(
            device,
            Frame(FrameType.PAIRING_HANDSHAKE, PairingOrchestrator.HANDSHAKE_MSG1, msg1Bytes.copyOf(n1)),
        )
        assertNotNull(msg2Frame, "responder must emit msg2 in reply to msg1")
        assertEquals(FrameType.PAIRING_HANDSHAKE, msg2Frame!!.type)
        assertEquals(PairingOrchestrator.HANDSHAKE_MSG2, msg2Frame.sub)

        val readBuf = ByteArray(Noise.MAX_PACKET_LEN)
        initiator.readMessage(msg2Frame.payload, 0, msg2Frame.payload.size, readBuf, 0)

        val msg3Bytes = ByteArray(Noise.MAX_PACKET_LEN)
        val n3 = initiator.writeMessage(msg3Bytes, 0, null, 0, 0)
        val maybeApproval = orch.onPairingControlFrame(
            device,
            Frame(FrameType.PAIRING_HANDSHAKE, PairingOrchestrator.HANDSHAKE_MSG3, msg3Bytes.copyOf(n3)),
        )
        assertNull(maybeApproval, "auto-approve disabled — no approval frame should be emitted")

        val xxOutcome = outcomes.firstOrNull { it.state == PairingOrchestrator.PhaseState.XxComplete }
        assertNotNull(xxOutcome, "expected XxComplete outcome after msg3")

        val pair = initiator.split()

        orch.onPairingControlFrame(
            device,
            Frame(FrameType.PAIRING_APPROVAL, PairingOrchestrator.APPROVAL_REJECT, sealEmpty(pair.sender)),
        )

        val rejected = outcomes.firstOrNull { it.state == PairingOrchestrator.PhaseState.PeerRejected }
        assertNotNull(rejected, "expected PeerRejected outcome after reject frame")
        val approved = outcomes.firstOrNull { it.state == PairingOrchestrator.PhaseState.BothApproved }
        assertNull(approved, "BothApproved must NOT be emitted after a reject frame")

        assertNull(orch.peerPrs(testAddress), "peerPrs must be null after reject")

        orch.onPairingControlFrame(
            device,
            Frame(FrameType.PAIRING_APPROVAL, PairingOrchestrator.APPROVAL_APPROVE, sealEmpty(pair.sender)),
        )
        val approvedAfterStray = outcomes.firstOrNull {
            it.state == PairingOrchestrator.PhaseState.BothApproved
        }
        assertNull(
            approvedAfterStray,
            "stray approve frame after reject must not yield BothApproved",
        )
        assertNull(orch.peerPrs(testAddress), "peerPrs must remain null after stray approve")
    }

    @Test
    fun `dual approve derives PRS only after BOTH sides approve`() {
        val orch = newOrchestratorAutoApproveOff()
        val device = newFakeDevice(testAddress)
        val outcomes = CopyOnWriteArrayList<PairingOrchestrator.HandshakeOutcome>()
        orch.addListener { outcomes.add(it) }
        val initiator = newInitiator()

        val msg1Bytes = ByteArray(Noise.MAX_PACKET_LEN)
        val n1 = initiator.writeMessage(msg1Bytes, 0, null, 0, 0)
        val msg2Frame = orch.onPairingControlFrame(
            device,
            Frame(FrameType.PAIRING_HANDSHAKE, PairingOrchestrator.HANDSHAKE_MSG1, msg1Bytes.copyOf(n1)),
        )!!
        val readBuf = ByteArray(Noise.MAX_PACKET_LEN)
        initiator.readMessage(msg2Frame.payload, 0, msg2Frame.payload.size, readBuf, 0)
        val msg3Bytes = ByteArray(Noise.MAX_PACKET_LEN)
        val n3 = initiator.writeMessage(msg3Bytes, 0, null, 0, 0)
        orch.onPairingControlFrame(
            device,
            Frame(FrameType.PAIRING_HANDSHAKE, PairingOrchestrator.HANDSHAKE_MSG3, msg3Bytes.copyOf(n3)),
        )

        val pair = initiator.split()

        orch.onPairingControlFrame(
            device,
            Frame(FrameType.PAIRING_APPROVAL, PairingOrchestrator.APPROVAL_APPROVE, sealEmpty(pair.sender)),
        )

        assertNull(
            outcomes.firstOrNull { it.state == PairingOrchestrator.PhaseState.BothApproved },
            "peer approval alone must not yield BothApproved",
        )
        assertNull(orch.peerPrs(testAddress), "peerPrs must be null until local user approves")

        val approveFrame = orch.userApprove(device)
        assertNotNull(approveFrame, "userApprove must return APPROVE frame in AwaitingApprovals state")
        assertEquals(FrameType.PAIRING_APPROVAL, approveFrame!!.type)
        assertEquals(PairingOrchestrator.APPROVAL_APPROVE, approveFrame.sub)

        val approved = outcomes.firstOrNull {
            it.state == PairingOrchestrator.PhaseState.BothApproved
        }
        assertNotNull(approved, "BothApproved must fire after local user approves")
        val prs = orch.peerPrs(testAddress)
        assertNotNull(prs, "PRS must be derived after both-approve")
        assertTrue(prs!!.size == 32, "PRS must be 32 bytes; was ${prs.size}")
    }

    @Test
    fun `forged APPROVE with bad AEAD must not advance peerDecision`() {
        val orch = newOrchestratorAutoApproveOff()
        val device = newFakeDevice(testAddress)
        val outcomes = CopyOnWriteArrayList<PairingOrchestrator.HandshakeOutcome>()
        orch.addListener { outcomes.add(it) }
        val initiator = newInitiator()

        val msg1Bytes = ByteArray(Noise.MAX_PACKET_LEN)
        val n1 = initiator.writeMessage(msg1Bytes, 0, null, 0, 0)
        val msg2Frame = orch.onPairingControlFrame(
            device,
            Frame(FrameType.PAIRING_HANDSHAKE, PairingOrchestrator.HANDSHAKE_MSG1, msg1Bytes.copyOf(n1)),
        )!!
        val readBuf = ByteArray(Noise.MAX_PACKET_LEN)
        initiator.readMessage(msg2Frame.payload, 0, msg2Frame.payload.size, readBuf, 0)
        val msg3Bytes = ByteArray(Noise.MAX_PACKET_LEN)
        val n3 = initiator.writeMessage(msg3Bytes, 0, null, 0, 0)
        orch.onPairingControlFrame(
            device,
            Frame(FrameType.PAIRING_HANDSHAKE, PairingOrchestrator.HANDSHAKE_MSG3, msg3Bytes.copyOf(n3)),
        )
        initiator.split()

        val garbage = ByteArray(48) { (it * 7 + 13).toByte() }
        orch.onPairingControlFrame(
            device,
            Frame(FrameType.PAIRING_APPROVAL, PairingOrchestrator.APPROVAL_APPROVE, garbage),
        )

        assertNull(
            outcomes.firstOrNull { it.state == PairingOrchestrator.PhaseState.BothApproved },
            "forged APPROVE must not yield BothApproved on its own",
        )

        orch.userApprove(device)
        assertNull(
            outcomes.firstOrNull { it.state == PairingOrchestrator.PhaseState.BothApproved },
            "local approve must NOT yield BothApproved when peer's only approval was forged",
        )
        assertNull(
            orch.peerPrs(testAddress),
            "peerPrs must be null when peer never AEAD-authenticated their approval",
        )
    }

    @Test
    fun `local user reject yields PeerRejected even if peer approved first`() {
        val orch = newOrchestratorAutoApproveOff()
        val device = newFakeDevice(testAddress)
        val outcomes = CopyOnWriteArrayList<PairingOrchestrator.HandshakeOutcome>()
        orch.addListener { outcomes.add(it) }
        val initiator = newInitiator()

        val msg1Bytes = ByteArray(Noise.MAX_PACKET_LEN)
        val n1 = initiator.writeMessage(msg1Bytes, 0, null, 0, 0)
        val msg2Frame = orch.onPairingControlFrame(
            device,
            Frame(FrameType.PAIRING_HANDSHAKE, PairingOrchestrator.HANDSHAKE_MSG1, msg1Bytes.copyOf(n1)),
        )!!
        val readBuf = ByteArray(Noise.MAX_PACKET_LEN)
        initiator.readMessage(msg2Frame.payload, 0, msg2Frame.payload.size, readBuf, 0)
        val msg3Bytes = ByteArray(Noise.MAX_PACKET_LEN)
        val n3 = initiator.writeMessage(msg3Bytes, 0, null, 0, 0)
        orch.onPairingControlFrame(
            device,
            Frame(FrameType.PAIRING_HANDSHAKE, PairingOrchestrator.HANDSHAKE_MSG3, msg3Bytes.copyOf(n3)),
        )

        val pair = initiator.split()

        orch.onPairingControlFrame(
            device,
            Frame(FrameType.PAIRING_APPROVAL, PairingOrchestrator.APPROVAL_APPROVE, sealEmpty(pair.sender)),
        )
        val rejectFrame = orch.userReject(device)
        assertNotNull(rejectFrame, "userReject must return REJECT frame")
        assertEquals(PairingOrchestrator.APPROVAL_REJECT, rejectFrame!!.sub)

        assertNull(orch.peerPrs(testAddress))
        assertNotNull(
            outcomes.firstOrNull { it.state == PairingOrchestrator.PhaseState.PeerRejected },
            "local reject must yield PeerRejected",
        )
        assertNull(
            outcomes.firstOrNull { it.state == PairingOrchestrator.PhaseState.BothApproved },
            "BothApproved must NOT fire after local reject",
        )
    }


    private fun newOrchestratorAutoApproveOff(): PairingOrchestrator {
        val identity = synthesizeIdentity()
        return PairingOrchestrator(identity).also { it.autoApprove = false }
    }

    private fun synthesizeIdentity(): IdentityRecord {
        val priv = ByteArray(X25519.PRIV_LEN) { (it + 0x10).toByte() }
        val deviceId = ByteArray(16) { (it + 0xA0).toByte() }
        return IdentityRecord.fromPrivate(
            platform = Platform.Android,
            deviceId = deviceId,
            staticPriv = priv,
            createdAt = 1_700_000_000L,
        )
    }

    private fun newFakeDevice(address: String): BluetoothDevice {
        val device = mockk<BluetoothDevice>()
        every { device.address } returns address
        return device
    }

    private fun newInitiator(): HandshakeState {
        val priv = ByteArray(X25519.PRIV_LEN) { (it + 0x40).toByte() }
        val state = HandshakeState(NoiseRunner.NOISE_XX, HandshakeState.INITIATOR)
        state.setPrologue(NoiseRunner.PROLOGUE_XX, 0, NoiseRunner.PROLOGUE_XX.size)
        state.localKeyPair.setPrivateKey(priv, 0)
        state.start()
        return state
    }

    private fun sealEmpty(cipher: CipherState): ByteArray {
        val out = ByteArray(cipher.macLength)
        val n = cipher.encryptWithAd(null, ByteArray(0), 0, out, 0, 0)
        return out.copyOf(n)
    }
}
