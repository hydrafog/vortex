package com.vortex.a3.core.earbuds

import android.content.Context
import android.util.Log
import com.vortex.a3.core.storage.PeerStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

object EarbudsSwitchHolder {

    private const val TAG = "EarbudsSwitchHolder"

    private val notReadyState = MutableStateFlow<SwitchState>(SwitchState.Idle)

    @Volatile private var orchestratorRef: SwitchOrchestrator? = null
    @Volatile private var controllerRef: AudioDeviceController? = null

    private val sessionWriters =
        java.util.concurrent.ConcurrentHashMap<String, suspend (AudioOpFrame) -> Result<Unit>>()

    private val bleWriters =
        java.util.concurrent.ConcurrentHashMap<String, suspend (AudioOpFrame) -> Result<Unit>>()

    private val raceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private suspend fun raceSend(
        ble: suspend (AudioOpFrame) -> Result<Unit>,
        lan: suspend (AudioOpFrame) -> Result<Unit>,
        frame: AudioOpFrame,
    ): Result<Unit> {
        val winner = CompletableDeferred<Result<Unit>>()
        val remaining = AtomicInteger(2)
        for (w in listOf(ble, lan)) {
            raceScope.launch {
                val r = runCatching { w(frame) }.getOrElse { Result.failure(it) }
                if (r.isSuccess) {
                    winner.complete(r)
                } else if (remaining.decrementAndGet() == 0) {
                    winner.complete(r)
                }
            }
        }
        return winner.await()
    }

    @Volatile
    private var acceptance: () -> SwitchOrchestrator.Acceptance =
        { SwitchOrchestrator.Acceptance.Allow }

    fun setAcceptanceProvider(provider: () -> SwitchOrchestrator.Acceptance) {
        acceptance = provider
    }

    @Synchronized
    fun init(context: Context, peerStore: PeerStore) {
        if (orchestratorRef != null) return
        val controller = AudioDeviceController(context.applicationContext)
        controller.prewarm()
        val persistence = SwitchPersistence(context.applicationContext)
        val orchestrator = SwitchOrchestrator(
            controller = controller,
            peerStore = peerStore,
            sender = { peerPub, frame ->
                val key = peerPub.joinToString("") { "%02x".format(it) }
                val bleWriter = bleWriters[key]
                val lanWriter = sessionWriters[key]
                when {
                    bleWriter != null && lanWriter != null ->
                        raceSend(bleWriter, lanWriter, frame)
                    bleWriter != null -> bleWriter(frame)
                    lanWriter != null -> lanWriter(frame)
                    else -> {
                        Log.w(TAG, "no writer (BLE or LAN) for peer=${key.take(8)}… op=${frame.op}")
                        Result.failure(IllegalStateException("no active session; need either subscribed AUDIO_SIGNAL or open LAN socket"))
                    }
                }
            },
            acceptanceProvider = { acceptance() },
            persistence = persistence,
        )
        controllerRef = controller
        orchestratorRef = orchestrator
        orchestrator.recoverOnStart()
        Log.i(TAG, "initialized")
    }

    val state: StateFlow<SwitchState>
        get() = orchestratorRef?.state ?: notReadyState.asStateFlow()

    fun request(peerPub: ByteArray, mac: String): Boolean {
        val o = orchestratorRef ?: run {
            Log.w(TAG, "request before init")
            return false
        }
        return o.request(peerPub, mac)
    }

    fun claim(peerPub: ByteArray, mac: String) {
        val o = orchestratorRef ?: run {
            Log.w(TAG, "claim before init")
            return
        }
        o.claim(peerPub, mac)
    }

    suspend fun onIncoming(peerPub: ByteArray, frame: AudioOpFrame) {
        val o = orchestratorRef ?: run {
            Log.w(TAG, "onIncoming before init")
            return
        }
        o.onIncoming(peerPub, frame)
    }

    @androidx.annotation.VisibleForTesting
    @Synchronized
    fun replaceForTest(orchestrator: SwitchOrchestrator?) {
        orchestratorRef = orchestrator
    }

    fun setSessionWriter(peerPub: ByteArray, writer: suspend (AudioOpFrame) -> Result<Unit>) {
        val key = peerPub.joinToString("") { "%02x".format(it) }
        sessionWriters[key] = writer
    }

    fun clearSessionWriter(peerPub: ByteArray, writer: suspend (AudioOpFrame) -> Result<Unit>) {
        val key = peerPub.joinToString("") { "%02x".format(it) }
        sessionWriters.remove(key, writer)
    }

    fun setBleWriter(peerPub: ByteArray, writer: suspend (AudioOpFrame) -> Result<Unit>) {
        val key = peerPub.joinToString("") { "%02x".format(it) }
        bleWriters[key] = writer
    }

    fun clearBleWriter(peerPub: ByteArray, writer: suspend (AudioOpFrame) -> Result<Unit>) {
        val key = peerPub.joinToString("") { "%02x".format(it) }
        bleWriters.remove(key, writer)
    }
}

