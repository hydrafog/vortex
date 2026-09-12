package com.vortex.a3.core.calendar

import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.nio.file.Files

class CalendarLogicTest {

    @AfterEach
    fun tearDown() {
        CalendarStore.resetForTest()
    }

    private fun event(
        id: String = "1",
        date: String = "2026-06-10",
        endDate: String = "",
        time: String = "",
        text: String = "Title",
        recur: String = "",
    ) = CalendarEvent(id = id, date = date, endDate = endDate, time = time, text = text, recur = recur)

    @Test
    fun `AC2 single-day covers its date only`() {
        val e = event(date = "2026-06-10")
        assertTrue(CalendarEvent.covers(e, "2026-06-10"))
        assertFalse(CalendarEvent.covers(e, "2026-06-11"))
    }

    @Test
    fun `AC2 multi-day span is inclusive`() {
        val e = event(date = "2026-06-10", endDate = "2026-06-12")
        assertTrue(CalendarEvent.covers(e, "2026-06-10"))
        assertTrue(CalendarEvent.covers(e, "2026-06-11"))
        assertTrue(CalendarEvent.covers(e, "2026-06-12"))
        assertFalse(CalendarEvent.covers(e, "2026-06-13"))
        assertEquals("2026-06-12", CalendarEvent.lastDay(e))
    }

    @Test
    fun `AC2 recurring yearly matches MM-DD tail`() {
        val e = event(date = "2020-02-29", recur = "year")
        assertTrue(CalendarEvent.covers(e, "2024-02-29"))
        assertFalse(CalendarEvent.covers(e, "2024-02-28"))
        assertFalse(CalendarEvent.covers(e, "2023-02-28"))
    }

    @Test
    fun `AC2 recurring monthly matches DD tail`() {
        val e = event(date = "2026-01-31", recur = "month")
        assertTrue(CalendarEvent.covers(e, "2026-03-31"))
        assertFalse(CalendarEvent.covers(e, "2026-03-30"))
    }

    @Test
    fun `AC2 recurring ignores endDate`() {
        val e = event(date = "2026-01-15", endDate = "2026-01-20", recur = "month")
        assertTrue(CalendarEvent.covers(e, "2026-02-15"))
        assertFalse(CalendarEvent.covers(e, "2026-02-16"))
    }

    @Test
    fun `AC2 forDate sorts empty time first then HH-MM`() {
        val events = listOf(
            event(id = "1", date = "2026-06-10", time = "10:00"),
            event(id = "2", date = "2026-06-10", time = ""),
            event(id = "3", date = "2026-06-10", time = "09:00"),
        )
        val out = CalendarEvent.forDate(events, "2026-06-10")
        assertEquals(listOf("2", "3", "1"), out.map { it.id })
        assertTrue(CalendarEvent.hasEvents(events, "2026-06-10"))
        assertFalse(CalendarEvent.hasEvents(events, "2026-06-11"))
    }

    @Test
    fun `AC3 corrupt bytes throw CalendarParseFailure`() {
        assertThrows<CalendarParseFailure> {
            CalendarEvent.listFromBytes("{not json".toByteArray(Charsets.UTF_8))
        }
    }

    @Test
    fun `AC2 cleanTime allowlist keeps HH-MM only`() {
        assertEquals("09:00", CalendarEvent.cleanTime("09:00"))
        assertEquals("9:00", CalendarEvent.cleanTime("9:00"))
        assertEquals("", CalendarEvent.cleanTime("morning"))
        assertEquals("", CalendarEvent.cleanTime("09:00:00"))
    }

    @Test
    fun `AC2 nextId goes past max numeric id`() {
        val events = listOf(event(id = "2"), event(id = "7"), event(id = "abc"))
        assertEquals("8", CalendarEvent.nextId(events))
        assertEquals("1", CalendarEvent.nextId(emptyList()))
    }

    @Test
    fun `AC1 numeric id coerces to string`() {
        val o = JSONObject().apply {
            put("id", 7)
            put("date", "2026-06-10")
        }
        assertEquals("7", CalendarEvent.fromJson(o).id)
    }

    @Test
    fun `AC1 missing keys default to empty strings`() {
        val o = JSONObject().apply {
            put("id", "3")
            put("date", "2026-06-10")
        }
        val e = CalendarEvent.fromJson(o)
        assertEquals("", e.endDate)
        assertEquals("", e.time)
        assertEquals("", e.endTime)
        assertEquals("", e.text)
        assertEquals("", e.recur)
    }

