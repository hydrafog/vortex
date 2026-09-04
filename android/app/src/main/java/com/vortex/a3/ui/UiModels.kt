package com.vortex.a3.ui

import com.vortex.a3.core.ble.AdvPayload
import com.vortex.a3.core.earbuds.BluetoothDeviceRow

data class PickerState(
    val open: Boolean = false,
    val scanning: Boolean = false,
    val rows: List<BluetoothDeviceRow> = emptyList(),
)

sealed class AdvertiseState {
    data object Idle : AdvertiseState()
    data object Starting : AdvertiseState()
    data class Active(val payload: AdvPayload) : AdvertiseState()
    data object TrustedPresence : AdvertiseState()
    data class Error(val reason: String) : AdvertiseState()
}
