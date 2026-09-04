package com.vortex.a3.core.notif

import org.json.JSONObject

data class NotificationMirror(
    val app: String,
    val appId: String = "",
    val title: String,
    val text: String,
    val ts: Long,
    val key: String = "",
    val dismiss: Boolean = false,
    val actions: List<String> = emptyList(),
    val replyIndex: Int = -1,
    val invokeIndex: Int = -1,
    val reply: String = "",
    val seq: Long = 0,
    val resync: Boolean = false,
    val knownKeys: List<String> = emptyList(),
) {
    fun toJsonBytes(): ByteArray {
        val o = JSONObject()
        o.put("app", app)
        if (appId.isNotEmpty()) o.put("app_id", appId)
        o.put("title", title)
        o.put("text", text)
        o.put("ts", ts)
        if (key.isNotEmpty()) o.put("key", key)
        if (dismiss) o.put("dismiss", true)
        if (actions.isNotEmpty()) o.put("actions", org.json.JSONArray(actions))
        if (replyIndex >= 0) o.put("reply_index", replyIndex)
        if (invokeIndex >= 0) o.put("invoke_index", invokeIndex)
        if (reply.isNotEmpty()) o.put("reply", reply)
        if (seq > 0) o.put("seq", seq)
        if (resync) o.put("resync", true)
        if (knownKeys.isNotEmpty()) o.put("known_keys", org.json.JSONArray(knownKeys))
        return o.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        fun fromJsonBytes(bytes: ByteArray): NotificationMirror? = try {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            NotificationMirror(
                app = o.optString("app", ""),
                appId = o.optString("app_id", ""),
                title = o.optString("title", ""),
                text = o.optString("text", ""),
                ts = o.optLong("ts", 0L),
                key = o.optString("key", ""),
                dismiss = o.optBoolean("dismiss", false),
                actions = o.optJSONArray("actions")?.let { arr ->
                    (0 until arr.length()).map { arr.optString(it, "") }
                } ?: emptyList(),
                replyIndex = o.optInt("reply_index", -1),
                invokeIndex = o.optInt("invoke_index", -1),
                reply = o.optString("reply", ""),
                seq = o.optLong("seq", 0L),
                resync = o.optBoolean("resync", false),
                knownKeys = o.optJSONArray("known_keys")?.let { arr ->
                    (0 until arr.length()).map { arr.optString(it, "") }
                } ?: emptyList(),
            )
        } catch (_: Throwable) {
            null
        }
    }
}
