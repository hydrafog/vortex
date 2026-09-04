package com.vortex.a3.core.contacts

import org.json.JSONArray
import org.json.JSONObject

data class Contact(
    val id: String,
    val name: String,
    val numbers: List<String>,
)

fun contactsToJsonBytes(contacts: List<Contact>): ByteArray {
    val arr = JSONArray()
    for (c in contacts) {
        val o = JSONObject()
        o.put("id", c.id)
        o.put("name", c.name)
        val nums = JSONArray()
        for (n in c.numbers) nums.put(n)
        o.put("numbers", nums)
        arr.put(o)
    }
    return arr.toString().toByteArray(Charsets.UTF_8)
}
