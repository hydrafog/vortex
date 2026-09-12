package com.vortex.a3.core.calendar

import org.json.JSONArray
import org.json.JSONObject

class CalendarParseFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

data class CalendarEvent(
    val id: String,
    val date: String,
    val endDate: String = "",
    val time: String = "",
    val endTime: String = "",
    val text: String = "",
    val recur: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("date", date)
        put("endDate", endDate)
        put("time", time)
        put("endTime", endTime)
        put("text", text)
        put("recur", recur)
    }

    companion object {
        private val DATE_RE = Regex("""\d{4}-\d{2}-\d{2}""")
        private val TIME_RE = Regex("""\d{1,2}:\d{2}""")

        fun cleanTime(raw: String): String {
            val v = raw.trim()
            return if (TIME_RE.matches(v)) v else ""
        }

        fun lastDay(e: CalendarEvent): String =
            if (e.endDate.isNotEmpty()) e.endDate else e.date

        fun covers(e: CalendarEvent, day: String): Boolean {
            if (e.recur == "year") {
                if (e.date.length < 5 || day.length < 5) return false
                return day.substring(5) == e.date.substring(5)
            }
            if (e.recur == "month") {
                if (e.date.length < 8 || day.length < 8) return false
                return day.substring(8) == e.date.substring(8)
            }
            return day >= e.date && day <= lastDay(e)
        }

        fun forDate(events: List<CalendarEvent>, day: String): List<CalendarEvent> =
            events.filter { covers(it, day) }.sortedWith(
                compareBy<CalendarEvent> { if (it.time.isEmpty()) "" else "1" }.thenBy { it.time },
            )

        fun hasEvents(events: List<CalendarEvent>, day: String): Boolean =
            forDate(events, day).isNotEmpty()

        fun nextId(events: List<CalendarEvent>): String {
            val max = events.mapNotNull { it.id.toLongOrNull() }.maxOrNull() ?: 0L
            return (max + 1).toString()
        }

        fun isValidDate(v: String): Boolean = DATE_RE.matches(v)

        fun isValidTime(v: String): Boolean = v.isEmpty() || TIME_RE.matches(v)

        fun fromJson(o: JSONObject): CalendarEvent {
            val date = o.optString("date", "")
            if (!isValidDate(date)) {
                throw CalendarParseFailure("invalid date: $date")
            }
            val time = o.optString("time", "")
            val endTime = o.optString("endTime", "")
            if (!isValidTime(time) || !isValidTime(endTime)) {
                throw CalendarParseFailure("invalid time: $time/$endTime")
            }
            var recur = o.optString("recur", "")
            if (recur.isEmpty() && o.optBoolean("yearly", false)) {
                recur = "year"
            }
            if (recur.isNotEmpty() && recur != "year" && recur != "month") {
                throw CalendarParseFailure("invalid recur: $recur")
            }
            val endDate = o.optString("endDate", "")
            if (endDate.isNotEmpty() && !isValidDate(endDate)) {
                throw CalendarParseFailure("invalid endDate: $endDate")
            }
            val rawId = o.opt("id")
            val id = when {
                rawId == null || rawId == JSONObject.NULL -> ""
                rawId is Number -> {
                    val l = rawId.toLong()
                    if (rawId.toDouble() == l.toDouble()) l.toString() else rawId.toString()
                }
                else -> rawId.toString()
            }
            return CalendarEvent(
                id = id,
                date = date,
                endDate = endDate,
                time = time,
                endTime = endTime,
                text = o.optString("text", ""),
                recur = recur,
            )
        }

        fun listToBytes(items: List<CalendarEvent>): ByteArray {
            val arr = JSONArray()
            items.forEach { arr.put(it.toJson()) }
            return arr.toString().toByteArray(Charsets.UTF_8)
        }

        fun sortedForList(items: List<CalendarEvent>): List<CalendarEvent> =
            items.sortedWith(
                compareBy<CalendarEvent> { it.date }.thenBy { if (it.time.isEmpty()) "" else "1" }.thenBy { it.time },
            )

        fun mergeUnion(into: List<CalendarEvent>, from: List<CalendarEvent>): List<CalendarEvent> {
            val ids = into.map { it.id }.toSet()
            return into + from.filter { it.id !in ids }
        }

        fun listFromBytes(bytes: ByteArray): List<CalendarEvent> {
            val raw = String(bytes, Charsets.UTF_8)
            if (raw.isBlank()) {
                return emptyList()
            }
            try {
                val arr = JSONArray(raw)
                return (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
            } catch (e: CalendarParseFailure) {
                throw e
            } catch (e: Exception) {
                throw CalendarParseFailure("corrupt calendar body", e)
            }
        }
    }
}
