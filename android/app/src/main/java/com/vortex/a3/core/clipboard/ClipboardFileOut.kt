package com.vortex.a3.core.clipboard

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log

data class ClipboardOutgoingFile(val bytes: ByteArray, val name: String, val mime: String)

object ClipboardFileReader {
    const val MAX_FILE_BYTES = 64L * 1024 * 1024

    private const val TAG = "ClipboardFileOut"

    fun read(context: Context, uri: Uri): ClipboardOutgoingFile? = try {
        val cr = context.contentResolver
        val mime = cr.getType(uri) ?: "application/octet-stream"
        val name = displayName(context, uri) ?: "file"
        val bytes = cr.openInputStream(uri)?.use { it.readBytes() }
        when {
            bytes == null -> null
            bytes.isEmpty() -> null
            bytes.size > MAX_FILE_BYTES -> {
                Log.i(TAG, "file too large (${bytes.size} bytes) — not sent")
                null
            }
            else -> ClipboardOutgoingFile(bytes, name, mime)
        }
    } catch (e: Exception) {
        Log.w(TAG, "file read failed: ${e.message}")
        null
    }

    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx) else null
                } else {
                    null
                }
            }
            ?: uri.lastPathSegment
    } catch (_: Exception) {
        uri.lastPathSegment
    }
}
