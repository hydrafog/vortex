package com.vortex.a3.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.vortex.a3.ui.icons.SolarIcons
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vortex.a3.core.notes.Note
import com.vortex.a3.core.notes.NoteStore
import com.vortex.a3.ui.components.VortexDivider
import com.vortex.a3.ui.str
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(onBack: () -> Unit) {
    val items by NoteStore.notes.collectAsState()
    var editing by remember { mutableStateOf<Note?>(null) }
    var mode by remember { mutableStateOf("notes") }
    var newTodo by remember { mutableStateOf("") }

    BackHandler(enabled = editing != null) { editing = null }

    if (editing != null) {
        NoteEditor(
            note = editing!!,
            onClose = { editing = null },
            onDelete = { NoteStore.delete(editing!!.id); editing = null },
        )
        return
    }

    val kind = if (mode == "notes") "note" else "todo"
    val shown = items.filter { it.kind == kind }.sortedByDescending { it.updatedAt }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(SolarIcons.ArrowBack, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
            }
            Text(
                str("notes.title"),
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 4.dp).weight(1f),
            )
            if (mode == "notes") {
                IconButton(onClick = { editing = NoteStore.create("note") }) {
                    Icon(SolarIcons.Add, contentDescription = str("notes.new_note"), tint = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        VortexDivider()

        NotesSegment(mode) { mode = it }

        if (mode == "notes") {
            ListArea(
                empty = shown.isEmpty(),
                emptyText = str("notes.empty"),
                modifier = Modifier.weight(1f),
            ) {
                items(shown, key = { it.id }) { n ->
                    NoteRow(n, onOpen = { editing = n })
                    HorizontalDivider()
                }
            }
        } else {
            TodoProgress(done = shown.count { it.done }, total = shown.size)
            ListArea(
                empty = shown.isEmpty(),
                emptyText = str("notes.empty_todos"),
                modifier = Modifier.weight(1f),
            ) {
                items(shown, key = { it.id }) { n -> TodoRow(n, onOpen = { editing = n }) }
            }
            TodoAddBar(
                value = newTodo,
                onChange = { newTodo = it },
                onAdd = { NoteStore.addTodo(newTodo); newTodo = "" },
            )
        }
    }
}

@Composable
private fun NotesSegment(mode: String, onSelect: (String) -> Unit) {
    val tabs = listOf("notes" to str("notes.notes"), "todos" to str("notes.todos"))
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(4.dp),
    ) {
        val cellW = maxWidth / 2
        val indicator by animateDpAsState(if (mode == "todos") cellW else 0.dp, label = "seg")
        Box(
            Modifier
                .offset(x = indicator)
                .width(cellW)
                .fillMaxHeight()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
        )
        Row(Modifier.fillMaxSize()) {
            tabs.forEach { (key, label) ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onSelect(key) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = if (mode == key) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ListArea(
    empty: Boolean,
    emptyText: String,
    modifier: Modifier = Modifier,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    Box(modifier.fillMaxWidth()) {
        if (empty) {
            Text(
                emptyText,
                Modifier.align(Alignment.Center).padding(32.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(Modifier.fillMaxSize(), content = content)
        }
    }
}

@Composable
private fun NoteRow(n: Note, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            SolarIcons.StickyNote2,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f)) {
            Text(
                n.title.ifBlank { str("notes.untitled") },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (n.body.isNotBlank()) {
                Text(
                    n.body.replace("\n", " "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TodoRow(n: Note, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 14.dp, end = 6.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TodoCheck(done = n.done, onToggle = { NoteStore.toggle(n.id, it) })
        Column(Modifier.weight(1f)) {
            Text(
                n.title.ifBlank { str("notes.untitled") },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (n.done) TextDecoration.LineThrough else null,
                color = if (n.done) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
            )
            if (n.dueAt > 0L) {
                Text(
                    formatDue(n.dueAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (n.dueAt < System.currentTimeMillis() && !n.done)
                        MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        IconButton(onClick = { NoteStore.delete(n.id) }) {
            Icon(
                SolarIcons.Close,
                contentDescription = str("notes.delete"),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun TodoCheck(done: Boolean, onToggle: (Boolean) -> Unit) {
    Box(
        Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(if (done) MaterialTheme.colorScheme.primary else Color.Transparent)
            .border(
                2.dp,
                if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                CircleShape,
            )
            .clickable { onToggle(!done) },
        contentAlignment = Alignment.Center,
    ) {
        if (done) {
            Icon(
                SolarIcons.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

@Composable
private fun TodoProgress(done: Int, total: Int) {
    val pct = if (total > 0) done.toFloat() / total else 0f
    val sweep by animateFloatAsState(pct * 360f, label = "ring")
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val s = 11.dp.toPx()
                val d = size.minDimension - s
                val tl = Offset((size.width - d) / 2f, (size.height - d) / 2f)
                drawArc(track, -90f, 360f, false, tl, Size(d, d), style = Stroke(s))
                drawArc(primary, -90f, sweep, false, tl, Size(d, d), style = Stroke(s, cap = StrokeCap.Round))
            }
            Text(
                "${(pct * 100).roundToInt()}%",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            str("notes.todos_done", done, total),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            str("notes.tap_complete"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TodoAddBar(value: String, onChange: (String) -> Unit, onAdd: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(percent = 50))
            .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(
                    str("notes.add_todo"),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onAdd() }),
            )
        }
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable { onAdd() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                SolarIcons.Add,
                contentDescription = str("notes.add_todo"),
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun NoteEditor(note: Note, onClose: () -> Unit, onDelete: () -> Unit) {
    var title by remember(note.id) { mutableStateOf(note.title) }
    var body by remember(note.id) { mutableStateOf(note.body) }
    var dueAt by remember(note.id) { mutableStateOf(note.dueAt) }

    androidx.compose.runtime.LaunchedEffect(title, body, dueAt) {
        delay(400)
        NoteStore.upsert(note.copy(title = title, body = body, dueAt = dueAt))
    }
    BackHandler { onClose() }
    val ctx = androidx.compose.ui.platform.LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(SolarIcons.ArrowBack, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(modifier = Modifier.weight(1f))
            if (note.kind == "todo") {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .combinedClickable(
                            onClick = { pickDueDateTime(ctx, dueAt) { dueAt = it } },
                            onLongClick = { dueAt = 0L },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        SolarIcons.Notifications,
                        contentDescription = str("notes.add_reminder"),
                        tint = if (dueAt > 0L) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(SolarIcons.Delete, contentDescription = str("notes.delete"), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        VortexDivider()

        Column(Modifier.fillMaxSize()) {
            TextField(
                value = title,
                onValueChange = { title = it },
                placeholder = {
                    Text(if (note.kind == "todo") str("notes.todo_placeholder") else str("notes.title_placeholder"))
                },
                textStyle = MaterialTheme.typography.titleLarge,
                modifier = Modifier.fillMaxWidth(),
                colors = transparentFieldColors(),
                singleLine = note.kind == "todo",
            )
            TextField(
                value = body,
                onValueChange = { body = it },
                placeholder = { Text(str("notes.body_placeholder")) },
                modifier = Modifier.fillMaxSize(),
                colors = transparentFieldColors(),
            )
        }
    }
}

@Composable
private fun transparentFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
)

private fun pickDueDateTime(ctx: android.content.Context, initial: Long, onSet: (Long) -> Unit) {
    val cal = java.util.Calendar.getInstance().apply { if (initial > 0L) timeInMillis = initial }
    android.app.DatePickerDialog(
        ctx,
        { _, y, m, d ->
            android.app.TimePickerDialog(
                ctx,
                { _, h, min ->
                    cal.set(y, m, d, h, min, 0)
                    cal.set(java.util.Calendar.MILLISECOND, 0)
                    onSet(cal.timeInMillis)
                },
                cal.get(java.util.Calendar.HOUR_OF_DAY),
                cal.get(java.util.Calendar.MINUTE),
                true,
            ).show()
        },
        cal.get(java.util.Calendar.YEAR),
        cal.get(java.util.Calendar.MONTH),
        cal.get(java.util.Calendar.DAY_OF_MONTH),
    ).show()
}

private fun formatDue(ms: Long): String =
    java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(ms))
