package com.vortex.a3.service

import android.util.Log
import kotlinx.coroutines.launch


internal fun VortexStack.buildLocalAppState(): com.vortex.a3.core.appstate.AppState {
    val battery = com.vortex.a3.core.status.DeviceStatusReader.readBattery(ctx).value
    val charging = com.vortex.a3.core.status.DeviceStatusReader.readCharging(ctx)
    val earbuds = try {
        com.vortex.a3.core.earbuds.EarbudsDetector.readConnectedEarbuds(ctx)
    } catch (t: Throwable) {
        Log.w(VortexStack.TAG, "earbuds detection threw: ${t.message}")
        null
    }
    val revokeNow = VortexService.pendingRevokes.toSet().isNotEmpty()
    val claimNow = VortexService.pendingAudioClaim.getAndSet(false)
    val lockPending = VortexService.pendingLock.get()?.takeIf {
        android.os.SystemClock.elapsedRealtime() < it.expiresAtMs
    }
    val mediaPending = VortexService.pendingMediaControl.get()?.takeIf {
        android.os.SystemClock.elapsedRealtime() < it.expiresAtMs
    }
    val phaseNow = VortexService.pendingCallPhase
    val km = ctx.getSystemService(android.app.KeyguardManager::class.java)
    val phoneUnlocked: Boolean? = km?.let { !it.isKeyguardLocked }
    return com.vortex.a3.core.appstate.AppState(
        battery = battery,
        deviceClass = com.vortex.a3.core.appstate.DeviceClass.PHONE,
        name = friendlyDeviceName(),
        earbuds = earbuds,
        revoked = revokeNow,
        audioClaimRequest = claimNow,
        callPhase = phaseNow,
        call = VortexService.currentCall?.let { enrichCallEvent(it) },
        handoff = VortexService.currentHandoff,
        mediaPlaying = localMediaPlaying,
        mediaPlayAgeMs = mediaHandoff?.let { mh ->
            val e = mh.localPlayEpochMono
            if (e > 0L) android.os.SystemClock.elapsedRealtime() - e else 0L
        } ?: 0L,
        smartSwitchEnabled = com.vortex.a3.core.media.SmartSwitchSetting.isEnabled(),
        smartSwitchChangedAt = com.vortex.a3.core.media.SmartSwitchSetting.changedAt(),
        charging = charging,
        unlocked = phoneUnlocked,
        lockCommand = lockPending?.op,
        lockCommandSeq = lockPending?.seq ?: 0L,
        mediaControl = mediaPending?.op,
        mediaControlSeq = mediaPending?.seq ?: 0L,
        laptopMirrorReq = com.vortex.a3.core.mirror.LaptopMirror.requestActive,
        laptopMirrorExtend = com.vortex.a3.core.mirror.LaptopMirror.extendWanted,
        cameraOffer = VortexService.cameraOffer,
        wifiIp = currentWifiIp(),
        displayHz = currentDisplayHz(ctx),
    )
}

private fun currentDisplayHz(ctx: android.content.Context): Int? = try {
    val dm = ctx.getSystemService(android.content.Context.DISPLAY_SERVICE)
        as? android.hardware.display.DisplayManager
    dm?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        ?.refreshRate
        ?.takeIf { it > 1f }
        ?.let { Math.round(it) }
} catch (_: Throwable) {
    null
}

private fun currentWifiIp(): String? = try {
    java.net.NetworkInterface.getNetworkInterfaces()?.asSequence()
        ?.filter { it.isUp && !it.isLoopback }
        ?.filter { ni ->
            val n = ni.name
            n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan")
        }
        ?.flatMap { it.inetAddresses.asSequence() }
        ?.filterIsInstance<java.net.Inet4Address>()
        ?.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
        ?.hostAddress
} catch (_: Exception) {
    null
}

