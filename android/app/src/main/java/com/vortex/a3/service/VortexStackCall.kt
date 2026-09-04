package com.vortex.a3.service

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.launch


@Volatile private var callGrabbedBuds = false

internal fun VortexStack.enrichCallEvent(ev: com.vortex.a3.core.call.CallEvent): com.vortex.a3.core.call.CallEvent {
    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
    val muted = am?.isMicrophoneMute ?: false
    @Suppress("DEPRECATION")
    val speaker = am?.isSpeakerphoneOn ?: false
    val hasEarbuds = am?.let { hasHeadsetOutput(it) } ?: false
    return ev.copy(
        sentAt = System.currentTimeMillis(),
        muted = muted,
        speaker = speaker,
        hasEarbuds = hasEarbuds,
    )
}

internal fun VortexStack.hasHeadsetOutput(am: android.media.AudioManager): Boolean = try {
    am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any {
        it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
    }
} catch (_: Exception) {
    false
}

internal fun VortexStack.republishCurrentCall() {
    VortexService.currentCall?.let {
        VortexService.callEventBus.tryEmit(it)
        lanServer?.nudge()
    }
}

internal fun VortexStack.handleCallControl(ctrl: com.vortex.a3.core.call.CallControl) {
    if (ctrl.seq > 0L) {
        synchronized(this) {
            if (ctrl.seq <= lastHandledCallControlSeq) return
            lastHandledCallControlSeq = ctrl.seq
        }
    }
    if (ctrl.action == com.vortex.a3.core.call.CallControl.Action.LOAD_THREAD) {
        handleLoadThread(ctrl.arg)
        return
    }
    callController.handle(ctrl)
    when (ctrl.action) {
        com.vortex.a3.core.call.CallControl.Action.MUTE,
        com.vortex.a3.core.call.CallControl.Action.UNMUTE,
        com.vortex.a3.core.call.CallControl.Action.SPEAKER_ON,
        com.vortex.a3.core.call.CallControl.Action.SPEAKER_OFF,
        -> republishCurrentCall()
    }
}

internal fun VortexStack.startCallFlow(): com.vortex.a3.core.call.CallFlowOrchestrator {
    val callFlow = com.vortex.a3.core.call.CallFlowOrchestrator(
        context = ctx,
        onCallStart = {
            if (!isBluetoothOn()) {
                Log.i(VortexStack.TAG, "call started but phone Bluetooth is OFF — skipping hand-off")
                callGrabbedBuds = false
            } else if (phoneOwnsBuds()) {
                Log.i(VortexStack.TAG, "call started but buds already on phone — no hand-off")
                callGrabbedBuds = false
            } else {
                Log.i(VortexStack.TAG, "call started; asking peer to release buds")
                VortexService.pendingCallPhase = "ringing"
                lanServer?.nudge()
                callGrabbedBuds = grabBudsToPhone()
            }
            callGrabbedBuds
        },
        onCallEnd = {
            VortexService.pendingCallPhase = null
            if (callGrabbedBuds) {
                Log.i(VortexStack.TAG, "call ended; handing buds back to laptop")
                callGrabbedBuds = false
                handBudsToLaptop()
            } else {
                Log.i(VortexStack.TAG, "call ended; buds were already on phone — leaving them")
            }
        },
        onCallEvent = { ev ->
            if (ev.phase == com.vortex.a3.core.call.CallEvent.PHASE_ENDED) {
                VortexService.currentCall = ev
                scope.launch {
                    kotlinx.coroutines.delay(6000)
                    if (VortexService.currentCall?.phase ==
                        com.vortex.a3.core.call.CallEvent.PHASE_ENDED
                    ) {
                        VortexService.currentCall = null
                    }
                }
            } else {
                VortexService.currentCall = ev
            }
            VortexService.callEventBus.tryEmit(ev)
            lanServer?.nudge()
        },
    )
    if (!callFlow.start()) {
        Log.w(VortexStack.TAG, "call-flow orchestrator did not start (READ_PHONE_STATE missing?)")
    }
    callFlowOrchestrator = callFlow
    com.vortex.a3.core.earbuds.EarbudsSwitchHolder.setAcceptanceProvider {
        if (VortexService.callGateActive()) {
            com.vortex.a3.core.earbuds.SwitchOrchestrator.Acceptance.Reject(
                com.vortex.a3.core.earbuds.RejectReason.InCall,
            )
        } else {
            com.vortex.a3.core.earbuds.SwitchOrchestrator.Acceptance.Allow
        }
    }
    return callFlow
}

internal fun VortexStack.registerFakeCallReceiver(callFlow: com.vortex.a3.core.call.CallFlowOrchestrator) {
    if (!com.vortex.a3.BuildConfig.DEBUG) return
    val receiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, intent: android.content.Intent?) {
            val raw = intent?.getStringExtra("state")?.lowercase() ?: return
            val mapped = when (raw) {
                "ringing" -> android.telephony.TelephonyManager.CALL_STATE_RINGING
                "offhook", "active" -> android.telephony.TelephonyManager.CALL_STATE_OFFHOOK
                "idle", "end" -> android.telephony.TelephonyManager.CALL_STATE_IDLE
                else -> {
                    Log.w(VortexStack.TAG, "FAKE_CALL: unknown state \"$raw\"")
                    return
                }
            }
            val number = intent.getStringExtra("number")
            Log.i(VortexStack.TAG, "FAKE_CALL broadcast → $raw")
            callFlow.simulateCallStateForDebug(mapped, number)
        }
    }
    val filter = android.content.IntentFilter("com.vortex.a3.FAKE_CALL")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        service.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
        @Suppress("UnspecifiedRegisterReceiverFlag")
        service.registerReceiver(receiver, filter)
    }
    fakeCallReceiver = receiver
}

internal fun VortexStack.forwardCallEvents() {
    scope.launch {
        VortexService.callEventBus.collect { ev ->
            if (!com.vortex.a3.core.notif.NotificationMirrorSetting.isEnabled()) return@collect
            val server = gattServer ?: return@collect
            val json = enrichCallEvent(ev).toJsonBytes()
            for (peer in peerStore.list()) {
                server.sendCallEncrypted(peer.peerStaticPub, json)
            }
            val pkg = ev.appId
            if (pkg.isNotEmpty() && sentIconPkgs.add(pkg)) {
                scope.launch { sendAppIcon(pkg) }
            }
        }
    }
}
