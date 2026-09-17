package com.vortex.a3.core.earbuds

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.vortex.a3.core.appstate.EarbudsInfo

object EarbudsDetector {

    private const val TAG = "VortexEarbuds"

    fun readConnectedEarbuds(context: Context): EarbudsInfo? {
        if (!hasBluetoothConnectPermission(context)) return null
        val bm = context.applicationContext.getSystemService(BluetoothManager::class.java)
            ?: return null
        val adapter: BluetoothAdapter = bm.adapter ?: return null
        if (!adapter.isEnabled) return null

        val saved = EarbudsStore.load(context) ?: return null
        return readSavedRow(context, bm, adapter, saved)
    }

    private fun readSavedRow(
        context: Context,
        bm: BluetoothManager,
        adapter: BluetoothAdapter,
        saved: SavedEarbuds,
    ): EarbudsInfo {
        val bonded = try {
            adapter.bondedDevices ?: emptySet()
        } catch (_: SecurityException) {
            emptySet()
        }
        val match = bonded.firstOrNull {
            it.address.equals(saved.address, ignoreCase = true)
        }
        val displayName = match?.let { safeName(it) }?.takeIf { it.isNotBlank() }
            ?: saved.name
        if (match == null) {
            return EarbudsInfo(name = displayName, address = saved.address, battery = null, connected = false)
        }
        val connected = isConnected(context, bm, adapter, match)
        val battery = if (connected) readBatteryLevel(match) else null
        return EarbudsInfo(name = displayName, address = saved.address, battery = battery, connected = connected)
    }

    private fun safeName(device: BluetoothDevice): String? = try {
        device.name
    } catch (_: SecurityException) {
        null
    }

    private fun isAudioOutputConnected(context: Context, address: String): Boolean {
        val am = context.getSystemService(android.media.AudioManager::class.java) ?: return false
        val outputs = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
        for (out in outputs) {
            val isBt = out.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                out.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (android.os.Build.VERSION.SDK_INT >= 31 &&
                    (out.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET ||
                     out.type == android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER))
            if (isBt && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val outAddr = out.address
                if (!outAddr.isNullOrBlank() && outAddr.equals(address, ignoreCase = true)) {
                    return true
                }
            }
        }
        return false
    }

    private fun isConnected(
        context: Context,
        bm: BluetoothManager,
        adapter: BluetoothAdapter,
        device: BluetoothDevice,
    ): Boolean {
        if (isAudioOutputConnected(context, device.address)) return true
        val reflectionConnected = try {
            val m = BluetoothDevice::class.java.getMethod("isConnected")
            (m.invoke(device) as? Boolean) ?: false
        } catch (_: Throwable) {
            false
        }
        if (reflectionConnected) return true
        if (adapter.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED) {
            if (reflectionConnected) return true
        }
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
        return false
    }

    private fun readBatteryLevel(device: BluetoothDevice): Int? {
        return try {
            val method = BluetoothDevice::class.java.getMethod("getBatteryLevel")
            val raw = method.invoke(device) as? Int ?: return null
            if (raw < 0 || raw > 100) null else raw
        } catch (_: Throwable) {
            null
        }
    }

    private fun hasBluetoothConnectPermission(context: Context): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}
