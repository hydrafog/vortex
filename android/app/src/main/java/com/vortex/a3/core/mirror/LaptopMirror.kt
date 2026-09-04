package com.vortex.a3.core.mirror

import android.content.Context
import android.content.Intent
import android.util.Log
import com.vortex.a3.ui.LaptopMirrorActivity

object LaptopMirror {
    private const val TAG = "LaptopMirror"

    private const val MISS_LIMIT = 5

    private const val SILENT_LIMIT = 10

    @Volatile
    var requestActive: Boolean = false
        private set

    @Volatile
    private var viewerOpen: Boolean = false

    @Volatile
    private var castMisses: Int = 0

    @Volatile
    var onRequestChanged: (() -> Unit)? = null

    @Volatile
    var viewerCloser: (() -> Unit)? = null

    @Volatile
    var extendWanted: Boolean = false
        private set

    fun requestView(extend: Boolean) {
        if (requestActive) return
        extendWanted = extend
        requestActive = true
        Log.i(TAG, "view-laptop requested (extend=$extend)")
        onRequestChanged?.invoke()
    }

    fun onLaptopOffer(ctx: Context, port: Int, key: ByteArray) {
        castMisses = 0
        if (!requestActive || viewerOpen) return
        if (port == 0 || key.size != 32) {
            Log.w(TAG, "ignoring malformed laptop cast offer")
            return
        }
        viewerOpen = true
        Log.i(TAG, "laptop cast offer → launching viewer (server :$port)")
        val intent = Intent(ctx, LaptopMirrorActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(LaptopMirrorActivity.EXTRA_PORT, port)
            putExtra(LaptopMirrorActivity.EXTRA_KEY, key)
        }
        try {
            ctx.startActivity(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "viewer launch failed: ${t.message}")
            viewerOpen = false
        }
    }

    fun onViewerClosed(@Suppress("UNUSED_PARAMETER") ctx: Context) {
        viewerOpen = false
        if (!requestActive) return
        requestActive = false
        Log.i(TAG, "viewer closed → cast request cleared")
        onRequestChanged?.invoke()
    }

    fun onLaptopCastFailed(reason: String) {
        if (!requestActive) return
        requestActive = false
        castMisses = 0
        Log.w(TAG, "laptop cannot cast: $reason → request cleared")
        onCastFailed?.invoke(reason)
        viewerCloser?.invoke()
        onRequestChanged?.invoke()
    }

    @Volatile
    var onCastFailed: ((String) -> Unit)? = null

    fun onLaptopCastSilent() {
        if (!requestActive || viewerOpen) return
        if (++castMisses < SILENT_LIMIT) return
        castMisses = 0
        requestActive = false
        Log.w(TAG, "no cast offer after $SILENT_LIMIT heartbeats → request cleared")
        onCastFailed?.invoke("The laptop did not respond")
        onRequestChanged?.invoke()
    }

    fun onLaptopCastEnded() {
        if (!viewerOpen) return
        if (++castMisses < MISS_LIMIT) return
        Log.i(TAG, "laptop stopped casting ($castMisses misses) → closing viewer")
        castMisses = 0
        viewerCloser?.invoke()
    }
}
