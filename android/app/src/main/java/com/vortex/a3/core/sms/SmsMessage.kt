package com.vortex.a3.core.sms

import org.json.JSONArray
import org.json.JSONObject

data class SmsMessage(
    val id: String,
    val address: String,
    val body: String,
    val type: Int,
    val date: Long,
    val thread: Long,
    val read: Int,
)

fun smsToJsonBytes(messages: List<SmsMessage>): ByteArray {
    val arr = JSONArray()
    for (m in messages) {
        val o = JSONObject()
        o.put("id", m.id)
        o.put("address", m.address)
        o.put("body", m.body)
        o.put("type", m.type)
        o.put("date", m.date)
        o.put("thread", m.thread)
        o.put("read", m.read)
        arr.put(o)
    }
    return arr.toString().toByteArray(Charsets.UTF_8)
}
