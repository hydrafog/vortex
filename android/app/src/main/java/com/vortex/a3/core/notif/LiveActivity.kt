package com.vortex.a3.core.notif

import org.json.JSONObject

data class LiveActivity(
    val key: String,
    val app: String = "",
    val appId: String = "",
    val title: String = "",
    val text: String = "",
    val sub: String = "",
    val progress: Int = -1,
    val ended: Boolean = false,
    val playing: Boolean? = null,
) {
    fun toJsonBytes(): ByteArray {
        val o = JSONObject()
        o.put("key", key)
        if (app.isNotEmpty()) o.put("app", app)
        if (appId.isNotEmpty()) o.put("app_id", appId)
        if (title.isNotEmpty()) o.put("title", title)
        if (text.isNotEmpty()) o.put("text", text)
        if (sub.isNotEmpty()) o.put("sub", sub)
        o.put("progress", progress)
        if (ended) o.put("ended", true)
        playing?.let { o.put("playing", it) }
        return o.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        fun fromJsonBytes(bytes: ByteArray): LiveActivity? = try {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            LiveActivity(
                key = o.optString("key", ""),
                app = o.optString("app", ""),
                appId = o.optString("app_id", ""),
                title = o.optString("title", ""),
                text = o.optString("text", ""),
                sub = o.optString("sub", ""),
                progress = o.optInt("progress", -1),
                ended = o.optBoolean("ended", false),
                playing = if (o.has("playing")) o.optBoolean("playing") else null,
            )
        } catch (_: Throwable) {
            null
        }
    }
}
