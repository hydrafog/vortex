package com.vortex.a3.ui
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.vortex.a3.service.VortexService
import com.vortex.a3.core.earbuds.BluetoothDeviceRow
import com.vortex.a3.core.earbuds.BluetoothScanner
import com.vortex.a3.core.earbuds.EarbudsStore
import com.vortex.a3.core.earbuds.SavedEarbuds


internal fun MainActivity.openEarbudsPicker() {
    earbudsPickerState.value = PickerState(open = true, scanning = true, rows = emptyList())
    val needed = pickerRequiredPermissions().filter {
        ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
    }
    if (needed.isEmpty()) {
        startPickerScan()
    } else {
        pickerPermissionLauncher.launch(needed.toTypedArray())
    }
}

internal fun MainActivity.rescanEarbudsPicker() {
    if (earbudsPickerState.value.scanning) return
    startPickerScan()
}

internal fun MainActivity.pickEarbud(row: BluetoothDeviceRow) {
    EarbudsStore.save(applicationContext, SavedEarbuds(address = row.address, name = row.name))
    savedEarbudsExists.value = true
    earbudsPickerState.value = PickerState(open = false)
    lifecycleScope.launch {
        try {
            localEarbudsState.value = com.vortex.a3.core.earbuds.EarbudsDetector
                .readConnectedEarbuds(applicationContext)
        } catch (t: Throwable) {
            android.util.Log.w("VortexEarbuds", "post-pick refresh failed", t)
        }
        VortexService.requestLanNudge()
    }
}

internal fun MainActivity.removeSavedEarbuds() {
    EarbudsStore.clear(applicationContext)
    savedEarbudsExists.value = false
    localEarbudsState.value = null
    VortexService.requestLanNudge()
}

internal fun MainActivity.requestSwitch() {
    val peer = peerStore.list().firstOrNull() ?: run {
        android.util.Log.w("VortexSwitch", "requestSwitch with no trusted peer")
        return
    }
    val saved = EarbudsStore.load(applicationContext) ?: run {
        android.util.Log.w("VortexSwitch", "requestSwitch with no saved earbuds")
        return
    }
    val mac = saved.address
    if (mac.isBlank()) {
        android.util.Log.w("VortexSwitch", "requestSwitch with empty saved mac")
        return
    }
    val budsOnLocal = localEarbudsState.value?.connected == true
    if (budsOnLocal) {
        android.util.Log.i("VortexSwitch", "swap: buds on local → asking peer to claim")
        VortexService.requestPeerToClaim()
    } else {
        val ok = com.vortex.a3.core.earbuds.EarbudsSwitchHolder
            .request(peer.peerStaticPub, mac)
        android.util.Log.i("VortexSwitch", "swap: claiming mac=$mac accepted=$ok")
    }
}

internal fun MainActivity.startPickerScan() {
    pickerScanJob?.cancel()
    earbudsPickerState.value = earbudsPickerState.value.copy(scanning = true)
    pickerScanJob = lifecycleScope.launch {
        val rows = try {
            BluetoothScanner.discover(applicationContext)
        } catch (t: Throwable) {
            android.util.Log.w("VortexEarbuds", "discover threw", t)
            emptyList()
        }
        val s = earbudsPickerState.value
        if (s.open) {
            earbudsPickerState.value = s.copy(scanning = false, rows = rows)
        }
    }
}

internal fun MainActivity.closeEarbudsPicker() {
    pickerScanJob?.cancel()
    earbudsPickerState.value = PickerState(open = false)
}

internal fun MainActivity.refreshSavedEarbudsFlag() {
    savedEarbudsExists.value = EarbudsStore.load(applicationContext) != null
}
