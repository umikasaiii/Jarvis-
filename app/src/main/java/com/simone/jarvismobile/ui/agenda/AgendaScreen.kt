package com.simone.jarvismobile.ui.agenda

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Subject
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.SubdirectoryArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simone.jarvismobile.core.agenda.Agenda
import com.simone.jarvismobile.core.agenda.AgendaEntry
import java.time.LocalDate

private val Cyan = Color(0xFF3FD8F0)
private val Ink = Color(0xFFE3EFF5)
private val Muted = Color(0xFF7C8B95)
private val Gold = Color(0xFFF3C34C)
private val Today = Color(0xFF4FE3C1)
private val Tomorrow = Color(0xFF2C5BFF)
private val Later = Color(0xFF6B7C87)
private val CardBg = Color(0x660A1826)

private fun dotFor(date: LocalDate?, today: LocalDate): Color = when {
    date == null -> Later
    date == today -> Today
    date == today.plusDays(1) -> Tomorrow
    date.isBefore(today) -> Gold // overdue
    else -> Later
}

/**
 * The unified "Attività" section, in the shape of Google Tasks: list tabs across
 * the top, a quick add row, tasks you can star, complete, give a due date and
 * sub-tasks, and a collapsible "Completate" archive. All of it is the real
 * `Agenda.md`, so appointments dictated by voice and tasks typed here are one
 * list.
 */
