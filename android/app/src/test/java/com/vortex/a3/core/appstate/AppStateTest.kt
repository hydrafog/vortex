package com.vortex.a3.core.appstate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class AppStateTest {

    @Test
    fun `battery above 100 deserializes to null`() {
        val json = """{"v":1,"class":"phone","battery":150}""".toByteArray(Charsets.UTF_8)
        val state = AppState.fromJsonBytes(json)
        assertNotNull(state)
        assertNull(state!!.battery, "battery > 100 must be filtered to null")
    }

    @Test
    fun `battery 100 passes through`() {
        val json = """{"v":1,"class":"phone","battery":100}""".toByteArray(Charsets.UTF_8)
        val state = AppState.fromJsonBytes(json)
        assertNotNull(state)
        assertEquals(100, state!!.battery)
    }

    @Test
    fun `negative battery deserializes to null`() {
        val json = """{"v":1,"class":"phone","battery":-5}""".toByteArray(Charsets.UTF_8)
        val state = AppState.fromJsonBytes(json)
        assertNotNull(state)
        assertNull(state!!.battery, "negative battery must be filtered to null")
    }

    @Test
    fun `earbuds battery above 100 deserializes to null`() {
        val json = """
            {"v":1,"class":"phone","earbuds":{"name":"AirPods","battery":200,"connected":true}}
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val state = AppState.fromJsonBytes(json)
        assertNotNull(state)
        assertNotNull(state!!.earbuds)
        assertNull(state.earbuds!!.battery, "earbuds battery > 100 must be filtered to null")
        assertEquals(true, state.earbuds.connected, "non-battery fields still parse")
    }
}