    @Test
    fun `AC1 yearly flag heals to year recur`() {
        val healed = JSONObject().apply {
            put("id", "4")
            put("date", "2020-02-29")
            put("yearly", true)
        }
        assertEquals("year", CalendarEvent.fromJson(healed).recur)
        val plain = JSONObject().apply {
            put("id", "5")
            put("date", "2026-06-10")
        }
        assertEquals("", CalendarEvent.fromJson(plain).recur)
    }

    @Test
    fun `AC2 seven-key round-trip with span year and month`() {
        val items = listOf(
            CalendarEvent(id = "1", date = "2026-06-10", endDate = "2026-06-12", time = "09:00", endTime = "10:00", text = "Span"),
            CalendarEvent(id = "2", date = "2020-02-29", time = "", text = "Yearly", recur = "year"),
            CalendarEvent(id = "3", date = "2026-01-31", time = "12:00", text = "Monthly", recur = "month"),
        )
        val bytes = CalendarEvent.listToBytes(items)
        val arr = org.json.JSONArray(String(bytes, Charsets.UTF_8))
        val first = arr.getJSONObject(0)
        assertTrue(first.has("endDate") && first.has("endTime") && first.has("recur"))
        assertTrue(first.has("id") && first.has("date") && first.has("time") && first.has("text"))
        val back = CalendarEvent.listFromBytes(bytes)
        assertEquals(items, back)
    }

    @Test
    fun `AC1 cross-provider add parity keeps seven keys after reload`() {
        val dir = Files.createTempDirectory("cal-parity").toFile()
        try {
            val localFile = File(dir, "calendar.json")
            CalendarStore.initForTest(localFile)
            val local = LocalCalendarProvider()
            local.add("2026-06-10", "09:00", "Span", endDate = "2026-06-12", endTime = "10:00", recur = "")
            CalendarStore.reload()
            val listed = local.forDate("2026-06-11")
            assertEquals(1, listed.size)
            assertEquals("2026-06-12", listed[0].endDate)
            assertEquals("10:00", listed[0].endTime)
            val ricelinFile = File(dir, "events.json")
            ricelinFile.writeBytes(CalendarEvent.listToBytes(CalendarStore.snapshot()))
            val ricelin = RicelinFileProvider(ricelinFile)
            assertTrue(ricelin.forDate("2026-06-11").any { it.text == "Span" })
            ricelin.add("2021-03-15", "", "Annual", recur = "year")
            ricelin.reload()
            assertTrue(ricelin.forDate("2026-03-15").any { it.text == "Annual" })
        } finally {
            CalendarStore.resetForTest()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `AC3 store keepsLastGood on corrupt body`() {
        val dir = Files.createTempDirectory("cal-good").toFile()
        try {
            val f = File(dir, "calendar.json")
            val seed = listOf(event(id = "1", date = "2026-06-10"))
            f.writeBytes(CalendarEvent.listToBytes(seed))
            CalendarStore.initForTest(f, seed)
            f.writeBytes("{corrupt".toByteArray(Charsets.UTF_8))
            CalendarStore.reload()
            assertEquals(seed, CalendarStore.snapshot())
            val rf = File(dir, "events.json")
            rf.writeBytes(CalendarEvent.listToBytes(seed))
            val provider = RicelinFileProvider(rf)
            rf.writeBytes("{corrupt".toByteArray(Charsets.UTF_8))
            provider.reload()
            assertEquals(seed.map { it.id }, provider.events.value.map { it.id })
        } finally {
            CalendarStore.resetForTest()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `AC3 missing file self-heals to empty`() {
        val dir = Files.createTempDirectory("cal-missing").toFile()
        try {
            val missing = File(dir, "absent.json")
            assertFalse(missing.exists())
            CalendarStore.initForTest(missing, emptyList())
            CalendarStore.reload()
            assertEquals(emptyList<CalendarEvent>(), CalendarStore.snapshot())
            val ricelinMissing = File(dir, "absent-events.json")
            val provider = RicelinFileProvider(ricelinMissing)
            assertEquals(emptyList<CalendarEvent>(), provider.events.value)
            assertTrue(ricelinMissing.exists())
        } finally {
            CalendarStore.resetForTest()
            dir.deleteRecursively()
        }
    }
}
