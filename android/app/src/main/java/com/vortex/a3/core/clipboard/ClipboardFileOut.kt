package com.vortex.a3.core.clipboard

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log

data class ClipboardOutgoingFile(val bytes: ByteArray, val name: String, val mime: String)

object ClipboardFileReader {
    const val MAX_FILE_BYTES = 100L * 1024 * 1024

    private const val TAG = "ClipboardFileOut"

    fun read(context: Context, uri: Uri): ClipboardOutgoingFile? = try {
        val cr = context.contentResolver
        val mime = cr.getType(uri) ?: "application/octet-stream"
        val (name, size) = queryFileInfo(context, uri)
        if (size > MAX_FILE_BYTES) {
            Log.w(TAG, "file '$name' too large ($size bytes > $MAX_FILE_BYTES): not sent")
            null
        } else {
            val bytes = cr.openInputStream(uri)?.use { it.readBytes() }
            when {
                bytes == null -> null
                bytes.isEmpty() -> null
                bytes.size > MAX_FILE_BYTES -> {
                    Log.i(TAG, "file too large (${bytes.size} bytes): not sent")
                    null
                }
                else -> ClipboardOutgoingFile(bytes, name, mime)
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "file read failed: ${e.message}")
        null
    }

    private fun queryFileInfo(context: Context, uri: Uri): Pair<String, Long> = try {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { c ->
            if (c.moveToFirst()) {
                val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIdx >= 0) c.getString(nameIdx) else null
                val size = if (sizeIdx >= 0) c.getLong(sizeIdx) else 0L
                Pair(name ?: uri.lastPathSegment ?: "file", size)
            } else {
                Pair(uri.lastPathSegment ?: "file", 0L)
            }
        } ?: Pair(uri.lastPathSegment ?: "file", 0L)
    } catch (_: Exception) {
        Pair(uri.lastPathSegment ?: "file", 0L)
    }
}
