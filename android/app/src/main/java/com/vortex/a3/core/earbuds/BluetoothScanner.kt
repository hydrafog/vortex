package com.vortex.a3.core.earbuds

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class BluetoothDeviceRow(
    val address: String,
    val name: String,
    val rssi: Int? = null,
    val connected: Boolean = false,
    val isAudio: Boolean = false,
)

object BluetoothScanner {

    private const val TAG = "VortexBtScan"
    const val DEFAULT_TIMEOUT_MS = 6_000L

    suspend fun discover(
        context: Context,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): List<BluetoothDeviceRow> {
        val ctx = context.applicationContext
        if (!hasScanPermission(ctx) || !hasConnectPermission(ctx)) {
            Log.w(TAG, "missing BT scan / connect permission — returning bonded only")
            return bondedRows(ctx)
        }
        val bm = ctx.getSystemService(BluetoothManager::class.java) ?: return emptyList()
        val adapter: BluetoothAdapter = bm.adapter ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()

        val found = mutableMapOf<String, BluetoothDeviceRow>()
        for (row in bondedRows(ctx)) {
            found[row.address] = row
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_FOUND) return
                val device: BluetoothDevice = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(
                        BluetoothDevice.EXTRA_DEVICE,
                        BluetoothDevice::class.java,
                    ) ?: return
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        ?: return
                }
                val rssi = intent.getShortExtra(
                    BluetoothDevice.EXTRA_RSSI,
                    Short.MIN_VALUE,
                ).toInt().takeIf { it != Short.MIN_VALUE.toInt() }
                val name = safeName(device) ?: device.address
                val isAudio = isAudioClass(device)
                val connected = isConnected(bm, device)
                found[device.address] = BluetoothDeviceRow(
                    address = device.address,
                    name = name,
                    rssi = rssi,
                    connected = connected,
                    isAudio = isAudio,
                )
            }
        }

        ctx.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_FOUND))
        try {
            adapter.cancelDiscovery()
        } catch (_: SecurityException) {
        }
        val started = try {
            adapter.startDiscovery()
        } catch (e: SecurityException) {
            Log.w(TAG, "startDiscovery threw SecurityException: ${e.message}")
            false
        }
        if (!started) {
            ctx.unregisterReceiver(receiver)
            return sortRows(found.values)
        }

        val finishedFilter = IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Unit> { cont ->
                val finishReceiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, intent: Intent?) {
                        if (intent?.action == BluetoothAdapter.ACTION_DISCOVERY_FINISHED) {
                            try {
                                ctx.unregisterReceiver(this)
                            } catch (_: IllegalArgumentException) {
                            }
                            if (cont.isActive) cont.resume(Unit)
                        }
                    }
                }
                ctx.registerReceiver(finishReceiver, finishedFilter)
                cont.invokeOnCancellation {
                    try {
                        ctx.unregisterReceiver(finishReceiver)
                    } catch (_: IllegalArgumentException) {
                    }
                }
            }
        }

        try {
            adapter.cancelDiscovery()
        } catch (_: SecurityException) {
        }
        try {
            ctx.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
        }

        return sortRows(found.values)
    }

    private fun bondedRows(ctx: Context): List<BluetoothDeviceRow> {
        if (!hasConnectPermission(ctx)) return emptyList()
        val bm = ctx.getSystemService(BluetoothManager::class.java) ?: return emptyList()
        val adapter = bm.adapter ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()
        val bonded = try {
            adapter.bondedDevices ?: emptySet()
        } catch (_: SecurityException) {
            return emptyList()
        }
        return bonded.map { device ->
            BluetoothDeviceRow(
                address = device.address,
                name = safeName(device) ?: device.address,
                rssi = null,
                connected = isConnected(bm, device),
                isAudio = isAudioClass(device),
            )
        }
    }

    private fun sortRows(rows: Collection<BluetoothDeviceRow>): List<BluetoothDeviceRow> =
        rows.sortedWith(
            compareByDescending<BluetoothDeviceRow> { it.isAudio }
                .thenByDescending { it.connected }
                .thenByDescending { it.rssi ?: Int.MIN_VALUE },
        )

    private fun safeName(device: BluetoothDevice): String? = try {
        device.name?.takeIf { it.isNotBlank() }
    } catch (_: SecurityException) {
        null
    }

    private fun isAudioClass(device: BluetoothDevice): Boolean = try {
        device.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO
    } catch (_: SecurityException) {
        false
    }

    private fun isConnected(bm: BluetoothManager, device: BluetoothDevice): Boolean {
        for (profile in intArrayOf(BluetoothProfile.HEADSET, BluetoothProfile.GATT)) {
            val state = try {
                bm.getConnectionState(device, profile)
            } catch (_: IllegalArgumentException) {
                continue
            } catch (_: SecurityException) {
                continue
            }
            if (state == BluetoothProfile.STATE_CONNECTED) return true
        }
        return try {
            val m = BluetoothDevice::class.java.getMethod("isConnected")
            (m.invoke(device) as? Boolean) ?: false
        } catch (_: Throwable) {
            false
        }
    }

    private fun hasScanPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasConnectPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}