@Composable
fun AgendaScreen(viewModel: AgendaViewModel = hiltViewModel()) {
    val lists by viewModel.lists.collectAsStateWithLifecycle()
    val selected by viewModel.selectedList.collectAsStateWithLifecycle()
    val view by viewModel.visible.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val today = LocalDate.now()

    var newTask by remember { mutableStateOf("") }
    // Show the completed archive expanded by default — it was collapsed, which
    // made finished tasks look like they had vanished.
    var completedOpen by remember { mutableStateOf(true) }
    var editing by remember { mutableStateOf<AgendaEntry?>(null) }
    var subtaskParent by remember { mutableStateOf<AgendaEntry?>(null) }
    var newListDialog by remember { mutableStateOf(false) }
    var datePickerFor by remember { mutableStateOf<AgendaEntry?>(null) }

    // Inline date/time/star for the task being typed, Google-Tasks style: chosen
    // before saving and cleared once the task is added.
    var newTaskDate by remember { mutableStateOf<LocalDate?>(null) }
    var newTaskTime by remember { mutableStateOf<java.time.LocalTime?>(null) }
    var newTaskStarred by remember { mutableStateOf(false) }
    var newTaskDatePicker by remember { mutableStateOf(false) }
    var newTaskTimePicker by remember { mutableStateOf(false) }
    fun resetNewTask() {
        newTask = ""; newTaskDate = null; newTaskTime = null; newTaskStarred = false
    }

    // The selected list may be one just created that has no tasks yet, so it
    // isn't in the derived list; show it anyway so the tab stays visible.
    val tabs = if (selected in lists) lists else lists + selected

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(Color(0xFF050C16), Color(0xFF081420), Color(0xFF03080E))),
            )
            // Lift the whole screen above the keyboard so the bottom add-field
            // (and what you type in it) stays visible while the IME is open.
            .imePadding()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.size(16.dp))
        Text("Attività", style = MaterialTheme.typography.headlineSmall, color = Cyan)

        // --- List tabs ------------------------------------------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tabs.forEach { list ->
                val active = list == selected
                Text(
                    text = list,
                    color = if (active) Color(0xFF04121A) else Ink,
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (active) Cyan else Color(0x330A1826))
                        .clickable { viewModel.selectList(list) }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
            Text(
                text = "＋",
                color = Cyan,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0x330A1826))
                    .clickable { newListDialog = true }
                    .padding(horizontal = 14.dp, vertical = 5.dp),
            )
        }

        if (message.isNotBlank()) {
            Text(message, color = Gold, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
        }

        if (view.open.isEmpty() && view.done.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    if (selected == viewModel.starredTab) {
                        "Nessuna attività speciale.\nTocca la stella su un'attività."
                    } else {
                        "Nessuna attività.\nScrivine una qui sotto o dì «ricordami di …»."
                    },
                    color = Muted,
                    fontSize = 14.sp,
                )
            }
        } else {
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Open tasks with their sub-tasks.
            view.open.forEach { (task, children) ->
                item(key = task.id) {
                    TaskRow(
                        entry = task,
                        today = today,
                        lists = lists.filterNot { it == viewModel.starredTab },
                        onToggle = { viewModel.toggle(task) },
                        onStar = { viewModel.toggleStar(task) },
                        onOpen = { editing = task },
                        onAddSubtask = { subtaskParent = task },
                        onDue = { viewModel.setDue(task, it) },
                        onPickDate = { datePickerFor = task },
                        onMove = { viewModel.moveTo(task, it) },
                        onDelete = { viewModel.delete(task) },
                    )
                }
                items(children, key = { "c-${it.id}" }) { child ->
                    SubtaskRow(
                        entry = child,
                        today = today,
                        onToggle = { viewModel.toggle(child) },
                        onDelete = { viewModel.delete(child) },
                    )
                }
                if (subtaskParent?.id == task.id) {
                    item(key = "sub-input-${task.id}") {
                        SubtaskInput(
                            onAdd = { viewModel.addSubtask(task, it); subtaskParent = null },
                            onCancel = { subtaskParent = null },
                        )
                    }
                }
            }

            // Completed archive.
            if (view.done.isNotEmpty()) {
                item(key = "completed-header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { completedOpen = !completedOpen }
                            .padding(top = 14.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (completedOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = null,
                            tint = Muted,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Completate (${view.done.size})",
                            color = Muted,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                if (completedOpen) {
                    items(view.done, key = { "d-${it.id}" }) { entry ->
                        CompletedRow(
                            entry = entry,
                            today = today,
                            onToggle = { viewModel.toggle(entry) },
                            onDelete = { viewModel.delete(entry) },
                        )
                    }
                }
            }

            item("tail") { Spacer(Modifier.size(24.dp)) }
        }
        }

        // --- Quick add at the BOTTOM (Google-Tasks style) -------------------
        if (selected != viewModel.starredTab) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                // Toolbar: set date, set time, star — mirrors Google Tasks.
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(onClick = { newTaskDatePicker = true }) {
                        Icon(
                            Icons.Filled.CalendarMonth,
                            contentDescription = "Data",
                            tint = if (newTaskDate != null) Cyan else Muted,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(onClick = { newTaskTimePicker = true }) {
                        Icon(
                            Icons.Filled.Schedule,
                            contentDescription = "Ora",
                            tint = if (newTaskTime != null) Cyan else Muted,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(onClick = { newTaskStarred = !newTaskStarred }) {
                        Icon(
                            if (newTaskStarred) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = "Speciale",
                            tint = if (newTaskStarred) Gold else Muted,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    val label = buildString {
                        newTaskDate?.let { append(Agenda.fullDate(it, today)) }
                        newTaskTime?.let {
                            if (isNotEmpty()) append(" · ")
                            append(Agenda.humanTime(it))
                        }
                    }
                    if (label.isNotBlank()) Text(label, color = Cyan, fontSize = 12.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newTask,
                        onValueChange = { newTask = it; viewModel.clearMessage() },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Nuova attività in “$selected”") },
                        singleLine = true,
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            viewModel.addTask(newTask, newTaskDate, newTaskTime, newTaskStarred)
                            resetNewTask()
                        },
                        enabled = newTask.isNotBlank(),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Aggiungi", tint = Cyan)
                    }
                }
            }
        }
    }

    editing?.let { entry ->
        TaskDetailDialog(
            entry = entry,
            onDismiss = { editing = null },
            onSave = { title, notes, date, time, starred ->
                viewModel.saveDetails(entry, title, notes, date, time, starred)
                editing = null
            },
            onComplete = { viewModel.toggle(entry); editing = null },
            onDelete = { viewModel.delete(entry); editing = null },
        )
    }

    if (newListDialog) {
        NewListDialog(
            onDismiss = { newListDialog = false },
            onCreate = { name ->
                viewModel.selectList(name)
                newListDialog = false
            },
        )
    }

    datePickerFor?.let { entry ->
        TaskDatePicker(
            initial = entry.date,
            onDismiss = { datePickerFor = null },
            onPick = { date ->
                viewModel.setDue(entry, date)
                datePickerFor = null
            },
        )
    }

    // Pickers for the task being typed in the quick-add row.
    if (newTaskDatePicker) {
        TaskDatePicker(
            initial = newTaskDate,
            onDismiss = { newTaskDatePicker = false },
            onPick = { date -> newTaskDate = date; newTaskDatePicker = false },
        )
    }
    if (newTaskTimePicker) {
        TaskTimePicker(
            initial = newTaskTime,
            onDismiss = { newTaskTimePicker = false },
            onPick = { time -> newTaskTime = time; newTaskTimePicker = false },
        )
    }
}

/**
 * Numeric hour/minute fields, not Material3's analog TimePicker dial or its
 * TimeInput: the dial's hour-to-minute step is an animated crossfade of the
 * whole clock face, which felt slow and laggy on-device, and even TimeInput's
 * two fields left the user tapping over to "Minuto" by hand. This auto-
 * advances focus to the minute field the moment two digits are typed into the
 * hour field, so entering a time is two short digit-runs with no taps between
 * them.
 */
@Composable
private fun TaskTimePicker(
    initial: java.time.LocalTime?,
    onDismiss: () -> Unit,
    onPick: (java.time.LocalTime) -> Unit,
) {
    val base = initial ?: java.time.LocalTime.of(9, 0)
    var hourValue by remember { mutableStateOf(TextFieldValue("%02d".format(base.hour))) }
    var minuteValue by remember { mutableStateOf(TextFieldValue("%02d".format(base.minute))) }
    val minuteFocus = remember { FocusRequester() }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val hour = hourValue.text.toIntOrNull()?.coerceIn(0, 23) ?: base.hour
                val minute = minuteValue.text.toIntOrNull()?.coerceIn(0, 59) ?: base.minute
                onPick(java.time.LocalTime.of(hour, minute))
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = hourValue,
                    onValueChange = { new ->
                        val digits = new.text.filter { it.isDigit() }.take(2)
                        hourValue = TextFieldValue(digits, selection = TextRange(digits.length))
                        if (digits.length == 2) minuteFocus.requestFocus()
                    },
                    label = { Text("Ora") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(72.dp)
                        // The field starts pre-filled with the current hour, so
                        // select it all on focus — the first digit typed then
                        // replaces it outright instead of inserting into it.
                        .onFocusChanged {
                            if (it.isFocused) {
                                hourValue = hourValue.copy(selection = TextRange(0, hourValue.text.length))
                            }
                        },
                )
                Text(":")
                OutlinedTextField(
                    value = minuteValue,
                    onValueChange = { new ->
                        val digits = new.text.filter { it.isDigit() }.take(2)
                        minuteValue = TextFieldValue(digits, selection = TextRange(digits.length))
                    },
                    label = { Text("Minuto") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(72.dp)
                        .focusRequester(minuteFocus)
                        .onFocusChanged {
                            if (it.isFocused) {
                                minuteValue = minuteValue.copy(selection = TextRange(0, minuteValue.text.length))
                            }
                        },
                )
            }
        },
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TaskDatePicker(
    initial: LocalDate?,
    onDismiss: () -> Unit,
    onPick: (LocalDate) -> Unit,
) {
    val startMillis = (initial ?: LocalDate.now())
        .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
    val state = androidx.compose.material3.rememberDatePickerState(initialSelectedDateMillis = startMillis)
    androidx.compose.material3.DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { millis ->
                    val picked = java.time.Instant.ofEpochMilli(millis)
                        .atZone(java.time.ZoneOffset.UTC).toLocalDate()
                    onPick(picked)
                }
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    ) {
        androidx.compose.material3.DatePicker(state = state)
    }
}

@Composable
private fun NewListDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nuova lista") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nome") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onCreate(name.trim()) }) { Text("Crea") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

@Composable
private fun TaskRow(
    entry: AgendaEntry,
    today: LocalDate,
    lists: List<String>,
    onToggle: () -> Unit,
    onStar: () -> Unit,
    onOpen: () -> Unit,
    onAddSubtask: () -> Unit,
    onDue: (LocalDate?) -> Unit,
    onPickDate: () -> Unit,
    onMove: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val dot = dotFor(entry.date, today)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardBg)
            .padding(start = 8.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompleteDot(done = entry.done, color = dot, onToggle = onToggle)
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f).clickable(onClick = onOpen)) {
            Text(entry.text, color = Ink, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (entry.notes.isNotBlank()) {
                Text(entry.notes, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (entry.date != null) {
                Text(
                    Agenda.fullDate(entry.date, today).replaceFirstChar { it.uppercase() } +
                        (entry.time?.let { " · " + Agenda.humanTime(it) } ?: ""),
                    color = dot,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        IconButton(onClick = onStar, modifier = Modifier.size(36.dp)) {
            Icon(
                if (entry.starred) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = "Speciale",
                tint = if (entry.starred) Gold else Muted,
                modifier = Modifier.size(19.dp),
            )
        }
        TaskMenu(
            lists = lists,
            onAddSubtask = onAddSubtask,
            onDue = onDue,
            onPickDate = onPickDate,
            onMove = onMove,
            onDelete = onDelete,
        )
    }
}

@Composable
private fun CompleteDot(done: Boolean, color: Color, onToggle: () -> Unit) {
    Box(
        modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        if (done) {
            Box(Modifier.size(18.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Check, contentDescription = "Fatto", tint = Color(0xFF04121A), modifier = Modifier.size(12.dp))
            }
        } else {
            Box(Modifier.size(18.dp).clip(CircleShape).border(2.dp, color, CircleShape))
        }
    }
}

@Composable
private fun TaskMenu(
    lists: List<String>,
    onAddSubtask: () -> Unit,
    onDue: (LocalDate?) -> Unit,
    onPickDate: () -> Unit,
    onMove: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var dueOpen by remember { mutableStateOf(false) }
    var moveOpen by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Altro", tint = Muted, modifier = Modifier.size(19.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Aggiungi sottoattività") },
                leadingIcon = { Icon(Icons.Filled.SubdirectoryArrowRight, null) },
                onClick = { open = false; onAddSubtask() },
            )
            DropdownMenuItem(
                text = { Text("Scadenza") },
                leadingIcon = { Icon(Icons.Filled.CalendarMonth, null) },
                onClick = { open = false; dueOpen = true },
            )
            DropdownMenuItem(
                text = { Text("Sposta in…") },
                leadingIcon = { Icon(Icons.Filled.FolderOpen, null) },
                onClick = { open = false; moveOpen = true },
            )
            DropdownMenuItem(
                text = { Text("Elimina") },
                leadingIcon = { Icon(Icons.Filled.Delete, null) },
                onClick = { open = false; onDelete() },
            )
        }
        DropdownMenu(expanded = dueOpen, onDismissRequest = { dueOpen = false }) {
            DropdownMenuItem(text = { Text("Oggi") }, onClick = { dueOpen = false; onDue(today) })
            DropdownMenuItem(text = { Text("Domani") }, onClick = { dueOpen = false; onDue(today.plusDays(1)) })
            DropdownMenuItem(text = { Text("Prossima settimana") }, onClick = { dueOpen = false; onDue(today.plusWeeks(1)) })
            DropdownMenuItem(text = { Text("Scegli data…") }, onClick = { dueOpen = false; onPickDate() })
            DropdownMenuItem(text = { Text("Nessuna data") }, onClick = { dueOpen = false; onDue(null) })
        }
        DropdownMenu(expanded = moveOpen, onDismissRequest = { moveOpen = false }) {
            lists.forEach { list ->
                DropdownMenuItem(text = { Text(list) }, onClick = { moveOpen = false; onMove(list) })
            }
        }
    }
}

