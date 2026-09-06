package com.vortex.a3.core.lan

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log

class IncomingFileSink(
    private val ctx: Context,
    rawName: String,
    private val extractZip: Boolean = false,
) : AutoCloseable {
    val name = IncomingFile.sanitizeName(rawName)
    private var uri: android.net.Uri? = null
    private var outStream: java.io.OutputStream? = null
    private var tempFile: java.io.File? = null
    private var totalBytesWritten: Long = 0
    private var initialized = false

    private fun initStream() {
        if (initialized) return
        initialized = true
        if (extractZip) {
            tempFile = java.io.File.createTempFile("vortex_extract_", ".zip", ctx.cacheDir)
            outStream = java.io.FileOutputStream(tempFile!!)
        } else {
            val resolver = ctx.contentResolver
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            uri = resolver.insert(collection, values)
            if (uri != null) {
                outStream = resolver.openOutputStream(uri!!)
            } else {
                Log.w("VortexIncomingFile", "MediaStore insert returned null for '$name'")
            }
        }
    }

    fun writeChunk(chunkData: ByteArray): Boolean {
        initStream()
        val out = outStream ?: return false
        return try {
            out.write(chunkData)
            totalBytesWritten += chunkData.size
            true
        } catch (e: Exception) {
            Log.e("VortexIncomingFile", "writeChunk failed: ${e.message}")
            false
        }
    }

    fun finish(): Boolean {
        initStream()
        return try {
            outStream?.flush()
            outStream?.close()
            outStream = null

            if (extractZip && tempFile != null) {
                val ok = IncomingFile.extractZipFileToDownloads(ctx, name, tempFile!!)
                tempFile?.delete()
                ok
            } else if (uri != null) {
                val resolver = ctx.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                resolver.update(uri!!, values, null, null)
                Log.i("VortexIncomingFile", "saved '$name' ($totalBytesWritten bytes) to Downloads")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e("VortexIncomingFile", "finish failed: ${e.message}")
            false
        }
    }

    override fun close() {
        try {
            outStream?.close()
        } catch (_: Exception) {}
        outStream = null
        if (extractZip) {
            tempFile?.delete()
        } else if (uri != null && totalBytesWritten == 0L) {
            try {
                ctx.contentResolver.delete(uri!!, null, null)
            } catch (_: Exception) {}
        }
    }
}

object IncomingFile {
    private const val TAG = "VortexIncomingFile"
    private const val CHANNEL = "vortex_files"

    fun sanitizeName(raw: String): String {
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

    private const val MAX_EXTRACT_ENTRIES = 50_000

    private fun safeComponent(raw: String): String? {
        if (raw == "." || raw == "..") return null
        val cleaned = raw.filter { it.code >= 0x20 && it.code != 0x7f }.trim()
        return cleaned.ifBlank { null }
    }

    fun extractZipFileToDownloads(ctx: Context, zipName: String, file: java.io.File): Boolean {
        val folder = sanitizeName(zipName.removeSuffix(".zip")).ifBlank { "vortex-folder" }
        val resolver = ctx.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        var wrote = 0
        var totalBytes = 0L
        var entries = 0
        val buffer = ByteArray(65536)
        try {
            java.util.zip.ZipInputStream(java.io.FileInputStream(file)).use { zin ->
                while (true) {
                    val entry = zin.nextEntry ?: break
                    if (++entries > MAX_EXTRACT_ENTRIES) {
                        Log.w(TAG, "extract '$folder': too many entries; stopping")
                        break
                    }
                    if (entry.isDirectory) {
                        zin.closeEntry()
                        continue
                    }

                    val parts = entry.name.replace('\\', '/').split('/')
                        .mapNotNull { safeComponent(it) }
                    if (parts.isEmpty()) {
                        zin.closeEntry()
                        continue
                    }
                    val leaf = parts.last()
                    val subDirs = parts.dropLast(1).joinToString("/")
                    val relPath = buildString {
                        append(android.os.Environment.DIRECTORY_DOWNLOADS).append('/').append(folder)
                        if (subDirs.isNotEmpty()) append('/').append(subDirs)
                        append('/')
                    }

                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, leaf)
                        put(MediaStore.Downloads.RELATIVE_PATH, relPath)
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(collection, values)
                    if (uri == null) {
                        Log.w(TAG, "extract: insert null for '$relPath$leaf'")
                        zin.closeEntry()
                        continue
                    }

                    resolver.openOutputStream(uri)?.use { out ->
                        var n: Int
                        while (zin.read(buffer).also { n = it } > 0) {
                            out.write(buffer, 0, n)
                            totalBytes += n
                        }
                    }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    zin.closeEntry()
                    wrote++
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "extract '$folder' failed: ${e.message}")
        }
        Log.i(TAG, "extracted '$folder': $wrote files ($totalBytes bytes) to Downloads")
        return wrote > 0
    }

    fun saveZipExtracted(ctx: Context, zipName: String, zipBytes: ByteArray): Boolean {
        val temp = java.io.File.createTempFile("vortex_zip_bytes_", ".zip", ctx.cacheDir)
        return try {
            temp.writeBytes(zipBytes)
            extractZipFileToDownloads(ctx, zipName, temp)
        } finally {
            temp.delete()
        }
    }

    const val PROGRESS_NOTIFICATION_ID = 90210

    fun notifyProgress(
        ctx: Context,
        currentName: String,
        fileIndex: Int,
        totalFiles: Int,
        bytesReceived: Long,
        totalBytes: Long,
    ) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "File transfers", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Ongoing file transfer progress and received files"
                    },
                )
            }
            val percent = if (totalBytes > 0) ((bytesReceived * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
            val title = if (totalFiles > 1) {
                "Receiving file $fileIndex of $totalFiles ($percent%)"
            } else {
                "Receiving $currentName ($percent%)"
            }
            val receivedFormatted = formatBytes(bytesReceived)
            val totalFormatted = formatBytes(totalBytes)
            val text = "$currentName • $receivedFormatted / $totalFormatted"

            val n = androidx.core.app.NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(com.vortex.a3.R.drawable.ic_notification_download)
                .setContentTitle(title)
                .setContentText(text)
                .setProgress(100, percent, totalBytes <= 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
            nm.notify(PROGRESS_NOTIFICATION_ID, n)
        } catch (e: Exception) {
            Log.w(TAG, "notifyProgress failed: ${e.message}")
        }
    }

    fun cancelProgress(ctx: Context) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(PROGRESS_NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.w(TAG, "cancelProgress failed: ${e.message}")
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
        val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
        return String.format(java.util.Locale.US, "%.1f %s", value, units[digitGroups])
    }

    fun notifyReceived(ctx: Context, label: String, count: Int) {
        cancelProgress(ctx)
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "File transfers", NotificationManager.IMPORTANCE_DEFAULT),
                )
            }
            val title = if (count > 1) "$count files received" else "File received"
            val n = androidx.core.app.NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(com.vortex.a3.R.drawable.ic_notification_download_done)
                .setContentTitle(title)
                .setContentText("$label: saved to Downloads")
                .setAutoCancel(true)
                .build()
            nm.notify(label.hashCode(), n)
        } catch (e: Exception) {
            Log.w(TAG, "notify failed: ${e.message}")
        }
    }
}
