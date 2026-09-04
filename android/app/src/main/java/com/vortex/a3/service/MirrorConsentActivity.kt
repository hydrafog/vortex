package com.vortex.a3.service

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log

class MirrorConsentActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.vortex.a3.core.mirror.MirrorRequestNotification.clear(this)
        if (savedInstanceState != null) {
            return
        }
        try {
            val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            @Suppress("DEPRECATION")
            startActivityForResult(pm.createScreenCaptureIntent(), REQ)
        } catch (t: Throwable) {
            Log.w(TAG, "consent launch failed: ${t.message}")
            MirrorConsent.deliver(false)
            finish()
        }
    }

    @Deprecated("startActivityForResult result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ && resultCode == RESULT_OK && data != null) {
            MirrorConsent.resultCode = resultCode
            MirrorConsent.resultData = data
            Log.i(TAG, "mirror consent granted")
            MirrorConsent.deliver(true)
        } else {
            Log.i(TAG, "mirror consent denied")
            MirrorConsent.clear()
            MirrorConsent.deliver(false)
        }
        finish()
    }

    companion object {
        private const val TAG = "VortexMirror"
        private const val REQ = 0x4D49

        fun launch(context: android.content.Context) {
            context.startActivity(
                Intent(context, MirrorConsentActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

object MirrorConsent {
    @Volatile var resultCode: Int = 0
    @Volatile var resultData: Intent? = null

    @Volatile var onResult: ((granted: Boolean) -> Unit)? = null

    @Volatile private var promptInFlight: Boolean = false
    @Volatile private var promptSince: Long = 0L

    fun hasToken(): Boolean = resultData != null

    @Synchronized
    fun beginPrompt(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (promptInFlight && now - promptSince < 10_000L) return false
        promptInFlight = true
        promptSince = now
        return true
    }

    @Synchronized
    fun deliver(granted: Boolean) {
        promptInFlight = false
        val cb = onResult
        onResult = null
        cb?.invoke(granted)
    }

    @Synchronized
    fun clear() {
        resultCode = 0
        resultData = null
    }
}
