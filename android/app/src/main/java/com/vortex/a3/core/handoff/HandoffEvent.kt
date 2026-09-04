package com.vortex.a3.core.handoff

import org.json.JSONObject

data class HandoffEvent(
    val url: String,
    val title: String = "",
    val appId: String = "",
    val openNow: Boolean = false,
) {
    fun toJsonBytes(): ByteArray {
        val o = JSONObject()
        o.put("url", url)
        if (title.isNotEmpty()) o.put("title", title)
        if (appId.isNotEmpty()) o.put("app_id", appId)
        o.put("open_now", openNow)
        return o.toString().toByteArray(Charsets.UTF_8)
    }
}
