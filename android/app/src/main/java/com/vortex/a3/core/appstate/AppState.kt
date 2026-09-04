package com.vortex.a3.core.appstate

import org.json.JSONException
import org.json.JSONObject

const val APPSTATE_SCHEMA_V: Int = 1

object DeviceClass {
    const val UNKNOWN = "unknown"
    const val LAPTOP = "laptop"
    const val PHONE = "phone"
    const val TABLET = "tablet"
    const val EARBUDS = "earbuds"
}

data class EarbudsInfo(
    val name: String,
    val address: String = "",
    val battery: Int? = null,
    val connected: Boolean = false,
)

data class AppState(
    val v: Int = APPSTATE_SCHEMA_V,
    val battery: Int? = null,
    val deviceClass: String = DeviceClass.UNKNOWN,
    val name: String? = null,
    val locale: String? = null,
    val localeChangedAt: Long = 0L,
    val theme: String? = null,
    val themeChangedAt: Long = 0L,
    val earbuds: EarbudsInfo? = null,
    val revoked: Boolean = false,
    val audioClaimRequest: Boolean = false,
    val callPhase: String? = null,
    val call: com.vortex.a3.core.call.CallEvent? = null,
    val callControl: com.vortex.a3.core.call.CallControl? = null,
    val notifInvoke: com.vortex.a3.core.notif.NotificationMirror? = null,
    val handoff: com.vortex.a3.core.handoff.HandoffEvent? = null,
    val mediaPlaying: Boolean = false,
    val mediaPlayAgeMs: Long = 0L,
    val mediaTitle: String? = null,
    val mediaArtist: String? = null,
    val mediaApp: String? = null,
    val mediaArtUrl: String? = null,
    val mediaNpPlaying: Boolean = false,
    val mediaControl: String? = null,
    val mediaControlSeq: Long = 0L,
    val smartSwitchEnabled: Boolean = true,
    val smartSwitchChangedAt: Long = 0L,
    val charging: Boolean = false,
    val locked: Boolean? = null,
    val unlocked: Boolean? = null,
    val lockCommand: String? = null,
    val lockCommandSeq: Long = 0L,
    val laptopMirrorReq: Boolean = false,
    val laptopMirrorExtend: Boolean = false,
    val laptopCast: LaptopCast? = null,
    val laptopCastError: String? = null,
    val cameraReq: Boolean = false,
    val cameraFacing: String = "",
    val cameraOffer: CameraOffer? = null,
    val ringSeq: Long = 0L,
    val wifiIp: String? = null,
    val displayHz: Int? = null,
    val ts: Long = System.currentTimeMillis() / 1000L,
) {
    fun toJsonBytes(): ByteArray {
        val obj = JSONObject()
        obj.put("v", v)
        battery?.let { obj.put("battery", it) }
        obj.put("class", deviceClass)
        name?.let { obj.put("name", it) }
        locale?.let { obj.put("locale", it) }
        obj.put("locale_changed_at", localeChangedAt)
        theme?.let { obj.put("theme", it) }
        obj.put("theme_changed_at", themeChangedAt)
        earbuds?.let {
            val e = JSONObject()
            e.put("name", it.name)
            if (it.address.isNotEmpty()) e.put("address", it.address)
            it.battery?.let { b -> e.put("battery", b) }
            e.put("connected", it.connected)
            obj.put("earbuds", e)
        }
        if (revoked) obj.put("revoked", true)
        if (audioClaimRequest) obj.put("audio_claim_request", true)
        callPhase?.let { obj.put("call_phase", it) }
        call?.let { obj.put("call", JSONObject(String(it.toJsonBytes(), Charsets.UTF_8))) }
        handoff?.let { obj.put("handoff", JSONObject(String(it.toJsonBytes(), Charsets.UTF_8))) }
        if (mediaPlaying) obj.put("media_playing", true)
        if (mediaPlayAgeMs > 0L) obj.put("media_play_age_ms", mediaPlayAgeMs)
        mediaTitle?.takeIf { it.isNotEmpty() }?.let { obj.put("media_title", it) }
        mediaArtist?.takeIf { it.isNotEmpty() }?.let { obj.put("media_artist", it) }
        mediaApp?.takeIf { it.isNotEmpty() }?.let { obj.put("media_app", it) }
        mediaArtUrl?.takeIf { it.isNotEmpty() }?.let { obj.put("media_art_url", it) }
        if (mediaNpPlaying) obj.put("media_np_playing", true)
        mediaControl?.takeIf { it.isNotEmpty() }?.let {
            obj.put("media_control", it)
            obj.put("media_control_seq", mediaControlSeq)
        }
        obj.put("smart_switch_enabled", smartSwitchEnabled)
        obj.put("smart_switch_changed_at", smartSwitchChangedAt)
        if (charging) obj.put("charging", true)
        locked?.let { obj.put("locked", it) }
        unlocked?.let { obj.put("unlocked", it) }
        lockCommand?.let {
            obj.put("lock_command", it)
            obj.put("lock_command_seq", lockCommandSeq)
        }
        if (laptopMirrorReq) {
            obj.put("laptop_mirror_req", true)
            obj.put("laptop_mirror_extend", laptopMirrorExtend)
        }
        laptopCast?.let {
            val c = JSONObject()
            c.put("ip", it.ip)
            c.put("port", it.port)
            c.put("key", it.key)
            obj.put("laptop_cast", c)
        }
        if (cameraReq) obj.put("camera_req", true)
        if (cameraFacing.isNotEmpty()) obj.put("camera_facing", cameraFacing)
        cameraOffer?.let {
            val c = JSONObject()
            c.put("port", it.port)
            c.put("key", it.key)
            c.put("rot", it.rot)
            obj.put("camera_offer", c)
        }
        wifiIp?.takeIf { it.isNotBlank() }?.let { obj.put("wifi_ip", it) }
        displayHz?.takeIf { it > 0 }?.let { obj.put("display_hz", it) }
        obj.put("ts", ts)
        return obj.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        private fun sanitizeBattery(raw: Int?): Int? =
            raw?.takeIf { it in 0..100 }

        private fun sanitizeName(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            return com.vortex.a3.core.pairing.PairingOrchestrator
                .sanitizePeerName(raw)
                .takeIf { it.isNotEmpty() }
        }

        fun fromJsonBytes(bytes: ByteArray): AppState? = try {
            val obj = JSONObject(String(bytes, Charsets.UTF_8))
            val earbuds: EarbudsInfo? = obj.optJSONObject("earbuds")?.let { e ->
                EarbudsInfo(
                    name = sanitizeName(e.optString("name", "Earbuds")) ?: "Earbuds",
                    address = e.optString("address", ""),
                    battery = sanitizeBattery(
                        if (e.has("battery") && !e.isNull("battery")) e.getInt("battery") else null,
                    ),
                    connected = e.optBoolean("connected", false),
                )
            }
            AppState(
                v = obj.optInt("v", APPSTATE_SCHEMA_V),
                battery = sanitizeBattery(
                    if (obj.has("battery") && !obj.isNull("battery")) obj.getInt("battery") else null,
                ),
                deviceClass = obj.optString("class", DeviceClass.UNKNOWN),
                name = sanitizeName(obj.optString("name", "")),
                locale = obj.optString("locale", "").takeIf { it.isNotBlank() },
                localeChangedAt = obj.optLong("locale_changed_at", 0L),
                theme = obj.optString("theme", "").takeIf { it.isNotBlank() },
                themeChangedAt = obj.optLong("theme_changed_at", 0L),
                earbuds = earbuds,
                revoked = obj.optBoolean("revoked", false),
                audioClaimRequest = obj.optBoolean("audio_claim_request", false),
                callPhase = obj.optString("call_phase", "").takeIf { it.isNotBlank() },
                call = obj.optJSONObject("call")?.let {
                    com.vortex.a3.core.call.CallEvent.fromJsonBytes(it.toString().toByteArray(Charsets.UTF_8))
                },
                callControl = obj.optJSONObject("call_control")?.let {
                    com.vortex.a3.core.call.CallControl.fromJsonBytes(it.toString().toByteArray(Charsets.UTF_8))
                },
                notifInvoke = obj.optJSONObject("notif_invoke")?.let {
                    com.vortex.a3.core.notif.NotificationMirror.fromJsonBytes(it.toString().toByteArray(Charsets.UTF_8))
                },
                mediaPlaying = obj.optBoolean("media_playing", false),
                mediaPlayAgeMs = obj.optLong("media_play_age_ms", 0L),
                mediaTitle = obj.optString("media_title", "").takeIf { it.isNotBlank() },
                mediaArtist = obj.optString("media_artist", "").takeIf { it.isNotBlank() },
                mediaApp = obj.optString("media_app", "").takeIf { it.isNotBlank() },
                mediaArtUrl = obj.optString("media_art_url", "").takeIf { it.isNotBlank() },
                mediaNpPlaying = obj.optBoolean("media_np_playing", false),
                mediaControl = obj.optString("media_control", "").takeIf { it.isNotBlank() },
                mediaControlSeq = obj.optLong("media_control_seq", 0L),
                smartSwitchEnabled = obj.optBoolean("smart_switch_enabled", true),
                smartSwitchChangedAt = obj.optLong("smart_switch_changed_at", 0L),
                charging = obj.optBoolean("charging", false),
                locked = if (obj.has("locked") && !obj.isNull("locked")) {
                    obj.optBoolean("locked")
                } else {
                    null
                },
                unlocked = if (obj.has("unlocked") && !obj.isNull("unlocked")) {
                    obj.optBoolean("unlocked")
                } else {
                    null
                },
                lockCommand = obj.optString("lock_command", "").takeIf { it.isNotBlank() },
                lockCommandSeq = obj.optLong("lock_command_seq", 0L),
                laptopMirrorReq = obj.optBoolean("laptop_mirror_req", false),
                laptopMirrorExtend = obj.optBoolean("laptop_mirror_extend", false),
                laptopCastError = obj.optString("laptop_cast_error", "")
                    .takeIf { it.isNotBlank() },
                laptopCast = obj.optJSONObject("laptop_cast")?.let { c ->
                    val port = c.optInt("port", 0)
                    val key = c.optString("key", "")
                    if (port != 0 && key.isNotEmpty()) {
                        LaptopCast(ip = c.optString("ip", ""), port = port, key = key)
                    } else {
                        null
                    }
                },
                cameraReq = obj.optBoolean("camera_req", false),
                ringSeq = obj.optLong("ring_seq", 0L),
                wifiIp = obj.optString("wifi_ip", "").takeIf { it.isNotBlank() },
                displayHz = obj.optInt("display_hz", 0).takeIf { it > 0 },
                cameraFacing = obj.optString("camera_facing", ""),
                cameraOffer = obj.optJSONObject("camera_offer")?.let { c ->
                    val port = c.optInt("port", 0)
                    val key = c.optString("key", "")
                    if (port != 0 && key.isNotEmpty()) {
                        CameraOffer(port = port, key = key, rot = c.optInt("rot", 0))
                    } else {
                        null
                    }
                },
                ts = obj.optLong("ts", 0L),
            )
        } catch (_: JSONException) {
            null
        }
    }
}

data class LaptopCast(
    val ip: String,
    val port: Int,
    val key: String,
)

data class CameraOffer(
    val port: Int,
    val key: String,
    val rot: Int = 0,
)
