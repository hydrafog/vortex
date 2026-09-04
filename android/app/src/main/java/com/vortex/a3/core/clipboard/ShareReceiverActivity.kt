package com.vortex.a3.core.clipboard

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import com.vortex.a3.service.VortexService

class ShareReceiverActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)

        if (intent?.action == Intent.ACTION_SEND) {
            val url = intent.getStringExtra(Intent.EXTRA_TEXT)?.let { extractUrl(it) }
            if (url != null) {
                val title = intent.getStringExtra(Intent.EXTRA_SUBJECT)
                    ?.takeIf { it.isNotBlank() } ?: ""
                VortexService.handoffBus.tryEmit(
                    com.vortex.a3.core.handoff.HandoffEvent(url = url, title = title, openNow = true),
                )
                Log.i(TAG, "share: forwarded a page to the laptop")
                Toast.makeText(this, "Opening on laptop…", Toast.LENGTH_SHORT).show()
                finish()
                overridePendingTransition(0, 0)
                return
            }
        }

        val uris: List<Uri> = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(streamExtra())
            Intent.ACTION_SEND_MULTIPLE -> streamListExtra()
            else -> emptyList()
        }

        if (uris.isEmpty() && intent?.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
            if (!text.isNullOrEmpty()) {
                VortexService.clipboardBus.tryEmit(text)
                Log.i(TAG, "share: forwarded ${text.length} chars to the laptop clipboard")
                Toast.makeText(this, "Sending text to laptop…", Toast.LENGTH_SHORT).show()
                finish()
                overridePendingTransition(0, 0)
                return
            }
        }

        var sent = 0
        for (uri in uris) {
            val file = ClipboardFileReader.read(this, uri)
            if (file != null) {
                VortexService.clipboardFileBus.tryEmit(file)
                Log.i(TAG, "share: forwarded file '${file.name}' (${file.bytes.size} bytes)")
                sent++
            } else {
                Log.w(TAG, "share: couldn't read $uri")
            }
        }
        val msg = when {
            sent == 0 -> "Couldn't read the shared file(s)"
            sent == 1 -> "Sending file to laptop…"
            else -> "Sending $sent files to laptop…"
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

        finish()
        overridePendingTransition(0, 0)
    }

    private fun extractUrl(text: String): String? =
        Regex("""https?://\S+""").find(text)?.value?.trimEnd('.', ',', ')', ']', '"', '\'')

    private fun streamExtra(): Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
    }

    private fun streamListExtra(): List<Uri> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        } ?: emptyList()

    companion object {
        private const val TAG = "VortexShare"
    }
}
