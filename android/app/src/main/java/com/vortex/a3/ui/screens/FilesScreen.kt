package com.vortex.a3.ui.screens

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight as FW
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vortex.a3.core.lan.IncomingFile
import com.vortex.a3.core.lan.TransferProgress
import com.vortex.a3.ui.components.AppHeader
import com.vortex.a3.ui.components.pillowCard
import com.vortex.a3.ui.icons.SolarIcons
import com.vortex.a3.ui.icons.SolarDuotoneIcon
import com.vortex.a3.ui.str
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ReceivedTransferInfo(
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val itemCount: Int = 0,
    val lastModified: Long,
    val contentUri: android.net.Uri? = null,
    val file: File? = null,
)

@Composable
fun FilesScreen(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var transfersList by remember { mutableStateOf<List<ReceivedTransferInfo>>(emptyList()) }
    val activeTransfer by IncomingFile.currentTransfer.collectAsState()

    fun loadTransfers() {
        try {
            val result = mutableListOf<ReceivedTransferInfo>()
            val dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (dlDir != null && dlDir.exists() && dlDir.isDirectory) {
                val entries = dlDir.listFiles()
                    ?.filter { !it.name.startsWith(".") }
                    ?.sortedByDescending { it.lastModified() }
                    ?.take(40) ?: emptyList()

                for (entry in entries) {
                    if (entry.isDirectory) {
                        val children = entry.listFiles()?.filter { !it.name.startsWith(".") } ?: emptyList()
                        val totalSize = children.sumOf { if (it.isFile) it.length() else 0L }
                        result.add(
                            ReceivedTransferInfo(
                                name = entry.name,
                                isDirectory = true,
                                sizeBytes = totalSize,
                                itemCount = children.size,
                                lastModified = entry.lastModified(),
                                file = entry,
                            ),
                        )
                    } else if (entry.isFile) {
                        result.add(
                            ReceivedTransferInfo(
                                name = IncomingFile.sanitizeName(entry.name),
                                isDirectory = false,
                                sizeBytes = entry.length(),
                                lastModified = entry.lastModified(),
                                file = entry,
                            ),
                        )
                    }
                }
            }

            if (result.isEmpty()) {
                try {
                    val projection = arrayOf(
                        MediaStore.Downloads._ID,
                        MediaStore.Downloads.DISPLAY_NAME,
                        MediaStore.Downloads.SIZE,
                        MediaStore.Downloads.DATE_MODIFIED,
                        MediaStore.Downloads.RELATIVE_PATH,
                    )
                    val uri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    val cursor = context.contentResolver.query(
                        uri,
                        projection,
                        null,
                        null,
                        "${MediaStore.Downloads.DATE_MODIFIED} DESC",
                    )
                    cursor?.use { c ->
                        val idCol = c.getColumnIndex(MediaStore.Downloads._ID)
                        val nameCol = c.getColumnIndex(MediaStore.Downloads.DISPLAY_NAME)
                        val sizeCol = c.getColumnIndex(MediaStore.Downloads.SIZE)
                        val dateCol = c.getColumnIndex(MediaStore.Downloads.DATE_MODIFIED)
                        val relPathCol = c.getColumnIndex(MediaStore.Downloads.RELATIVE_PATH)

                        val seenFolders = mutableMapOf<String, ReceivedTransferInfo>()
                        var count = 0

                        while (c.moveToNext() && count < 60) {
                            val id = if (idCol >= 0) c.getLong(idCol) else continue
                            val name = if (nameCol >= 0) c.getString(nameCol) else continue
                            val size = if (sizeCol >= 0) c.getLong(sizeCol) else 0L
                            val dateSec = if (dateCol >= 0) c.getLong(dateCol) else 0L
                            val relPath = if (relPathCol >= 0) c.getString(relPathCol) ?: "" else ""

                            val subfolder = relPath.removePrefix("Download/").removePrefix("Download").trim('/')
                            val topFolder = subfolder.substringBefore('/')
                            if (topFolder.isNotBlank()) {
                                val existing = seenFolders[topFolder]
                                if (existing != null) {
                                    seenFolders[topFolder] = existing.copy(
                                        sizeBytes = existing.sizeBytes + size,
                                        itemCount = existing.itemCount + 1,
                                        lastModified = maxOf(existing.lastModified, dateSec * 1000L),
                                    )
                                } else {
                                    seenFolders[topFolder] = ReceivedTransferInfo(
                                        name = topFolder,
                                        isDirectory = true,
                                        sizeBytes = size,
                                        itemCount = 1,
                                        lastModified = dateSec * 1000L,
                                    )
                                }
                            } else {
                                val contentUri = ContentUris.withAppendedId(uri, id)
                                result.add(
                                    ReceivedTransferInfo(
                                        name = IncomingFile.sanitizeName(name),
                                        isDirectory = false,
                                        sizeBytes = size,
                                        lastModified = dateSec * 1000L,
                                        contentUri = contentUri,
                                    ),
                                )
                            }
                            count++
                        }
                        result.addAll(seenFolders.values)
                        result.sortByDescending { it.lastModified }
                    }
                } catch (_: Exception) {}
            }

            transfersList = result
        } catch (_: Exception) {
            transfersList = emptyList()
        }
    }

    fun openTransfer(item: ReceivedTransferInfo) {
        if (item.isDirectory) {
            try {
                val dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val folderFile = item.file ?: File(dlDir, item.name)
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    folderFile,
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "resource/folder")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                try {
                    val intent = Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(context, "Saved to Downloads/${item.name}", Toast.LENGTH_SHORT).show()
                }
            }
            return
        }

        try {
            val targetUri = item.contentUri ?: item.file?.let { file ->
                androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
            } ?: return

            val mime = context.contentResolver.getType(targetUri) ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(targetUri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(context, "Cannot open file", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        loadTransfers()
    }

    LaunchedEffect(activeTransfer) {
        loadTransfers()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        AppHeader(
            title = str("files.title"),
            tagline = "Transfers between PC and mobile",
            showLogo = false,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (activeTransfer != null) {
                Text(
                    text = "ACTIVE TRANSFER",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FW.SemiBold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(start = 2.dp),
                )
                ActiveTransferCard(
                    transfer = activeTransfer!!,
                    onCancel = {
                        IncomingFile.requestCancel(context)
                        Toast.makeText(context, "Transfer cancelled", Toast.LENGTH_SHORT).show()
                    },
                )
            }

            Text(
                text = "RECEIVED TRANSFERS",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FW.SemiBold,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(start = 2.dp),
            )

            if (transfersList.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pillowCard(),
                ) {
                    transfersList.forEachIndexed { index, item ->
                        TransferRow(
                            item = item,
                            onClick = { openTransfer(item) },
                        )
                        if (index < transfersList.size - 1) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            )
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            contentAlignment = Alignment.Center,
                        ) {
                            SolarDuotoneIcon(
                                icon = SolarIcons.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No transfers yet",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FW.SemiBold,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Files and folders transferred between your PC and mobile will appear here",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveTransferCard(
    transfer: TransferProgress,
    onCancel: () -> Unit,
) {
    val percent = if (transfer.totalBytes > 0) {
        ((transfer.bytesReceived * 100) / transfer.totalBytes).toInt().coerceIn(0, 100)
    } else 0
    val receivedStr = remember(transfer.bytesReceived) { formatFileSize(transfer.bytesReceived) }
    val totalStr = remember(transfer.totalBytes) { formatFileSize(transfer.totalBytes) }
    val speedStr = remember(transfer.speedBytesPerSec) {
        if (transfer.speedBytesPerSec > 0L) {
            val mb = transfer.speedBytesPerSec / (1024.0 * 1024.0)
            if (mb >= 1.0) {
                String.format(Locale.US, "%.1f MB/s", mb)
            } else {
                val kb = transfer.speedBytesPerSec / 1024.0
                String.format(Locale.US, "%.0f KB/s", kb)
            }
        } else ""
    }
    val progressFraction = if (transfer.totalBytes > 0) {
        (transfer.bytesReceived.toFloat() / transfer.totalBytes.toFloat()).coerceIn(0f, 1f)
    } else 0f

    val animatedProgress by animateFloatAsState(
        targetValue = progressFraction,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "activeTransferProgress",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pillowCard()
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                SolarDuotoneIcon(
                    icon = SolarIcons.FileDownload,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = transfer.currentName,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FW.SemiBold,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                val subtitle = if (transfer.totalFiles > 1) {
                    if (speedStr.isNotEmpty()) "File ${transfer.fileIndex} of ${transfer.totalFiles} • $speedStr"
                    else "File ${transfer.fileIndex} of ${transfer.totalFiles}"
                } else {
                    if (speedStr.isNotEmpty()) speedStr
                    else "Incoming transfer..."
                }
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = true),
                        onClick = onCancel,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                SolarDuotoneIcon(
                    icon = SolarIcons.Close,
                    contentDescription = "Cancel transfer",
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            if (animatedProgress > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = animatedProgress)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (speedStr.isNotEmpty()) "$receivedStr / $totalStr • $speedStr" else "$receivedStr / $totalStr",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                text = "$percent%",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FW.SemiBold,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun TransferRow(
    item: ReceivedTransferInfo,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val formattedDate = remember(item.lastModified) {
        if (item.lastModified > 0L) {
            try {
                SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(item.lastModified))
            } catch (_: Exception) { "" }
        } else ""
    }
    val formattedSize = remember(item.sizeBytes) {
        formatFileSize(item.sizeBytes)
    }

    val subtitle = remember(item, formattedSize, formattedDate) {
        buildString {
            if (item.isDirectory) {
                if (item.itemCount > 0) {
                    append("${item.itemCount} ${if (item.itemCount == 1) "file" else "files"}")
                    if (item.sizeBytes > 0) append(" • $formattedSize")
                } else {
                    append("Folder")
                }
            } else {
                append(formattedSize)
            }
            if (formattedDate.isNotBlank()) {
                append(" • $formattedDate")
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = true),
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            SolarDuotoneIcon(
                icon = if (item.isDirectory) SolarIcons.Folder else SolarIcons.FileDownload,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FW.Medium,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
    return String.format(Locale.US, "%.1f %s", value, units[digitGroups])
}