@Composable
private fun SubtaskRow(
    entry: AgendaEntry,
    today: LocalDate,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 28.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x440A1826))
            .padding(start = 6.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.SubdirectoryArrowRight, null, tint = Muted, modifier = Modifier.size(14.dp))
        CompleteDot(done = entry.done, color = dotFor(entry.date, today), onToggle = onToggle)
        Text(
            entry.text,
            color = if (entry.done) Muted else Ink,
            fontSize = 14.sp,
            textDecoration = if (entry.done) TextDecoration.LineThrough else null,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Delete, contentDescription = "Elimina", tint = Muted, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun CompletedRow(
    entry: AgendaEntry,
    today: LocalDate,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x330A1826))
            .padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompleteDot(done = true, color = Later, onToggle = onToggle)
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.text,
                color = Muted,
                fontSize = 14.sp,
                textDecoration = TextDecoration.LineThrough,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            entry.completedAt?.let {
                Text(
                    "Completata: " + Agenda.humanDate(it, today),
                    color = Muted,
                    fontSize = 10.sp,
                )
            }
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Delete, contentDescription = "Elimina", tint = Muted, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun SubtaskInput(onAdd: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 28.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Sottoattività") },
            singleLine = true,
        )
        IconButton(onClick = { if (text.isNotBlank()) onAdd(text) }, enabled = text.isNotBlank()) {
            Icon(Icons.Filled.Add, contentDescription = "Aggiungi", tint = Cyan)
        }
        IconButton(onClick = onCancel) {
            Icon(Icons.Filled.ExpandLess, contentDescription = "Chiudi", tint = Muted)
        }
    }
}

