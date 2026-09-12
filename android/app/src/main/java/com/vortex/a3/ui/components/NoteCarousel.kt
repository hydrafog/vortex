package com.vortex.a3.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight as FW
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.vortex.a3.core.notes.Note
import com.vortex.a3.core.notes.NoteStore
import com.vortex.a3.ui.icons.SolarDuotoneIcon
import com.vortex.a3.ui.icons.SolarIcons
import com.vortex.a3.ui.str
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoteCarousel(
    onOpenNote: (Note) -> Unit,
    onAddNote: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val notes by NoteStore.notes.collectAsState()
    var deletingNote by remember { mutableStateOf<Note?>(null) }

    val squareSize = 132.dp

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(notes, key = { it.id }) { note ->
            NoteSquare(
                note = note,
                size = squareSize,
                onClick = { onOpenNote(note) },
                onLongClick = { deletingNote = note },
            )
        }

        item(key = "add_note") {
            AddNoteSquare(
                size = squareSize,
                onClick = onAddNote,
            )
        }
    }

    if (deletingNote != null) {
        val target = deletingNote!!
        AlertDialog(
            onDismissRequest = { deletingNote = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(str("notes.delete"), color = MaterialTheme.colorScheme.onSurface, fontWeight = FW.SemiBold) },
            text = {
                Text(
                    str("notes.delete_body"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        deletingNote = null
                        NoteStore.delete(target.id)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) { Text(str("notes.delete")) }
            },
            dismissButton = {
                TextButton(onClick = { deletingNote = null }) {
                    Text(str("switch.cancel"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
    }
}

@Composable
private fun AddNoteSquare(
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(size)
            .pillowCard()
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = true),
                onClick = onClick,
            )
            .padding(14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                SolarDuotoneIcon(
                    icon = SolarIcons.Add,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = str("notes.new_note"),
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FW.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteSquare(
    note: Note,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val formattedDate = remember(note.updatedAt) {
        if (note.updatedAt > 0L) {
            try {
                SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(note.updatedAt))
            } catch (_: Exception) { null }
        } else null
    }

    Box(
        modifier = Modifier
            .size(size)
            .pillowCard()
            .combinedClickable(
                interactionSource = interaction,
                indication = ripple(bounded = true),
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(14.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = note.title.ifBlank { str("notes.untitled") },
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FW.Medium,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (note.body.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = note.body.replace("\n", " "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            if (formattedDate != null) {
                Text(
                    text = formattedDate,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
