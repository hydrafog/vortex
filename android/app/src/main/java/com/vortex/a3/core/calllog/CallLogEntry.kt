package com.vortex.a3.core.calllog

import org.json.JSONArray
import org.json.JSONObject

data class CallLogEntry(
    val id: String,
    val number: String,
    val name: String,
    val type: Int,
    val date: Long,
    val duration: Long,
)

fun callLogToJsonBytes(entries: List<CallLogEntry>): ByteArray {
    val arr = JSONArray()
    for (e in entries) {
        val o = JSONObject()
        o.put("id", e.id)
        o.put("number", e.number)
        o.put("name", e.name)
        o.put("type", e.type)
        o.put("date", e.date)
        o.put("duration", e.duration)
        arr.put(o)
    }
    return arr.toString().toByteArray(Charsets.UTF_8)
}
