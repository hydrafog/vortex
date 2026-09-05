package com.vortex.a3.ui.icons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SolarIconsTest {

    @Test
    fun `charging maps to BatteryChargingFull`() {
        assertEquals(
            SolarIcons.BatteryChargingFull,
            SolarIcons.batteryIconFor(pct = 10, charging = true),
        )
    }

    @Test
    fun `null battery maps to BatteryStd`() {
        assertEquals(
            SolarIcons.BatteryStd,
            SolarIcons.batteryIconFor(pct = null, charging = false),
        )
    }

    @Test
    fun `high battery maps to BatteryFull`() {
        assertEquals(
            SolarIcons.BatteryFull,
            SolarIcons.batteryIconFor(pct = 90, charging = false),
        )
        assertEquals(
            SolarIcons.BatteryFull,
            SolarIcons.batteryIconFor(pct = 80, charging = false),
        )
    }

    @Test
    fun `mid battery maps to BatteryStd`() {
        assertEquals(
            SolarIcons.BatteryStd,
            SolarIcons.batteryIconFor(pct = 60, charging = false),
        )
        assertEquals(
            SolarIcons.BatteryStd,
            SolarIcons.batteryIconFor(pct = 40, charging = false),
        )
    }

    @Test
    fun `low battery maps to Battery2Bar`() {
        assertEquals(
            SolarIcons.Battery2Bar,
            SolarIcons.batteryIconFor(pct = 20, charging = false),
        )
        assertEquals(
            SolarIcons.Battery2Bar,
            SolarIcons.batteryIconFor(pct = 15, charging = false),
        )
    }

    @Test
    fun `critical battery maps to BatteryAlert`() {
        assertEquals(
            SolarIcons.BatteryAlert,
            SolarIcons.batteryIconFor(pct = 5, charging = false),
        )
    }

    @Test
    fun `battery variants are distinct glyphs`() {
        assertNotEquals(SolarIcons.BatteryFull, SolarIcons.Battery2Bar)
        assertNotEquals(SolarIcons.BatteryStd, SolarIcons.BatteryAlert)
        assertNotEquals(SolarIcons.BatteryChargingFull, SolarIcons.BatteryFull)
    }

    @Test
    fun `locked maps to Lock and unlocked to LockOpen`() {
        assertEquals(SolarIcons.Lock, SolarIcons.lockIconFor(locked = true))
        assertEquals(SolarIcons.LockOpen, SolarIcons.lockIconFor(locked = false))
        assertNotEquals(SolarIcons.Lock, SolarIcons.LockOpen)
    }

    @Test
    fun `navigation and settings glyphs resolve`() {
        assertEquals(SolarIcons.ArrowBack, SolarIcons.resolve("ArrowBack"))
        assertEquals(SolarIcons.Settings, SolarIcons.resolve("Settings"))
        assertEquals(SolarIcons.Laptop, SolarIcons.resolve("Laptop"))
        assertEquals(SolarIcons.Smartphone, SolarIcons.resolve("Smartphone"))
        assertNotEquals(SolarIcons.ArrowBack, SolarIcons.Settings)
    }

    @Test
    fun `unknown icon name throws typed failure`() {
        assertThrows(IconLoadFailure::class.java) {
            SolarIcons.resolve("NotAnIcon")
        }
    }
}
