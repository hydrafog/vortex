package com.vortex.a3.ui
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.outlined.Settings
import com.vortex.a3.BuildConfig
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import android.provider.Settings
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.vortex.a3.core.ble.Advertiser
import com.vortex.a3.core.identity.IdentityRecord
import com.vortex.a3.core.lan.LanServer
import com.vortex.a3.core.lan.LanServerMode
import com.vortex.a3.core.pairing.PairingOrchestrator
import com.vortex.a3.core.pairing.ReconnectOrchestrator
import com.vortex.a3.core.storage.TrustedPeer
import com.vortex.a3.service.VortexService
import com.vortex.a3.core.appstate.AppState


internal fun MainActivity.wirePairingOrchestrator(identity: IdentityRecord) {
    val orchestrator = PairingOrchestrator(identity)
    orchestrator.autoApprove =
        BuildConfig.DEBUG && intent.getBooleanExtra("auto_approve", false)
    orchestrator.addListener { outcome ->
        handshakeState.value = outcome
        when (outcome.state) {
            PairingOrchestrator.PhaseState.XxComplete -> {
                pendingApproval.value = outcome
                val timedOutcome = outcome
                lifecycleScope.launch {
                    kotlinx.coroutines.delay(120_000)
                    if (pendingApproval.value === timedOutcome) {
                        android.util.Log.w(
                            "PairingTimeout",
                            "SAS approval window expired; auto-rejecting",
                        )
                        onRejectClicked(timedOutcome)
                    }
                }
            }
            PairingOrchestrator.PhaseState.BothApproved -> {
                pendingApproval.value = null
                val prs = orchestrator.peerPrs(outcome.device.address)
                if (prs != null) {
                    peerStore.save(
                        TrustedPeer(
                            peerStaticPub = outcome.peerStaticPub,
                            prs = prs,
                            pairedAt = System.currentTimeMillis() / 1000,
                            peerName = outcome.peerName,
                        )
                    )
                    refreshPeerList()
                    state.value = AdvertiseState.TrustedPresence
                    lifecycleScope.launch {
                        kotlinx.coroutines.delay(600)
                        advertiser.stopAll()
                        gattServer.stop()
                        lanServer?.stop()
                        VortexService.start(applicationContext)
                    }
                }
            }
            PairingOrchestrator.PhaseState.PeerRejected -> {
                pendingApproval.value = null
            }
        }
    }
    pairingOrchestrator = orchestrator
    gattServer.pairingOrchestrator = orchestrator
}

internal fun MainActivity.wireReconnectOrchestrator(identity: IdentityRecord) {
    val reconnect = ReconnectOrchestrator(identity, peerStore)
    reconnect.addListener { outcome -> reconnectState.value = outcome }
    gattServer.reconnectOrchestrator = reconnect
}

internal fun MainActivity.startPairingWindowLanIfUntrusted(identity: IdentityRecord) {
    if (peerStore.list().isNotEmpty()) return
    val instanceId = ByteArray(8).also {
        java.security.SecureRandom().nextBytes(it)
    }
    pairingInstanceId = instanceId
    lanServer = LanServer(applicationContext, identity, peerStore).also {
        it.start(LanServerMode.PairingWindow(instanceId))
    }
}

internal fun MainActivity.onApproveClicked(outcome: PairingOrchestrator.HandshakeOutcome) {
    val orch = pairingOrchestrator ?: return
    val frame = orch.buildLocalApprovalFrame(
        outcome.device,
        approve = true,
        localName = friendlyLocalName(),
    ) ?: run {
        pendingApproval.value = null
        return
    }
    gattServer.sendPairingControl(outcome.device, frame)
    orch.commitLocalDecision(outcome.device, approve = true)
    pendingApproval.value = null
}

internal fun MainActivity.onRejectClicked(outcome: PairingOrchestrator.HandshakeOutcome) {
    val orch = pairingOrchestrator ?: return
    val frame = orch.buildLocalApprovalFrame(outcome.device, approve = false) ?: run {
        pendingApproval.value = null
        return
    }
    gattServer.sendPairingControl(outcome.device, frame)
    orch.commitLocalDecision(outcome.device, approve = false)
    pendingApproval.value = null
}

internal fun MainActivity.onForgetPeerClicked(peer: TrustedPeer) {
    val hex = peer.peerStaticPub.joinToString("") { "%02x".format(it) }
    VortexService.pendingRevokes.add(hex)
    VortexService.requestLanNudge()
    peerListState.value = peerListState.value.filter {
        !it.peerStaticPub.contentEquals(peer.peerStaticPub)
    }
    peerCountState.value = peerListState.value.size
    lifecycleScope.launch {
        kotlinx.coroutines.delay(1_500)
        VortexService.pendingRevokes.remove(hex)
        peerStore.forget(peer.peerStaticPub)
        refreshPeerList()
        if (peerStore.list().isEmpty()) {
            VortexService.stop(applicationContext)
            state.value = AdvertiseState.Idle
        }
    }
}

internal fun MainActivity.onForgetAllClicked() {
    VortexService.stop(applicationContext)
    for (peer in peerStore.list()) {
        peerStore.forget(peer.peerStaticPub)
    }
    refreshPeerList()
    reconnectState.value = null
    state.value = AdvertiseState.Idle
}

internal fun MainActivity.refreshPeerList() {
    peerListState.value = peerStore.list()
    peerCountState.value = peerListState.value.size
}

internal fun MainActivity.friendlyLocalName(): String {
    return try {
        android.provider.Settings.Global.getString(contentResolver, "device_name")
            ?.takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }
        ?: "${Build.MANUFACTURER} ${Build.MODEL}".trim()
}

internal fun MainActivity.startAdvertising() {
    state.value = AdvertiseState.Starting
    if (!gattServer.start()) {
        state.value = AdvertiseState.Error("failed to start GATT server")
        return
    }
    val firstPeer = peerStore.list().firstOrNull()
    if (firstPeer != null) {
        advertiser.startTrustedPresence(
            prs = firstPeer.prs,
            scope = lifecycleScope,
            rotationWindowSec = 60L,
            onError = { reason ->
                state.value = AdvertiseState.Error(reason)
            },
        )
        state.value = AdvertiseState.TrustedPresence
    } else {
        val id = pairingInstanceId
        val cb: (Advertiser.StartResult) -> Unit = { result ->
            state.value = when (result) {
                is Advertiser.StartResult.Started ->
                    AdvertiseState.Active(result.payload)
                is Advertiser.StartResult.Failed -> {
                    gattServer.stop()
                    AdvertiseState.Error(result.reason)
                }
            }
        }
        if (id != null) {
            advertiser.startPairableAdvertiseWith(id, cb)
        } else {
            advertiser.startPairableAdvertise(cb)
        }
    }
}

internal fun MainActivity.maybeRequestEssentialPermissions() {
    val needed = essentialPermissions().filter {
        ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
    }
    if (needed.isNotEmpty()) {
        essentialPermissionLauncher.launch(needed.toTypedArray())
    }
}

internal fun MainActivity.onStartClicked() {
    val needed = requiredPermissions().filter {
        ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
    }
    if (needed.isEmpty()) {
        startAdvertising()
    } else {
        permissionLauncher.launch(needed.toTypedArray())
    }
}

internal fun MainActivity.onStopClicked() {
    advertiser.stopAll()
    gattServer.stop()
    state.value = AdvertiseState.Idle
}
