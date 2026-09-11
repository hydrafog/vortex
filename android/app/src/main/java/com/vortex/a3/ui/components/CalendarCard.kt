package com.vortex.a3.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight as FW
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vortex.a3.core.calendar.CalendarProvider
import com.vortex.a3.core.notes.Note
import com.vortex.a3.core.notes.NoteStore
import com.vortex.a3.ui.str
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

fun dueDayKey(dueAt: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    if (dueAt <= 0L) return ""
    return Instant.ofEpochMilli(dueAt).atZone(zone).toLocalDate().toString()
}

fun dayWindowStart(day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long {
    return day.atStartOfDay(zone).toInstant().toEpochMilli()
}

@Composable
fun CalendarCard(
    selected: String,
    onSelect: (String) -> Unit,
    provider: CalendarProvider,
    onOpenNote: (String) -> Unit,
    modifier: Modifier = Modifier,
    onAddEvent: () -> Unit = {},
) {
    val events by provider.events.collectAsState()
    val notes by NoteStore.notes.collectAsState()
    val zone = remember { ZoneId.systemDefault() }
    val selectedDate = remember(selected) {
        runCatching { LocalDate.parse(selected) }.getOrDefault(LocalDate.now(zone))
    }
    val week = remember(selectedDate) {
        (0 until 7).map { selectedDate.minusDays(3).plusDays(it.toLong()) }
    }
    val dayEvents = remember(events, selected) {
        provider.forDate(selected)
    }
    val dueNotes = remember(notes, selected) {
        notes.filter { it.dueAt > 0L && dueDayKey(it.dueAt, zone) == selected && !it.deleted }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardCorner)
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            week.forEach { day ->
                val key = day.toString()
                val isSelected = key == selected
                val hasAny = remember(events, key) {
                    provider.forDate(key).isNotEmpty()
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerLow,
                        )
                        .clickable(onClick = { onSelect(key) })
                        .semantics { contentDescription = "Day $key" }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FW.Medium,
                    )
                    Text(
                        text = day.dayOfMonth.toString(),
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isSelected) FW.SemiBold else FW.Medium,
                    )
                    Box(
                        modifier = Modifier
                            .size(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                if (hasAny) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceContainerLow,
                            ),
                    )
                }
            }
        }
        if (dayEvents.isEmpty() && dueNotes.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    text = str("calendar.empty"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = str("calendar.add"),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FW.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onAddEvent)
                        .semantics { contentDescription = "Add calendar event" }
                        .padding(vertical = 4.dp),
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                dayEvents.forEach { e ->
                    CalendarEventRow(
                        title = e.text.ifBlank { str("calendar.empty") },
                        meta = eventMeta(e),
                        overdue = false,
                    )
                }
                dueNotes.forEach { n ->
                    val overdue = n.dueAt < System.currentTimeMillis() && !n.done
                    CalendarEventRow(
                        title = n.title.ifBlank { str("notes.untitled") },
                        meta = str("calendar.due_note"),
                        overdue = overdue,
                        onClick = { onOpenNote(n.id) },
                    )
                }
            }
        }
    }
}

private fun eventMeta(e: com.vortex.a3.core.calendar.CalendarEvent): String {
    val timePart = when {
        e.time.isEmpty() -> "all day"
        e.endTime.isNotEmpty() -> e.time + "-" + e.endTime
        else -> e.time
    }
    val recurPart = when (e.recur) {
        "year" -> "every year"
        "month" -> "every month"
        else -> ""
    }
    return if (recurPart.isEmpty()) timePart else "$recurPart · $timePart"
}

@Composable
private fun CalendarEventRow(
    title: String,
    meta: String,
    overdue: Boolean,
    onClick: (() -> Unit)? = null,
) {
    val base = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerLow)
        .padding(horizontal = 12.dp, vertical = 10.dp)
    val withClick = if (onClick != null) base.clickable(onClick = onClick) else base
    Column(modifier = withClick, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FW.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = meta,
            color = if (overdue) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
fun rememberCalendarSelection(): Pair<String, (String) -> Unit> {
    val zone = remember { ZoneId.systemDefault() }
    val initial = remember { LocalDate.now(zone).toString() }
    val state = androidx.compose.runtime.mutableStateOf(initial)
    return state.value to { state.value = it }
}

fun Note.isDueOn(day: String, zone: ZoneId = ZoneId.systemDefault()): Boolean =
    dueAt > 0L && dueDayKey(dueAt, zone) == day

@Composable
fun Spacer12() {
    Spacer(modifier = Modifier.height(12.dp))
}
