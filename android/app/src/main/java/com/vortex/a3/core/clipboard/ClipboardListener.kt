package com.vortex.a3.core.clipboard

import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.vortex.a3.service.VortexService

class ClipboardListener(private val context: Context) {

    private val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    private val main = Handler(Looper.getMainLooper())
    private var listener: ClipboardManager.OnPrimaryClipChangedListener? = null

    fun start() = main.post {
        if (cm == null || listener != null) return@post
        val l = ClipboardManager.OnPrimaryClipChangedListener { onPrimaryClipChanged() }
        listener = l
        try {
            cm.addPrimaryClipChangedListener(l)
            Log.i(TAG, "auto clipboard capture armed (phone→laptop)")
        } catch (e: Exception) {
            listener = null
            Log.w(TAG, "failed to arm clipboard listener: ${e.message}")
        }
    }

    fun stop() = main.post {
        val l = listener ?: return@post
        try {
            cm?.removePrimaryClipChangedListener(l)
        } catch (_: Exception) {
        }
        listener = null
    }

    private fun onPrimaryClipChanged() {
        if (!ClipboardSyncSetting.isEnabled()) return
        val clip = try {
            cm?.primaryClip
        } catch (e: Exception) {
            return
        } ?: return
        if (ClipboardReader.isSensitive(clip)) {
            Log.i(TAG, "clipboard: sensitive item — not synced")
            return
        }
        if (clip.itemCount == 0) return
        val item = clip.getItemAt(0)
        if (item.uri != null && item.text.isNullOrEmpty()) return
        val text = item.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        if (ClipboardSyncGuard.wasJustApplied(ClipboardSyncGuard.sig(text))) return
        VortexService.clipboardBus.tryEmit(text)
        Log.i(TAG, "clipboard: auto-forwarded ${text.length} chars to laptop")
    }

    companion object {
        private const val TAG = "ClipboardListener"
    }
}
