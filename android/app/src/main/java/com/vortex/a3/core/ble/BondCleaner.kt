package com.vortex.a3.core.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.util.Log

object BondCleaner {
    private const val TAG = "VortexBondCleaner"

    fun removeBond(adapter: BluetoothAdapter, address: String): Boolean {
        return try {
            val device = adapter.getRemoteDevice(address)
            if (device.bondState == BluetoothDevice.BOND_NONE) {
                Log.i(TAG, "removeBond: $address already unbonded")
                return false
            }
            val ok = BluetoothDevice::class.java
                .getMethod("removeBond")
                .invoke(device) as? Boolean ?: false
            Log.i(TAG, "removeBond($address) -> $ok (was bondState=${device.bondState})")
            ok
        } catch (e: Exception) {
            Log.w(TAG, "removeBond($address) failed: ${e.message}")
            false
        }
    }
}
