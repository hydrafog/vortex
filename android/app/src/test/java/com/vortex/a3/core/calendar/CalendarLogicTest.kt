package com.vortex.a3.core.calendar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CalendarLogicTest {

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
    fun `AC2 corrupt bytes map to empty list`() {
        val out = CalendarEvent.listFromBytes("{not json".toByteArray(Charsets.UTF_8))
        assertEquals(emptyList<CalendarEvent>(), out)
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
}
