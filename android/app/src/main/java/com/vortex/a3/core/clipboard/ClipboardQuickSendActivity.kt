package com.vortex.a3.core.clipboard

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.vortex.a3.service.VortexService

class ClipboardQuickSendActivity : Activity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var resumed = false
    private var hasFocus = false
    private var forwarded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        scheduleRead()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        this.hasFocus = hasFocus
        if (hasFocus) scheduleRead()
    }

    private fun scheduleRead() {
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({ tryForward() }, CAPTURE_DELAY_MS)
    }

    private fun tryForward() {
        if (forwarded || !resumed || !hasFocus) return
        forwarded = true
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = cm?.primaryClip
        if (ClipboardReader.isSensitive(clip)) {
            Log.i(TAG, "quick-send: sensitive clip — not synced")
            finish()
            overridePendingTransition(0, 0)
            return
        }
        val item = if (clip != null && clip.itemCount > 0) clip.getItemAt(0) else null

        val desc = clip?.description
        val mimes = (0 until (desc?.mimeTypeCount ?: 0)).joinToString(",") { desc!!.getMimeType(it) }
        Log.i(
            TAG,
            "quick-send clip: mimes=[$mimes] hasUri=${item?.uri != null} " +
                "uriType=${item?.uri?.let { contentResolver.getType(it) }} hasText=${!item?.text.isNullOrEmpty()}",
        )

        val uri = item?.uri
        val png = if (uri != null) readImagePng(uri) else null
        if (png != null) {
            VortexService.clipboardImageBus.tryEmit(png)
            Log.i(TAG, "quick-send: forwarded image (${png.size} bytes) to laptop")
        } else {
            val text = item?.text?.toString()?.trim().orEmpty()
                .ifEmpty { item?.coerceToText(this)?.toString()?.trim().orEmpty() }
            if (text.isNotEmpty()) {
                VortexService.clipboardBus.tryEmit(text)
                Log.i(TAG, "quick-send: forwarded ${text.length} chars to laptop")
            } else {
                Log.i(TAG, "quick-send: clipboard empty")
            }
        }
        finish()
        overridePendingTransition(0, 0)
    }

    private fun readImagePng(uri: android.net.Uri): ByteArray? {
        val type = contentResolver.getType(uri) ?: return null
        if (!type.startsWith("image/")) return null
        return try {
            val bmp = contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it)
            } ?: return null
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            val bytes = out.toByteArray()
            if (bytes.size > ClipboardImage.MAX_BLE_IMAGE_BYTES) null else bytes
        } catch (e: Exception) {
            Log.w(TAG, "quick-send: image read failed: ${e.message}")
            null
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ClipboardQuickSend"
        private const val CAPTURE_DELAY_MS = 80L
    }
}
