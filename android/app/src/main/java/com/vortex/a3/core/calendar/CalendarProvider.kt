package com.vortex.a3.core.calendar

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

interface CalendarProvider {
    val events: StateFlow<List<CalendarEvent>>
    fun forDate(day: String): List<CalendarEvent>
    fun add(date: String, time: String, text: String, endDate: String = "", endTime: String = "", recur: String = ""): CalendarEvent
    fun remove(id: String)
}

class LocalCalendarProvider : CalendarProvider {
    override val events: StateFlow<List<CalendarEvent>> get() = CalendarStore.events

    override fun forDate(day: String): List<CalendarEvent> =
        CalendarEvent.forDate(CalendarStore.snapshot(), day)

    override fun add(date: String, time: String, text: String, endDate: String, endTime: String, recur: String): CalendarEvent =
        CalendarStore.add(date = date, time = time, text = text, endDate = endDate, endTime = endTime, recur = recur)

    override fun remove(id: String) = CalendarStore.remove(id)
}

class RicelinFileProvider(private val file: File) : CalendarProvider {
    private val _events = MutableStateFlow<List<CalendarEvent>>(emptyList())
    override val events: StateFlow<List<CalendarEvent>> = _events

    private var lastGood: List<CalendarEvent> = emptyList()

    init {
        reload()
    }

    fun reload() {
        if (!file.exists()) {
            runCatching { file.parentFile?.mkdirs(); file.writeBytes("[]".toByteArray(Charsets.UTF_8)) }
            lastGood = emptyList()
            _events.value = lastGood
            return
        }
        val bytes = try {
            file.readBytes()
        } catch (_: Exception) {
            _events.value = lastGood
            return
        }
        val raw = try {
            String(bytes, Charsets.UTF_8)
        } catch (_: Exception) {
            _events.value = lastGood
            return
        }
        if (raw.isBlank()) {
            lastGood = emptyList()
            _events.value = lastGood
            return
        }
        val parsed = try {
            CalendarEvent.listFromBytes(bytes)
        } catch (_: CalendarParseFailure) {
            _events.value = lastGood
            return
        } catch (_: Exception) {
            _events.value = lastGood
            return
        }
        lastGood = CalendarEvent.sortedForList(parsed)
        _events.value = lastGood
    }

    fun mirrorFrom(items: List<CalendarEvent>) {
        val merged = CalendarEvent.mergeUnion(_events.value, items)
        if (merged.size > _events.value.size) {
            lastGood = CalendarEvent.sortedForList(merged)
            _events.value = lastGood
            runCatching { file.writeBytes(CalendarEvent.listToBytes(lastGood)) }
        }
    }

    fun loadBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) {
            lastGood = emptyList()
            _events.value = lastGood
            return
        }
        val raw = try { String(bytes, Charsets.UTF_8) } catch (_: Exception) {
            _events.value = lastGood
            return
        }
        if (raw.isBlank()) {
            lastGood = emptyList()
            _events.value = lastGood
            return
        }
        val parsed = try {
            CalendarEvent.listFromBytes(bytes)
        } catch (_: CalendarParseFailure) {
            _events.value = lastGood
            return
        } catch (_: Exception) {
            _events.value = lastGood
            return
        }
        if (parsed.isEmpty() && raw.trim() != "[]") {
            _events.value = lastGood
            return
        }
        lastGood = CalendarEvent.sortedForList(parsed)
        _events.value = lastGood
    }

    override fun forDate(day: String): List<CalendarEvent> =
        CalendarEvent.forDate(_events.value, day)

    override fun add(date: String, time: String, text: String, endDate: String, endTime: String, recur: String): CalendarEvent {
        val e = CalendarEvent(
            id = CalendarEvent.nextId(_events.value),
            date = date.trim(),
            endDate = endDate.trim(),
            time = CalendarEvent.cleanTime(time),
            endTime = CalendarEvent.cleanTime(endTime),
            text = text.trim(),
            recur = recur.trim(),
        )
        lastGood = CalendarEvent.sortedForList(lastGood + e)
        _events.value = lastGood
        runCatching { file.writeBytes(CalendarEvent.listToBytes(lastGood)) }
        return e
    }

    override fun remove(id: String) {
        lastGood = CalendarEvent.sortedForList(lastGood.filter { it.id != id })
        _events.value = lastGood
        runCatching { file.writeBytes(CalendarEvent.listToBytes(lastGood)) }
    }
}
