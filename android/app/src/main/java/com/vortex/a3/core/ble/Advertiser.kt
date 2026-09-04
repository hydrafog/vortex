package com.vortex.a3.core.ble

import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.vortex.a3.core.crypto.Presence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom

class Advertiser(private val context: Context) {

    private val adapter by lazy {
        val bm = context.getSystemService(BluetoothManager::class.java)
        bm.adapter
    }

    private val advertiser by lazy { adapter?.bluetoothLeAdvertiser }

    @Volatile
    private var activeCallback: AdvertiseCallback? = null

    @Volatile
    var fastModeProvider: (() -> Boolean)? = null

    private val rotationKick = Channel<Unit>(Channel.CONFLATED)

    fun kickRotation() {
        rotationKick.trySend(Unit)
    }

    @Volatile
    private var activePayload: AdvPayload? = null

    @Volatile
    private var presenceJob: Job? = null

    sealed class StartResult {
        data class Started(val payload: AdvPayload) : StartResult()
        data class Failed(val reason: String) : StartResult()
    }

    fun startWith(payload: AdvPayload, onResult: (StartResult) -> Unit) {
        if (activeCallback != null) {
            onResult(StartResult.Failed("already advertising"))
            return
        }
        val advertiser = advertiser
        if (advertiser == null) {
            onResult(StartResult.Failed("bluetooth not available"))
            return
        }

        val payloadBytes = payload.encode()

        val advertiseData = AdvertiseData.Builder()
            .addServiceData(ParcelUuid(Ble.VORTEX_SERVICE_UUID), payloadBytes)
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()

        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        val advertiseMode = if (payload.flags.isPairable ||
            fastModeProvider?.invoke() == true
        ) {
            AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
        } else {
            AdvertiseSettings.ADVERTISE_MODE_BALANCED
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(advertiseMode)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                val mode = if (payload.flags.isPairable) "pairable" else "trusted-presence"
                Log.i(TAG, "advertise started: $mode, instance=${payloadBytes.copyOfRange(2, 10).toHexString()}")
                activePayload = payload
                onResult(StartResult.Started(payload))
            }

            override fun onStartFailure(errorCode: Int) {
                val msg = errorCodeMessage(errorCode)
                Log.e(TAG, "advertise failed: $msg")
                activeCallback = null
                onResult(StartResult.Failed(msg))
            }
        }

        activeCallback = callback
        try {
            advertiser.startAdvertising(settings, advertiseData, scanResponse, callback)
        } catch (e: SecurityException) {
            activeCallback = null
            onResult(StartResult.Failed("missing BLUETOOTH_ADVERTISE permission: ${e.message}"))
        }
    }

    fun startPairableAdvertise(onResult: (StartResult) -> Unit) {
        val instanceId = ByteArray(8).also { SecureRandom().nextBytes(it) }
        startWith(AdvPayload.pairable(instanceId), onResult)
    }

    fun startPairableAdvertiseWith(instanceId: ByteArray, onResult: (StartResult) -> Unit) {
        require(instanceId.size == 8) { "instanceId must be 8 bytes" }
        startWith(AdvPayload.pairable(instanceId), onResult)
    }

    fun startTrustedPresence(
        prs: ByteArray,
        scope: CoroutineScope,
        rotationWindowSec: Long = 60L,
        onError: (String) -> Unit = {},
    ) {
        require(prs.size == 32) { "PRS must be 32 bytes" }
        presenceJob?.cancel()
        stop()
        val prsCopy = prs.copyOf()
        presenceJob = scope.launch {
            var consecFails = 0
            while (isActive) {
                val nowSec = System.currentTimeMillis() / 1000
                val bucket = Presence.currentBucket(nowSec, rotationWindowSec)
                val token = Presence.deriveToken(prsCopy, bucket)
                stop()
                startWith(AdvPayload.trustedPresence(token)) { result ->
                    when (result) {
                        is StartResult.Started -> consecFails = 0
                        is StartResult.Failed -> {
                            consecFails++
                            Log.w(TAG, "trusted-presence advertise failed (${consecFails}x): ${result.reason}")
                            if (consecFails == PRESENCE_FAIL_ALERT_AT) onError(result.reason)
                        }
                    }
                }
                val secondsIntoBucket = nowSec % rotationWindowSec
                val sleepSec = rotationWindowSec - secondsIntoBucket + 5L
                withTimeoutOrNull(sleepSec * 1000) { rotationKick.receive() }
            }
        }
    }

    fun stop() {
        val cb = activeCallback ?: return
        try {
            advertiser?.stopAdvertising(cb)
        } catch (e: SecurityException) {
            Log.w(TAG, "stopAdvertising threw: ${e.message}")
        }
        activeCallback = null
        activePayload = null
        Log.i(TAG, "advertise stopped")
    }

    fun stopAll() {
        presenceJob?.cancel()
        presenceJob = null
        stop()
    }

    fun isAdvertising(): Boolean = activeCallback != null

    fun activePayload(): AdvPayload? = activePayload

    private fun errorCodeMessage(code: Int): String = when (code) {
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "ADVERTISE_FAILED_DATA_TOO_LARGE"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "ADVERTISE_FAILED_TOO_MANY_ADVERTISERS"
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "ADVERTISE_FAILED_ALREADY_STARTED"
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "ADVERTISE_FAILED_INTERNAL_ERROR"
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "ADVERTISE_FAILED_FEATURE_UNSUPPORTED"
        else -> "advertise error $code"
    }

    companion object {
        private const val TAG = "VortexAdv"

        private const val PRESENCE_FAIL_ALERT_AT = 3
    }
}

private fun ByteArray.toHexString(): String =
    joinToString("") { "%02x".format(it) }
