package com.vortex.a3.core.status

import android.content.Context
import android.os.BatteryManager

object DeviceClass {
    const val UNKNOWN: Byte = 0
    const val LAPTOP: Byte = 1
    const val PHONE: Byte = 2
    const val TABLET: Byte = 3
    const val EARBUDS: Byte = 4
}

data class BatteryPct(val value: Int?) {
    fun toByte(): Byte = (value ?: 0xFF).toByte()

    companion object {
        fun fromByte(b: Byte): BatteryPct {
            val v = b.toInt() and 0xFF
            return if (v == 0xFF || v > 100) BatteryPct(null) else BatteryPct(v)
        }
    }
}

data class LocalStatus(val battery: BatteryPct, val deviceClass: Byte) {
    fun appendTo(buf: ByteArray, offset: Int) {
        buf[offset] = battery.toByte()
        buf[offset + 1] = deviceClass
    }
}

data class PeerStatus(val battery: BatteryPct, val deviceClass: Byte)

object DeviceStatusReader {
    fun readBattery(context: Context): BatteryPct {
        return try {
            val bm = context.applicationContext.getSystemService(BatteryManager::class.java)
            val v = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (v in 0..100) BatteryPct(v) else BatteryPct(null)
        } catch (_: Exception) {
            BatteryPct(null)
        }
    }

    fun readCharging(context: Context): Boolean {
        return try {
            val bm = context.applicationContext.getSystemService(BatteryManager::class.java)
            val status = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        } catch (_: Exception) {
            false
        }
    }

    fun localPhoneStatus(context: Context): LocalStatus =
        LocalStatus(readBattery(context), DeviceClass.PHONE)

    fun decodePeerStatus(payload: ByteArray): PeerStatus? {
        if (payload.size < 10) return null
        return PeerStatus(
            battery = BatteryPct.fromByte(payload[8]),
            deviceClass = payload[9],
        )
    }
}