internal fun VortexStack.handlePeerAppState(peerPub: ByteArray, state: com.vortex.a3.core.appstate.AppState) {
    VortexService.peerStateBus.tryEmit(peerPub.toHex() to state)
    latestPeerState = state
    latestPeerStateAtMs = android.os.SystemClock.elapsedRealtime()
    onStateChanged()
    state.earbuds?.let { buds ->
        if (buds.connected && buds.address.isNotBlank() &&
            com.vortex.a3.core.earbuds.EarbudsStore.load(ctx) == null
        ) {
            com.vortex.a3.core.earbuds.EarbudsStore.save(
                ctx,
                com.vortex.a3.core.earbuds.SavedEarbuds(address = buds.address, name = buds.name),
            )
            Log.i(VortexStack.TAG, "auto-saved peer earbuds (card pinned locally)")
        }
    }
    state.callControl?.let { handleCallControl(it) }
    state.notifInvoke?.let { handleNotifInvoke(it) }
    val cast = state.laptopCast
    val castError = state.laptopCastError
    if (cast != null) {
        val key = hexToBytes(cast.key)
        if (key != null && key.size == 32) {
            com.vortex.a3.core.mirror.LaptopMirror.onLaptopOffer(ctx, cast.port, key)
        }
    } else if (castError != null) {
        com.vortex.a3.core.mirror.LaptopMirror.onLaptopCastFailed(castError)
    } else {
        com.vortex.a3.core.mirror.LaptopMirror.onLaptopCastEnded()
        com.vortex.a3.core.mirror.LaptopMirror.onLaptopCastSilent()
    }
    handleCameraRequest(state.cameraReq, state.cameraFacing)
    com.vortex.a3.core.ring.RingController.onRingSeq(ctx, state.ringSeq)
    if (com.vortex.a3.core.media.SmartSwitchSetting
            .applyFromPeer(state.smartSwitchEnabled, state.smartSwitchChangedAt)
    ) {
        Log.i(VortexStack.TAG, "smart-switch: adopted peer setting (LWW) = ${state.smartSwitchEnabled}")
    }
    if (state.revoked) {
        Log.i(VortexStack.TAG, "peer revoked us; forgetting ${peerPub.toHexPrefix()}")
        peerStore.forget(peerPub)
        VortexService.revokedByPeerBus.tryEmit(peerPub.toHex())
    }
    if (state.audioClaimRequest) {
        val saved = com.vortex.a3.core.earbuds.EarbudsStore.load(ctx)
        if (saved != null) {
            if (audioCtl?.isConnected(saved.address) == true) {
                Log.i(VortexStack.TAG, "audio_claim_request still set but buds already here; ignoring")
            } else {
                Log.i(VortexStack.TAG, "peer set audio_claim_request; running initiator")
                com.vortex.a3.core.earbuds.EarbudsSwitchHolder.request(peerPub, saved.address)
            }
        }
    }
    val peerNow = state.mediaPlaying
    lastPeerMediaPlaying = peerNow
    mediaHandoff?.peerPlaying = peerNow
    val peerEpochMono = if (state.mediaPlayAgeMs > 0L && peerNow) {
        android.os.SystemClock.elapsedRealtime() - state.mediaPlayAgeMs
    } else 0L
    mediaHandoff?.peerPlayEpochMono = peerEpochMono
    val ourEpoch = mediaHandoff?.localPlayEpochMono ?: 0L
    val peerPlayedLast = ourEpoch == 0L || (peerEpochMono != 0L && peerEpochMono > ourEpoch)
    val mac = com.vortex.a3.core.earbuds.EarbudsStore.load(ctx)?.address
    val inCall = VortexService.callGateActive()
    val enabled = mediaHandoff?.smartSwitchEnabled != false
    val ctl = audioCtl
    if (peerNow && peerPlayedLast && enabled && !inCall &&
        mac != null && ctl != null && ctl.isConnected(mac)
    ) {
        Log.i(VortexStack.TAG, "laptop is the media device; releasing buds so it can grab")
        scope.launch { ctl.disconnect(mac) }
    }
    com.vortex.a3.core.media.LaptopMediaNotification.update(
        ctx,
        title = state.mediaTitle.orEmpty(),
        artist = state.mediaArtist.orEmpty(),
        app = state.mediaApp.orEmpty(),
        artUrl = state.mediaArtUrl.orEmpty(),
        playing = state.mediaNpPlaying,
    )
}

private fun hexToBytes(s: String): ByteArray? {
    if (s.length % 2 != 0) return null
    return try {
        ByteArray(s.length / 2) { i ->
            s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    } catch (_: NumberFormatException) {
        null
    }
}

internal fun VortexStack.friendlyDeviceName(): String {
    return try {
        android.provider.Settings.Global.getString(service.contentResolver, "device_name")
            ?.takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }
        ?: "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()
}