/**
 * A Google-Tasks-style editor for one task: title, details, a due date and an
 * optional time, a star, and a "complete" action. Everything is applied on Save.
 */
@Composable
private fun TaskDetailDialog(
    entry: AgendaEntry,
    onDismiss: () -> Unit,
    onSave: (title: String, notes: String, date: LocalDate?, time: java.time.LocalTime?, starred: Boolean) -> Unit,
    onComplete: () -> Unit,
    onDelete: () -> Unit,
) {
    val today = LocalDate.now()
    var title by remember { mutableStateOf(entry.text) }
    var notes by remember { mutableStateOf(entry.notes) }
    var date by remember { mutableStateOf(entry.date) }
    var time by remember { mutableStateOf(entry.time) }
    var starred by remember { mutableStateOf(entry.starred) }
    var datePicker by remember { mutableStateOf(false) }
    var timePicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Modifica attività", modifier = Modifier.weight(1f))
                IconButton(onClick = { starred = !starred }) {
                    Icon(
                        if (starred) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = "Speciale",
                        tint = if (starred) Gold else Muted,
                    )
                }
            }
        },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Titolo") },
                    leadingIcon = { Icon(Icons.Filled.Edit, null) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Aggiungi dettagli") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Subject, null) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(8.dp))
                // Scadenza (data) row.
                Row(
                    Modifier.fillMaxWidth().clickable { datePicker = true }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.CalendarMonth, null, tint = if (date != null) Cyan else Muted)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        date?.let { Agenda.fullDate(it, today) } ?: "Aggiungi scadenza",
                        color = if (date != null) Ink else Muted,
                    )
                    if (date != null) {
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { date = null; time = null }) {
                            Icon(Icons.Filled.Delete, "Rimuovi data", tint = Muted, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                // Ora row (only meaningful with a date).
                Row(
                    Modifier.fillMaxWidth().clickable { timePicker = true }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Schedule, null, tint = if (time != null) Cyan else Muted)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        time?.let { Agenda.humanTime(it) } ?: "Aggiungi ora",
                        color = if (time != null) Ink else Muted,
                    )
                    if (time != null) {
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { time = null }) {
                            Icon(Icons.Filled.Delete, "Rimuovi ora", tint = Muted, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                Spacer(Modifier.size(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onComplete) {
                        Icon(Icons.Filled.Check, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (entry.done) "Riapri" else "Segna come completa")
                    }
                    TextButton(onClick = onDelete) { Text("Elimina", color = Muted) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // A time with no date means "today at HH:MM", like Google Tasks.
                val d = date ?: time?.let { today }
                onSave(title, notes, d, time, starred)
            }) { Text("Salva") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )

    if (datePicker) {
        TaskDatePicker(
            initial = date,
            onDismiss = { datePicker = false },
            onPick = { date = it; datePicker = false },
        )
    }
    if (timePicker) {
        TaskTimePicker(
            initial = time,
            onDismiss = { timePicker = false },
            onPick = { time = it; timePicker = false },
        )
    }
}
