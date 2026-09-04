package com.vortex.a3.core.earbuds

import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

interface AudioDeviceHandle {
    fun prewarm()
    suspend fun connect(mac: String): Result<Unit>
    suspend fun disconnect(mac: String): Result<Unit>
    fun isConnected(mac: String): Boolean
    fun invalidate(mac: String)
    fun close()
    fun isBluetoothEnabled(): Boolean = true
}

class AudioDeviceController(private val appContext: Context) : AudioDeviceHandle {

    @Volatile private var a2dp: BluetoothA2dp? = null
    @Volatile private var headset: BluetoothHeadset? = null
    private val adapter: BluetoothAdapter? =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter

    private data class CachedState(val connected: Boolean, val capturedAtMs: Long)
    private val cache = ConcurrentHashMap<String, CachedState>()

    private val warmedAtMs = AtomicLong(0L)

    private val methodCache = ConcurrentHashMap<String, Method>()

    init {
        prewarm()
    }

    override fun prewarm() {
        if (!hasConnectPermission()) {
            Log.w(TAG, "prewarm skipped: missing BLUETOOTH_CONNECT")
            return
        }
        val ad = adapter ?: run {
            Log.w(TAG, "prewarm skipped: no Bluetooth adapter")
            return
        }
        if (a2dp == null) openProfile(ad, BluetoothProfile.A2DP) { a2dp = it as BluetoothA2dp }
        if (headset == null) openProfile(ad, BluetoothProfile.HEADSET) { headset = it as BluetoothHeadset }
    }

    override suspend fun connect(mac: String): Result<Unit> = withContext(Dispatchers.IO) {
        val device = remoteDevice(mac) ?: return@withContext fail("no remote device for $mac")
        if (isConnectedNow(device)) {
            invalidate(mac)
            return@withContext Result.success(Unit)
        }
        if (callProfileMethod(a2dp, "connect", device)) {
            if (awaitConnected(device, CONNECT_SETTLE_MS)) {
                invalidate(mac)
                return@withContext Result.success(Unit)
            }
        }
        if (callProfileMethod(headset, "connect", device)) {
            if (awaitConnected(device, CONNECT_SETTLE_MS)) {
                invalidate(mac)
                return@withContext Result.success(Unit)
            }
        }
        Result.failure(IllegalStateException("connect failed: did not reach CONNECTED within ${CONNECT_SETTLE_MS}ms"))
    }

    override suspend fun disconnect(mac: String): Result<Unit> = withContext(Dispatchers.IO) {
        val device = remoteDevice(mac) ?: return@withContext fail("no remote device for $mac")
        var anyOk = false
        if (callProfileMethod(a2dp, "disconnect", device)) anyOk = true
        if (callProfileMethod(headset, "disconnect", device)) anyOk = true
        if (!anyOk && !isConnectedNow(device)) {
            invalidate(mac)
            return@withContext Result.success(Unit)
        }
        val ok = awaitDisconnected(device, DISCONNECT_SETTLE_MS)
        invalidate(mac)
        if (ok) Result.success(Unit)
        else Result.failure(IllegalStateException("disconnect did not settle within ${DISCONNECT_SETTLE_MS}ms"))
    }

    override fun isConnected(mac: String): Boolean {
        val now = System.currentTimeMillis()
        val hit = cache[mac]
        if (hit != null && now - hit.capturedAtMs < CACHE_TTL_MS) {
            return hit.connected
        }
        val device = remoteDevice(mac) ?: return false
        val fresh = isConnectedNow(device)
        cache[mac] = CachedState(fresh, now)
        return fresh
    }

    override fun invalidate(mac: String) {
        cache.remove(mac)
    }

    override fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    override fun close() {
        val ad = adapter ?: return
        try { a2dp?.let { ad.closeProfileProxy(BluetoothProfile.A2DP, it) } } catch (_: Throwable) {}
        try { headset?.let { ad.closeProfileProxy(BluetoothProfile.HEADSET, it) } } catch (_: Throwable) {}
        a2dp = null
        headset = null
        cache.clear()
    }


