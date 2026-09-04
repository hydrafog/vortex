package com.vortex.a3.core.call

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CallFlowOrchestrator(
    private val context: Context,
    private val onCallStart: () -> Boolean,
    private val onCallEnd: () -> Unit,
    private val onCallEvent: (CallEvent) -> Unit = {},
) {

    @Volatile private var current: CallEvent? = null

    enum class CallPhase {
        Idle,
        Ringing,
        Active,
        EndingHandoff,
    }

    private val tag = "CallFlow"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val phaseFlow = MutableStateFlow(CallPhase.Idle)

    val phase: StateFlow<CallPhase> = phaseFlow.asStateFlow()

    @Volatile private var speakerphoneFallbackJob: Job? = null

    private var registeredCallback: TelephonyCallback? = null
    private var registeredListener: PhoneStateListener? = null

    private val callGrabFallbackMs: Long = 2_000

    fun start(): Boolean {
        if (registeredCallback != null || registeredListener != null) {
            return true
        }
        if (!hasReadPhoneState()) {
            Log.w(tag, "READ_PHONE_STATE missing; call handoff disabled")
            return false
        }
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (tm == null) {
            Log.w(tag, "TelephonyManager unavailable (no telephony hardware?)")
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    handleCallState(state)
                }
            }
            try {
                tm.registerTelephonyCallback(context.mainExecutor, cb)
                registeredCallback = cb
                Log.i(tag, "telephony callback registered (API 31+)")
            } catch (e: SecurityException) {
                Log.w(tag, "registerTelephonyCallback rejected: ${e.message}")
                return false
            }
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                @Deprecated("Pre-S")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    handleCallState(state, phoneNumber)
                }
            }
            try {
                @Suppress("DEPRECATION")
                tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                registeredListener = listener
                Log.i(tag, "phone-state listener registered (pre-API-31)")
            } catch (e: SecurityException) {
                Log.w(tag, "listen rejected: ${e.message}")
                return false
            }
        }
        return true
    }

    fun stop() {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (tm != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                registeredCallback?.let {
                    try { tm.unregisterTelephonyCallback(it) } catch (_: Exception) {  }
                }
            } else {
                registeredListener?.let {
                    @Suppress("DEPRECATION")
                    try { tm.listen(it, PhoneStateListener.LISTEN_NONE) } catch (_: Exception) {  }
                }
            }
        }
        registeredCallback = null
        registeredListener = null
        speakerphoneFallbackJob?.cancel()
        scope.coroutineContext[Job]?.cancel()
    }

    fun notifyBudsConnected() {
        speakerphoneFallbackJob?.cancel()
        speakerphoneFallbackJob = null
        val phase = phaseFlow.value
        if (phase == CallPhase.Ringing || phase == CallPhase.Active) {
            disableSpeakerphone()
        }
    }

    fun simulateCallStateForDebug(state: Int, incomingNumber: String? = null) {
        Log.w(tag, "simulateCallStateForDebug($state) — debug only")
        handleCallState(state, incomingNumber)
    }

    private fun handleCallState(rawState: Int, incomingNumber: String? = null) {
        val nextPhase = when (rawState) {
            TelephonyManager.CALL_STATE_RINGING -> CallPhase.Ringing
            TelephonyManager.CALL_STATE_OFFHOOK -> CallPhase.Active
            TelephonyManager.CALL_STATE_IDLE -> CallPhase.Idle
            else -> return
        }
        val prev = phaseFlow.value
        if (prev == nextPhase) return
        Log.i(tag, "call $prev → $nextPhase")
        phaseFlow.value = nextPhase

        when {
            prev == CallPhase.Idle && (nextPhase == CallPhase.Ringing || nextPhase == CallPhase.Active) -> {
                emitCallEnter(nextPhase, incomingNumber)
                scope.launch {
                    val handoffStarted = try {
                        onCallStart()
                    } catch (e: Exception) {
                        Log.w(tag, "onCallStart threw: ${e.message}")
                        false
                    }
                    if (handoffStarted) armSpeakerphoneFallback()
                }
            }
            prev == CallPhase.Ringing && nextPhase == CallPhase.Active -> {
                emitCallActive()
            }
            (prev == CallPhase.Ringing || prev == CallPhase.Active) && nextPhase == CallPhase.Idle -> {
                phaseFlow.value = CallPhase.EndingHandoff
                emitCallEnded()
                speakerphoneFallbackJob?.cancel()
                speakerphoneFallbackJob = null
                disableSpeakerphone()
                scope.launch {
                    try { onCallEnd() } catch (e: Exception) { Log.w(tag, "onCallEnd threw: ${e.message}") }
                    phaseFlow.value = CallPhase.Idle
                }
            }
        }
    }

    private fun emitCallEnter(phase: CallPhase, incomingNumber: String?) {
        val outgoing = phase == CallPhase.Active
        val id = "call-${System.currentTimeMillis()}"
        val number = incomingNumber?.trim().orEmpty()
        val dialerPkg = defaultDialerPackage()
        val base = CallEvent(
            id = id,
            phase = if (outgoing) CallEvent.PHASE_ACTIVE else CallEvent.PHASE_RINGING,
            name = "",
            number = number,
            startedAt = if (outgoing) System.currentTimeMillis() else 0L,
            outgoing = outgoing,
            appId = dialerPkg,
        )
        current = base
        scope.launch {
            val name = resolveContactName(number)
            val cur = current ?: return@launch
            if (cur.id != id) return@launch
            val ev = if (name.isEmpty()) cur else cur.copy(name = name)
            current = ev
            safeEmit(ev)
        }
    }

    private fun emitCallActive() {
        val base = current ?: return
        val ev = base.copy(phase = CallEvent.PHASE_ACTIVE, startedAt = System.currentTimeMillis())
        current = ev
        safeEmit(ev)
    }

    private fun emitCallEnded() {
        val base = current ?: return
        current = null
        safeEmit(base.copy(phase = CallEvent.PHASE_ENDED))
    }

    private fun safeEmit(ev: CallEvent) {
        try {
            onCallEvent(ev)
        } catch (e: Exception) {
            Log.w(tag, "onCallEvent threw: ${e.message}")
        }
    }

    private fun resolveContactName(number: String): String {
        if (number.isEmpty()) return ""
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ""
        }
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number),
            )
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.trim().orEmpty() else ""
            }.orEmpty()
        } catch (e: Exception) {
            Log.w(tag, "resolveContactName failed: ${e.message}")
            ""
        }
    }

    private fun armSpeakerphoneFallback() {
        speakerphoneFallbackJob?.cancel()
        speakerphoneFallbackJob = scope.launch {
            delay(callGrabFallbackMs)
            val phase = phaseFlow.value
            if (phase == CallPhase.Ringing || phase == CallPhase.Active) {
                Log.w(tag, "buds not connected within ${callGrabFallbackMs}ms; enabling speakerphone")
                enableSpeakerphone()
                val now = phaseFlow.value
                if (now != CallPhase.Ringing && now != CallPhase.Active) {
                    Log.w(tag, "call ended during speaker fallback; undoing")
                    disableSpeakerphone()
                }
            }
        }
    }

    private fun enableSpeakerphone() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isSpeakerphoneOn = true
        } catch (e: Exception) {
            Log.w(tag, "enableSpeakerphone failed: ${e.message}")
        }
    }

    private fun disableSpeakerphone() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            am.isSpeakerphoneOn = false
            am.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) {
            Log.w(tag, "disableSpeakerphone failed: ${e.message}")
        }
    }

    private fun defaultDialerPackage(): String = try {
        val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        tm?.defaultDialerPackage.orEmpty()
    } catch (_: Exception) {
        ""
    }

    private fun hasReadPhoneState(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_PHONE_STATE,
        ) == PackageManager.PERMISSION_GRANTED
}
