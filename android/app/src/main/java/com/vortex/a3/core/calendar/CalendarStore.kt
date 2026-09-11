package com.vortex.a3.core.calendar

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

object CalendarStore {
    private const val FILE = "calendar.json"

    private var file: File? = null
    private var all: List<CalendarEvent> = emptyList()

    private val _events = MutableStateFlow<List<CalendarEvent>>(emptyList())
    val events: StateFlow<List<CalendarEvent>> = _events

    fun init(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, FILE)
        init(f)
    }

    fun init(f: File) {
        if (file != null) return
        file = f
        all = if (f.exists()) CalendarEvent.listFromBytes(runCatching { f.readBytes() }.getOrDefault(ByteArray(0))) else emptyList()
        publish()
    }

    fun initForTest(f: File, items: List<CalendarEvent> = emptyList()) {
        file = f
        all = items
        publish()
    }

    fun resetForTest() {
        file = null
        all = emptyList()
        _events.value = emptyList()
    }

    private fun publish() {
        _events.value = all.sortedWith(
            compareBy<CalendarEvent> { it.date }.thenBy { if (it.time.isEmpty()) "" else "1" }.thenBy { it.time },
        )
    }

    private fun persist() {
        file?.let { runCatching { it.writeBytes(CalendarEvent.listToBytes(all)) } }
    }

    fun snapshot(): List<CalendarEvent> = all

    fun replaceAll(items: List<CalendarEvent>) {
        all = items
        persist(); publish()
    }

    fun add(date: String, time: String = "", text: String = "", endDate: String = "", endTime: String = "", recur: String = ""): CalendarEvent {
        val cleanDate = date.trim()
        val e = CalendarEvent(
            id = CalendarEvent.nextId(all),
            date = cleanDate,
            endDate = endDate.trim(),
            time = CalendarEvent.cleanTime(time),
            endTime = CalendarEvent.cleanTime(endTime),
            text = text.trim(),
            recur = recur.trim(),
        )
        all = all + e
        persist(); publish()
        return e
    }

    fun remove(id: String) {
        all = all.filter { it.id != id }
        persist(); publish()
    }

    fun forDate(day: String): List<CalendarEvent> =
        CalendarEvent.forDate(all, day)
}
