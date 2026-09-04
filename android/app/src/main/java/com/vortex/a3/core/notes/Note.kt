package com.vortex.a3.core.notes

import org.json.JSONArray
import org.json.JSONObject

data class Note(
    val id: String,
    val kind: String,
    val title: String = "",
    val body: String = "",
    val done: Boolean = false,
    val dueAt: Long = 0L,
    val updatedAt: Long,
    val deleted: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("kind", kind)
        put("title", title)
        put("body", body)
        put("done", done)
        put("due_at", dueAt)
        put("updated_at", updatedAt)
        put("deleted", deleted)
    }

    companion object {
        fun fromJson(o: JSONObject): Note = Note(
            id = o.getString("id"),
            kind = o.optString("kind", "note"),
            title = o.optString("title", ""),
            body = o.optString("body", ""),
            done = o.optBoolean("done", false),
            dueAt = o.optLong("due_at", 0L),
            updatedAt = o.optLong("updated_at", 0L),
            deleted = o.optBoolean("deleted", false),
        )

        fun listToBytes(items: List<Note>): ByteArray {
            val arr = JSONArray()
            items.forEach { arr.put(it.toJson()) }
            return arr.toString().toByteArray(Charsets.UTF_8)
        }

        fun listFromBytes(bytes: ByteArray): List<Note> = try {
            val arr = JSONArray(String(bytes, Charsets.UTF_8))
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        } catch (_: Throwable) {
            emptyList()
        }
    }
}
