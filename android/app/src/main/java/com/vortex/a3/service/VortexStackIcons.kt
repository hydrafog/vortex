package com.vortex.a3.service

import android.util.Log
import kotlinx.coroutines.launch


internal suspend fun VortexStack.sendAppIcon(pkg: String) {
    val png = renderAppIconPng(pkg)
    if (png == null) { sentIconPkgs.remove(pkg); return }
    val idBytes = pkg.toByteArray(Charsets.UTF_8)
    if (idBytes.size > 255) { sentIconPkgs.remove(pkg); return }
    val total = ((png.size + ICON_CHUNK - 1) / ICON_CHUNK).coerceAtLeast(1)
    if (total > 0xFFFF) { sentIconPkgs.remove(pkg); return }
    var ok = true
    outer@ for (peer in peerStore.list()) {
        val server = gattServer
        if (server == null) { ok = false; break@outer }
        for (idx in 0 until total) {
            val start = idx * ICON_CHUNK
            val end = minOf(start + ICON_CHUNK, png.size)
            val payload = java.io.ByteArrayOutputStream().apply {
                write(idBytes.size and 0xFF)
                write(idBytes)
                write((total ushr 8) and 0xFF); write(total and 0xFF)
                write((idx ushr 8) and 0xFF); write(idx and 0xFF)
                write(png, start, end - start)
            }.toByteArray()
            if (!server.sendIconChunkEncrypted(peer.peerStaticPub, payload)) { ok = false; break@outer }
            kotlinx.coroutines.delay(12)
        }
    }
    if (ok) Log.i(VortexStack.TAG, "icon sent for $pkg ($total chunks)") else sentIconPkgs.remove(pkg)
}

internal fun VortexStack.renderAppIconPng(pkg: String, sizePx: Int = 64): ByteArray? = try {
    val pm = ctx.packageManager
    val drawable = pm.getApplicationIcon(pkg)
    val bmp = android.graphics.Bitmap.createBitmap(
        sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888,
    )
    val canvas = android.graphics.Canvas(bmp)
    drawable.setBounds(0, 0, sizePx, sizePx)
    drawable.draw(canvas)
    val out = java.io.ByteArrayOutputStream()
    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
    bmp.recycle()
    out.toByteArray()
} catch (e: Exception) {
    Log.w(VortexStack.TAG, "renderAppIconPng($pkg): ${e.message}")
    null
}

internal fun VortexStack.forwardLiveActivities() {
    scope.launch {
        VortexService.liveActivityBus.collect { live ->
            if (!com.vortex.a3.core.notif.NotificationMirrorSetting.isEnabled()) return@collect
            val server = gattServer ?: return@collect
            val json = live.toJsonBytes()
            for (peer in peerStore.list()) {
                server.sendLiveActivityEncrypted(peer.peerStaticPub, json)
            }
            val pkg = live.appId
            if (pkg.isNotEmpty() && !live.ended && sentIconPkgs.add(pkg)) {
                scope.launch { sendAppIcon(pkg) }
            }
        }
    }
}
