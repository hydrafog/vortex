package com.vortex.a3.ui.screens

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.vortex.a3.ui.components.AppHeader
import com.vortex.a3.ui.components.CardCorner
import com.vortex.a3.ui.components.VortexDivider
import com.vortex.a3.ui.icons.SolarIcons
import com.vortex.a3.ui.str
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ReceivedFileInfo(
    val name: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val file: File,
)

@Composable
fun FilesScreen(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var filesList by remember { mutableStateOf<List<ReceivedFileInfo>>(emptyList()) }

    LaunchedEffect(Unit) {
        try {
            val dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (dlDir != null && dlDir.exists() && dlDir.isDirectory) {
                val found = dlDir.listFiles()
                    ?.filter { it.isFile && !it.name.startsWith(".") }
                    ?.sortedByDescending { it.lastModified() }
                    ?.take(20)
                    ?.map {
                        ReceivedFileInfo(
                            name = IncomingFile.sanitizeName(it.name),
                            sizeBytes = it.length(),
                            lastModified = it.lastModified(),
                            file = it,
                        )
                    } ?: emptyList()
                filesList = found
            }
        } catch (_: Exception) {
            filesList = emptyList()
        }
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

        if (filesList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = SolarIcons.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = "No transfers yet",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FW.SemiBold,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Files transferred between your PC and mobile will appear here",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "RECEIVED FILES",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FW.SemiBold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(start = 2.dp),
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(CardCorner)
                        .background(MaterialTheme.colorScheme.surface),
                ) {
                    filesList.forEachIndexed { index, item ->
                        FileRow(
                            item = item,
                            onClick = {
                                try {
                                    val uri = androidx.core.content.FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        item.file,
                                    )
                                    val mime = context.contentResolver.getType(uri) ?: "*/*"
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, mime)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                    try {
                                        val fallback = Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(android.net.Uri.fromFile(item.file), "*/*")
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        context.startActivity(fallback)
                                    } catch (_: Exception) {}
                                }
                            },
                        )
                        if (index < filesList.size - 1) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileRow(
    item: ReceivedFileInfo,
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
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = SolarIcons.FileDownload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
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
                text = if (formattedDate.isNotBlank()) "$formattedSize • $formattedDate" else formattedSize,
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
