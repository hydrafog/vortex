package com.vortex.a3.service

import android.util.Log
import kotlinx.coroutines.launch


internal fun VortexStack.startClipboardOutbound() {
    scope.launch {
        VortexService.clipboardBus.collect { text ->
            if (!com.vortex.a3.core.clipboard.ClipboardSyncSetting.isEnabled()) return@collect
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return@collect
            val capped = if (trimmed.length > com.vortex.a3.core.clipboard.ClipboardText.MAX_TEXT_CHARS) {
                trimmed.take(com.vortex.a3.core.clipboard.ClipboardText.MAX_TEXT_CHARS)
            } else {
                trimmed
            }
            val utf8Len = capped.toByteArray(Charsets.UTF_8).size
            if (utf8Len <= com.vortex.a3.core.clipboard.ClipboardText.MAX_SINGLE_FRAME_TEXT_BYTES) {
                val json = clipboardJsonBytes(capped)
                for (peer in peerStore.list()) {
                    gattServer?.sendClipboardEncrypted(peer.peerStaticPub, json)
                }
            } else {
                val chunks = com.vortex.a3.core.clipboard.ClipboardText.buildChunks(capped)
                for (peer in peerStore.list()) {
                    for (chunk in chunks) {
                        gattServer?.sendClipboardTextChunkEncrypted(peer.peerStaticPub, chunk)
                        kotlinx.coroutines.delay(12)
                    }
                }
                Log.i(VortexStack.TAG, "clipboard: long text sent chunked ($utf8Len bytes, ${chunks.size} chunks)")
            }
        }
    }

    scope.launch {
        VortexService.clipboardImageBus.collect { png ->
            if (!com.vortex.a3.core.clipboard.ClipboardSyncSetting.isEnabled()) return@collect
            if (png.isEmpty()) return@collect
            val token = com.vortex.a3.core.clipboard.ClipboardImageStore.stash(png)
            val o = org.json.JSONObject()
            o.put("token", token)
            o.put("bytes", png.size)
            val offer = o.toString().toByteArray(Charsets.UTF_8)
            var delivered = false
            for (peer in peerStore.list()) {
                if (gattServer?.sendClipboardImageOfferEncrypted(peer.peerStaticPub, offer) == true) {
                    delivered = true
                }
            }
            if (delivered) {
                Log.i(VortexStack.TAG, "clipboard image offered to laptop (${png.size} bytes, token=$token)")
            } else {
                Log.w(VortexStack.TAG, "clipboard image offer couldn't go out (BLE link down?)")
            }
        }
    }

    scope.launch {
        VortexService.clipboardFileBus.collect { file ->
            if (!com.vortex.a3.core.clipboard.ClipboardSyncSetting.isEnabled()) return@collect
            if (file.bytes.isEmpty()) return@collect
            val token = com.vortex.a3.core.clipboard.ClipboardBlobStore.stash(file.bytes)
            val o = org.json.JSONObject()
            o.put("token", token)
            o.put("bytes", file.bytes.size)
            o.put("name", file.name)
            o.put("mime", file.mime)
            val offer = o.toString().toByteArray(Charsets.UTF_8)
            Log.i(VortexStack.TAG, "clipboard file offered to laptop ('${file.name}', ${file.bytes.size} bytes, token=$token)")
            offerFileToLaptop(token, file.name, offer)
            if (file.bytes.size >= 4 * 1024 * 1024) maybeStartWifiDirect()
        }
    }
}

internal fun VortexStack.clipboardJsonBytes(text: String): ByteArray {
    val capped = if (text.length > 4096) text.take(4096) else text
    val o = org.json.JSONObject()
    o.put("text", capped)
    o.put("ts", System.currentTimeMillis())
    return o.toString().toByteArray(Charsets.UTF_8)
}
