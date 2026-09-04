package com.vortex.a3.ui

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat


internal fun Context.isIgnoringBatteryOptimizations(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    val pm = getSystemService(PowerManager::class.java) ?: return true
    return pm.isIgnoringBatteryOptimizations(packageName)
}

internal fun Context.isNotificationAccessGranted(): Boolean =
    androidx.core.app.NotificationManagerCompat
        .getEnabledListenerPackages(this)
        .contains(packageName)

internal fun requiredPermissions(): List<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    add(Manifest.permission.READ_PHONE_STATE)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        add(Manifest.permission.ANSWER_PHONE_CALLS)
    }
    add(Manifest.permission.READ_CALL_LOG)
    add(Manifest.permission.READ_CONTACTS)
    add(Manifest.permission.CALL_PHONE)
    add(Manifest.permission.READ_SMS)
    add(Manifest.permission.SEND_SMS)
}

internal fun essentialPermissions(): List<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.BLUETOOTH_SCAN)
    } else {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.NEARBY_WIFI_DEVICES)
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}

internal fun pickerRequiredPermissions(): List<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}

internal fun isAggressiveOemRom(): Boolean {
    val m = Build.MANUFACTURER.lowercase()
    return AGGRESSIVE_OEMS.any { m.contains(it) }
}

private val AGGRESSIVE_OEMS = listOf(
    "xiaomi", "redmi", "poco",
    "huawei", "honor",
    "samsung",
    "oppo", "realme", "oneplus",
    "vivo", "iqoo",
    "meizu", "asus", "letv",
)