    private fun hasConnectPermission(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun remoteDevice(mac: String): BluetoothDevice? {
        val ad = adapter ?: return null
        return try { ad.getRemoteDevice(mac) } catch (e: IllegalArgumentException) {
            Log.w(TAG, "bad MAC '$mac': ${e.message}"); null
        }
    }

    private fun isConnectedNow(device: BluetoothDevice): Boolean {
        val a = a2dp
        val h = headset
        if (a != null && safeState(a, "getConnectionState", device) == BluetoothProfile.STATE_CONNECTED) {
            return true
        }
        if (h != null && safeState(h, "getConnectionState", device) == BluetoothProfile.STATE_CONNECTED) {
            return true
        }
        return false
    }

    private fun resolveMethod(proxy: Any, name: String): Method? {
        val key = proxy.javaClass.name + "#" + name
        methodCache[key]?.let { return it }
        return try {
            val m = proxy.javaClass.getMethod(name, BluetoothDevice::class.java)
            m.isAccessible = true
            methodCache[key] = m
            m
        } catch (e: Throwable) {
            Log.w(TAG, "$name via reflection failed to resolve: ${e.message}")
            null
        }
    }

    private fun safeState(proxy: Any, method: String, device: BluetoothDevice): Int? {
        val m = resolveMethod(proxy, method) ?: return null
        return try {
            m.invoke(proxy, device) as? Int
        } catch (e: Throwable) {
            Log.w(TAG, "$method via reflection failed: ${e.message}")
            null
        }
    }

    private fun callProfileMethod(proxy: Any?, name: String, device: BluetoothDevice): Boolean {
        val p = proxy ?: return false
        val m = resolveMethod(p, name) ?: return false
        return try {
            (m.invoke(p, device) as? Boolean) ?: false
        } catch (e: Throwable) {
            Log.w(TAG, "$name via reflection failed: ${e.message}")
            false
        }
    }

    private suspend fun awaitConnected(device: BluetoothDevice, maxMs: Long): Boolean =
        awaitState(device, BluetoothProfile.STATE_CONNECTED, maxMs)

    private suspend fun awaitDisconnected(device: BluetoothDevice, maxMs: Long): Boolean =
        awaitState(device, BluetoothProfile.STATE_DISCONNECTED, maxMs)

    private suspend fun awaitState(device: BluetoothDevice, target: Int, maxMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < deadline) {
            val a = a2dp
            val h = headset
            val s1 = a?.let { safeState(it, "getConnectionState", device) }
            val s2 = h?.let { safeState(it, "getConnectionState", device) }
            if (target == BluetoothProfile.STATE_CONNECTED) {
                if (s1 == target || s2 == target) return true
            } else {
                val aDown = a == null || s1 == target
                val hDown = h == null || s2 == target
                if (aDown && hDown) return true
            }
            delay(75)
        }
        return false
    }

    private fun openProfile(ad: BluetoothAdapter, kind: Int, onReady: (BluetoothProfile) -> Unit) {
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                onReady(proxy)
                warmedAtMs.set(System.currentTimeMillis())
                Log.i(TAG, "profile proxy opened: $kind")
            }
            override fun onServiceDisconnected(profile: Int) {
                Log.i(TAG, "profile proxy disconnected: $kind — will reopen on next use")
                when (kind) {
                    BluetoothProfile.A2DP -> a2dp = null
                    BluetoothProfile.HEADSET -> headset = null
                }
            }
        }
        try {
            ad.getProfileProxy(appContext, listener, kind)
        } catch (e: SecurityException) {
            Log.w(TAG, "getProfileProxy denied: ${e.message}")
        }
    }

    private fun fail(msg: String): Result<Unit> = Result.failure(IllegalStateException(msg))

    companion object {
        private const val TAG = "VortexAudioCtrl"

        // NOTE: retry count / pause live in SwitchOrchestrator, which

        const val CONNECT_SETTLE_MS: Long = 1_500

        const val DISCONNECT_SETTLE_MS: Long = 1_000

        const val CACHE_TTL_MS: Long = 300
    }
}
