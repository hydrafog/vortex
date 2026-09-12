package com.vortex.a3.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight as FW
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vortex.a3.core.calendar.CalendarEvent
import com.vortex.a3.core.calendar.CalendarProvider
import com.vortex.a3.core.notes.Note
import com.vortex.a3.core.notes.NoteStore
import com.vortex.a3.ui.icons.SolarDuotoneIcon
import com.vortex.a3.ui.icons.SolarIcons
import com.vortex.a3.ui.str
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

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
    var baseDate by remember { mutableStateOf(selectedDate) }
    LaunchedEffect(selectedDate) {
        if (baseDate != selectedDate) {
            baseDate = selectedDate
        }
    }

    val dayEvents = remember(events, selected) {
        provider.forDate(selected)
    }
    val dueNotes = remember(notes, selected) {
        notes.filter { it.dueAt > 0L && dueDayKey(it.dueAt, zone) == selected && !it.deleted }
    }

    var showAddDialog by remember { mutableStateOf(false) }

    val animatable = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()
    var isDragging by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .pillowCard()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val totalWidthPx = constraints.maxWidth.toFloat()
            val cellSpacingPx = (totalWidthPx / 7f).coerceAtLeast(1f)
            val activeOffsetDays = (-animatable.value / cellSpacingPx).roundToInt()
            val displayedDate = remember(baseDate, activeOffsetDays) {
                baseDate.plusDays(activeOffsetDays.toLong())
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = displayedDate.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + displayedDate.year,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FW.SemiBold,
                )
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable { showAddDialog = true }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SolarDuotoneIcon(
                        icon = SolarIcons.Add,
                        contentDescription = str("calendar.add"),
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = str("calendar.add"),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FW.SemiBold,
                    )
                }
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp),
        ) {
            val totalWidthPx = constraints.maxWidth.toFloat()
            val cellSpacingPx = (totalWidthPx / 7f).coerceAtLeast(1f)
            val centerX = totalWidthPx / 2f
            val currentDragPx = animatable.value

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .draggable(
                        state = rememberDraggableState { delta ->
                            coroutineScope.launch {
                                animatable.snapTo(animatable.value + delta)
                            }
                        },
                        orientation = Orientation.Horizontal,
                        onDragStarted = { isDragging = true },
                        onDragStopped = { velocity ->
                            isDragging = false
                            val currentOffset = animatable.value
                            val projected = currentOffset + (velocity * 0.12f).coerceIn(-cellSpacingPx * 2.5f, cellSpacingPx * 2.5f)
                            val daysMoved = (-projected / cellSpacingPx).roundToInt().coerceIn(-4, 4)
                            val targetOffset = -daysMoved * cellSpacingPx

                            coroutineScope.launch {
                                animatable.animateTo(
                                    targetValue = targetOffset,
                                    initialVelocity = velocity,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioLowBouncy,
                                        stiffness = Spring.StiffnessMediumLow,
                                    ),
                                )
                                if (daysMoved != 0) {
                                    val targetDate = baseDate.plusDays(daysMoved.toLong())
                                    baseDate = targetDate
                                    animatable.snapTo(0f)
                                    onSelect(targetDate.toString())
                                } else {
                                    animatable.snapTo(0f)
                                }
                            }
                        },
                    ),
            ) {
                for (i in -5..5) {
                    val dayDate = remember(baseDate, i) { baseDate.plusDays(i.toLong()) }
                    val centerPosPx = centerX + i * cellSpacingPx + currentDragPx
                    val normDist = (centerPosPx - centerX) / cellSpacingPx
                    val absDist = kotlin.math.abs(normDist)

                    if (absDist <= 4.2f) {
                        val scale = (1f - (absDist / 3.2f)).coerceIn(0f, 1f)
                        val cardWidthDp = (32f + 18f * scale).dp
                        val cardHeightDp = (44f + 28f * scale).dp

                        val curveFactor = kotlin.math.max(0f, 1f - (absDist / 3.0f) * (absDist / 3.0f))
                        val dipDp = (14f * curveFactor).dp
                        val topYDp = 4.dp + dipDp

                        val isEmphasized = absDist < 0.5f
                        val hasAny = remember(events, dayDate) {
                            provider.forDate(dayDate.toString()).isNotEmpty()
                        }

                        CalendarDayCard(
                            day = dayDate,
                            isEmphasized = isEmphasized,
                            hasAny = hasAny,
                            scale = scale,
                            modifier = Modifier
                                .width(cardWidthDp)
                                .height(cardHeightDp)
                                .offset {
                                    val cardWidthPx = cardWidthDp.toPx()
                                    IntOffset(
                                        x = (centerPosPx - cardWidthPx / 2f).roundToInt(),
                                        y = topYDp.roundToPx(),
                                    )
                                }
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {
                                    if (!isDragging && i != 0) {
                                        coroutineScope.launch {
                                            animatable.animateTo(
                                                targetValue = -i * cellSpacingPx,
                                                animationSpec = spring(
                                                    dampingRatio = Spring.DampingRatioLowBouncy,
                                                    stiffness = Spring.StiffnessMediumLow,
                                                ),
                                            )
                                            val targetDate = dayDate
                                            baseDate = targetDate
                                            animatable.snapTo(0f)
                                            onSelect(targetDate.toString())
                                        }
                                    }
                                },
                        )
                    }
                }
            }
        }

        AnimatedContent(
            targetState = selected,
            transitionSpec = {
                fadeIn(animationSpec = tween(180, easing = FastOutSlowInEasing)) togetherWith
                    fadeOut(animationSpec = tween(180, easing = FastOutSlowInEasing))
            },
            label = "calendarEventsTransition",
        ) { _ ->
            if (dayEvents.isEmpty() && dueNotes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = str("calendar.empty"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
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

    if (showAddDialog) {
        var eventTitle by remember { mutableStateOf("") }
        var eventTime by remember { mutableStateOf("") }
        var titleError by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    text = str("calendar.add") + " ($selected)",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FW.SemiBold,
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = eventTitle,
                        onValueChange = {
                            eventTitle = it
                            if (it.isNotBlank()) titleError = false
                        },
                        label = { Text(str("calendar.event_title")) },
                        isError = titleError,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = eventTime,
                        onValueChange = { eventTime = it },
                        label = { Text(str("calendar.time_hint")) },
                        placeholder = { Text("14:30") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (eventTitle.isBlank()) {
                            titleError = true
                            return@Button
                        }
                        val cleanTime = CalendarEvent.cleanTime(eventTime)
                        provider.add(date = selected, time = cleanTime, text = eventTitle.trim())
                        showAddDialog = false
                        onAddEvent()
                    },
                ) {
                    Text(str("calendar.save"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text(str("calendar.cancel"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
    }
}

@Composable
private fun CalendarDayCard(
    day: LocalDate,
    isEmphasized: Boolean,
    hasAny: Boolean,
    scale: Float,
    modifier: Modifier = Modifier,
) {
    val cornerRadius = (10f + 6f * scale).dp
    val dayFontSize = (12f + 8f * scale).sp
    val weekFontSize = (9f + 3f * scale).sp

    val bgColor by animateColorAsState(
        targetValue = if (isEmphasized) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceContainerLow,
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "calDayBg",
    )
    val textDayColor by animateColorAsState(
        targetValue = if (isEmphasized) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurface,
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "calDayText",
    )
    val textWeekColor by animateColorAsState(
        targetValue = if (isEmphasized) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f)
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "calWeekText",
    )
    val dotColor by animateColorAsState(
        targetValue = if (isEmphasized) MaterialTheme.colorScheme.onPrimary
        else if (hasAny) MaterialTheme.colorScheme.primary
        else Color.Transparent,
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "calDotColor",
    )

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(bgColor)
            .semantics { contentDescription = "Day $day" }
            .padding(vertical = (4f + 3f * scale).dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
            color = textWeekColor,
            fontSize = weekFontSize,
            fontWeight = if (isEmphasized) FW.Bold else FW.Normal,
            maxLines = 1,
        )
        Text(
            text = day.dayOfMonth.toString(),
            color = textDayColor,
            fontSize = dayFontSize,
            fontWeight = if (isEmphasized) FW.Bold else if (scale > 0.5f) FW.SemiBold else FW.Normal,
            maxLines = 1,
        )
        Box(
            modifier = Modifier
                .size((3f + 1.5f * scale).dp)
                .clip(RoundedCornerShape(2.dp))
                .background(dotColor),
        )
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
