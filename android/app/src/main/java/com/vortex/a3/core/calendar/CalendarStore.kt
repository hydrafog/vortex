package com.vortex.a3.core.calendar

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

object CalendarStore {
    private const val FILE = "calendar.json"

    private var file: File? = null
    private var all: List<CalendarEvent> = emptyList()
    private var lastGood: List<CalendarEvent> = emptyList()

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
        if (!f.exists()) {
            all = emptyList()
            lastGood = emptyList()
            publish()
            return
        }
        val bytes = runCatching { f.readBytes() }.getOrNull() ?: ByteArray(0)
        val raw = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull() ?: ""
        if (raw.isBlank()) {
            all = emptyList()
            lastGood = emptyList()
            publish()
            return
        }
        try {
            all = CalendarEvent.listFromBytes(bytes)
            lastGood = all
        } catch (_: CalendarParseFailure) {
            all = lastGood
        } catch (_: Exception) {
            all = lastGood
        }
        publish()
    }

    fun reload() {
        val f = file ?: return
        if (!f.exists()) {
            all = emptyList()
            lastGood = emptyList()
            persist()
            publish()
            return
        }
        val bytes = try {
            f.readBytes()
        } catch (_: Exception) {
            return
        }
        val raw = try {
            String(bytes, Charsets.UTF_8)
        } catch (_: Exception) {
            return
        }
        if (raw.isBlank()) {
            all = emptyList()
            lastGood = emptyList()
            publish()
            return
        }
        try {
            val parsed = CalendarEvent.listFromBytes(bytes)
            all = parsed
            lastGood = parsed
            publish()
        } catch (_: CalendarParseFailure) {
            return
        } catch (_: Exception) {
            return
        }
    }

    fun mirrorFrom(items: List<CalendarEvent>) {
        val merged = CalendarEvent.mergeUnion(all, items)
        if (merged.size > all.size) {
            all = merged
            lastGood = merged
            persist()
            publish()
        }
    }

    fun initForTest(f: File, items: List<CalendarEvent> = emptyList()) {
        file = f
        all = items
        lastGood = items
        publish()
    }

    fun resetForTest() {
        file = null
        all = emptyList()
        lastGood = emptyList()
        _events.value = emptyList()
    }

    private fun publish() {
        _events.value = CalendarEvent.sortedForList(all)
    }

    private fun persist() {
        file?.let { runCatching { it.writeBytes(CalendarEvent.listToBytes(all)) } }
    }

    fun snapshot(): List<CalendarEvent> = all

    fun replaceAll(items: List<CalendarEvent>) {
        all = items
        lastGood = items
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
        lastGood = all
        persist(); publish()
        return e
    }

    fun remove(id: String) {
        all = all.filter { it.id != id }
        lastGood = all
        persist(); publish()
    }

    fun forDate(day: String): List<CalendarEvent> =
        CalendarEvent.forDate(all, day)
}
