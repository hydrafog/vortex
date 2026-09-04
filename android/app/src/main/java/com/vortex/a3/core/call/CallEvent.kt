package com.vortex.a3.core.call

import org.json.JSONObject

data class CallEvent(
    val id: String,
    val phase: String,
    val name: String = "",
    val number: String = "",
    val startedAt: Long = 0L,
    val outgoing: Boolean = false,
    val connected: Boolean = false,
    val appId: String = "",
    val sentAt: Long = 0L,
    val muted: Boolean = false,
    val speaker: Boolean = false,
    val hasEarbuds: Boolean = false,
) {
    fun toJsonBytes(): ByteArray {
        val o = JSONObject()
        o.put("id", id)
        o.put("phase", phase)
        if (name.isNotEmpty()) o.put("name", name)
        if (number.isNotEmpty()) o.put("number", number)
        if (startedAt > 0) o.put("started_at", startedAt)
        if (outgoing) o.put("outgoing", true)
        if (connected) o.put("connected", true)
        if (appId.isNotEmpty()) o.put("app_id", appId)
        if (sentAt > 0) o.put("sent_at", sentAt)
        if (muted) o.put("muted", true)
        if (speaker) o.put("speaker", true)
        if (hasEarbuds) o.put("has_earbuds", true)
        return o.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        const val PHASE_RINGING = "ringing"
        const val PHASE_ACTIVE = "active"
        const val PHASE_ENDED = "ended"

        fun fromJsonBytes(bytes: ByteArray): CallEvent? = try {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            val id = o.optString("id", "")
            val phase = o.optString("phase", "")
            if (id.isEmpty() || phase.isEmpty()) null
            else CallEvent(
                id = id,
                phase = phase,
                name = o.optString("name", ""),
                number = o.optString("number", ""),
                startedAt = o.optLong("started_at", 0L),
                outgoing = o.optBoolean("outgoing", false),
                connected = o.optBoolean("connected", false),
                appId = o.optString("app_id", ""),
                sentAt = o.optLong("sent_at", 0L),
                muted = o.optBoolean("muted", false),
                speaker = o.optBoolean("speaker", false),
                hasEarbuds = o.optBoolean("has_earbuds", false),
            )
        } catch (_: Throwable) {
            null
        }
    }
}

data class CallControl(
    val id: String,
    val action: String,
    val arg: String = "",
    val seq: Long = 0L,
) {
    fun toJsonBytes(): ByteArray {
        val o = JSONObject()
        o.put("id", id)
        o.put("action", action)
        if (arg.isNotEmpty()) o.put("arg", arg)
        if (seq > 0) o.put("seq", seq)
        return o.toString().toByteArray(Charsets.UTF_8)
    }

    object Action {
        const val ACCEPT = "accept"
        const val DECLINE = "decline"
        const val END = "end"
        const val MUTE = "mute"
        const val UNMUTE = "unmute"
        const val SPEAKER_ON = "speaker_on"
        const val SPEAKER_OFF = "speaker_off"
        const val SILENCE = "silence"
        const val SMS_REJECT = "sms_reject"

        const val ORIGINATE_CALL = "originate_call"

        const val SEND_SMS = "send_sms"

        const val MARK_READ = "mark_read"

        const val LOAD_THREAD = "load_thread"

        const val MEDIA_PLAY_PAUSE = "media_play_pause"
        const val MEDIA_NEXT = "media_next"
        const val MEDIA_PREV = "media_prev"
    }

    companion object {
        fun fromJsonBytes(bytes: ByteArray): CallControl? = try {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            val id = o.optString("id", "")
            val action = o.optString("action", "")
            if (action.isEmpty()) null
            else CallControl(
                id = id,
                action = action,
                arg = o.optString("arg", ""),
                seq = o.optLong("seq", 0L),
            )
        } catch (_: Throwable) {
            null
        }
    }
}
