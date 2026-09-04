package com.vortex.a3.core.lan

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log

object IncomingFile {
    private const val TAG = "VortexIncomingFile"
    private const val CHANNEL = "vortex_files"

    private fun sanitizeName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base
            .filter { it.code >= 0x20 && it.code != 0x7f }
            .trimStart('.')
            .trim()
            .take(200)
        return cleaned.ifBlank { "vortex-file" }
    }

    fun save(ctx: Context, rawName: String, bytes: ByteArray): Boolean {
        val name = sanitizeName(rawName)
        return try {
            val resolver = ctx.contentResolver
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values)
            if (uri == null) {
                Log.w(TAG, "MediaStore insert returned null for '$name'")
                return false
            }
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Log.i(TAG, "saved '$name' (${bytes.size} bytes) to Downloads")
            true
        } catch (e: Exception) {
            Log.w(TAG, "save failed: ${e.message}")
            false
        }
    }

    private const val MAX_EXTRACT_BYTES = 512L * 1024 * 1024
    private const val MAX_EXTRACT_ENTRIES = 10_000

    private fun safeComponent(raw: String): String? {
        if (raw == "." || raw == "..") return null
        val cleaned = raw.filter { it.code >= 0x20 && it.code != 0x7f }.trim()
        return cleaned.ifBlank { null }
    }

    fun saveZipExtracted(ctx: Context, zipName: String, zipBytes: ByteArray): Boolean {
        val folder = sanitizeName(zipName.removeSuffix(".zip")).ifBlank { "vortex-folder" }
        val resolver = ctx.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        var wrote = 0
        var totalBytes = 0L
        var entries = 0
        try {
            java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(zipBytes)).use { zin ->
                while (true) {
                    val entry = zin.nextEntry ?: break
                    if (++entries > MAX_EXTRACT_ENTRIES) {
                        Log.w(TAG, "extract '$folder': too many entries; stopping")
                        break
                    }
                    if (entry.isDirectory) { zin.closeEntry(); continue }

                    val parts = entry.name.replace('\\', '/').split('/')
                        .mapNotNull { safeComponent(it) }
                    if (parts.isEmpty()) { zin.closeEntry(); continue }
                    val leaf = parts.last()
                    val subDirs = parts.dropLast(1).joinToString("/")
                    val relPath = buildString {
                        append(android.os.Environment.DIRECTORY_DOWNLOADS).append('/').append(folder)
                        if (subDirs.isNotEmpty()) append('/').append(subDirs)
                        append('/')
                    }

                    val data = zin.readBytes()
                    zin.closeEntry()
                    totalBytes += data.size
                    if (totalBytes > MAX_EXTRACT_BYTES) {
                        Log.w(TAG, "extract '$folder': over size cap; stopping")
                        break
                    }

                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, leaf)
                        put(MediaStore.Downloads.RELATIVE_PATH, relPath)
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(collection, values)
                    if (uri == null) {
                        Log.w(TAG, "extract: insert null for '$relPath$leaf'")
                        continue
                    }
                    resolver.openOutputStream(uri)?.use { it.write(data) }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    wrote++
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "extract '$folder' failed: ${e.message}")
        }
        Log.i(TAG, "extracted '$folder': $wrote files ($totalBytes bytes) to Downloads")
        return wrote > 0
    }

    fun notifyReceived(ctx: Context, label: String, count: Int) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "File transfers", NotificationManager.IMPORTANCE_DEFAULT),
                )
            }
            val title = if (count > 1) "$count files received" else "File received"
            val n = androidx.core.app.NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText("$label — saved to Downloads")
                .setAutoCancel(true)
                .build()
            nm.notify(label.hashCode(), n)
        } catch (e: Exception) {
            Log.w(TAG, "notify failed: ${e.message}")
        }
    }
}
