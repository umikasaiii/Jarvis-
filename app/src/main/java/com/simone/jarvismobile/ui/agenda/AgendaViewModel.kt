package com.simone.jarvismobile.ui.agenda

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simone.jarvismobile.agenda.AgendaRepository
import com.simone.jarvismobile.core.agenda.AgendaEntry
import com.simone.jarvismobile.core.agenda.ReminderAlert
import com.simone.jarvismobile.core.agenda.ReminderAlertType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/**
 * Drives the unified "Attività" section — a Google-Tasks-style planner over the
 * one agenda file. Tasks live in [lists]; each can be starred, given a due date,
 * completed (which files it under "Completate"), and can carry one level of
 * sub-tasks. Everything is the real `Agenda.md`.
 */
@HiltViewModel
class AgendaViewModel @Inject constructor(
    private val agenda: AgendaRepository,
) : ViewModel() {

    val entries: StateFlow<List<AgendaEntry>> = agenda.entries

    /** The starred pseudo-list, shown as its own tab like Google Tasks. */
    val starredTab = "★ Speciali"

    /** Tab names: the starred view first, then every real list. */
    val lists: StateFlow<List<String>> = entries
        .map { all ->
            val real = all.map { it.list }.filter { it.isNotBlank() }.distinct().sorted()
            listOf(starredTab) + real.ifEmpty { listOf(AgendaEntry.DEFAULT_LIST) }
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            listOf(starredTab, AgendaEntry.DEFAULT_LIST),
        )

    private val _selectedList = MutableStateFlow(AgendaEntry.DEFAULT_LIST)
    val selectedList: StateFlow<String> = _selectedList

    /** Message surfaced under the input (e.g. a save failure). */
    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message

    /** The entries shown for the current tab, split into open and completed. */
    val visible: StateFlow<TaskView> = combine(entries, _selectedList) { all, list ->
        val inTab = all.filter { e -> if (list == starredTab) e.starred else e.list == list }
        val childrenByParent = inTab.filter { it.parentId != null }.groupBy { it.parentId }
        val parents = inTab.filter { it.parentId == null }
        TaskView(
            open = parents.filterNot { it.done }.map { it to childrenByParent[it.id].orEmpty() },
            // Completed archive ordered from the one ticked first onward. Items
            // completed before completion dates were recorded (null) go last.
            done = parents.filter { it.done }
                .sortedWith(compareBy(nullsLast<java.time.LocalDate>()) { it.completedAt }),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TaskView())

    init {
        viewModelScope.launch { runCatching { agenda.reload() } }
    }

    fun selectList(list: String) { _selectedList.value = list }

    fun clearMessage() { _message.value = "" }

    /**
     * Adds a task to the current list (never the starred pseudo-tab), Google-Tasks
     * style: an optional due date, an optional time (which makes it a timed
     * appointment on the dashboard), and an optional star — all chosen inline
     * before saving.
     */
    fun addTask(
        title: String,
        due: LocalDate? = null,
        time: java.time.LocalTime? = null,
        starred: Boolean = false,
    ) = viewModelScope.launch {
        val clean = title.trim()
        if (clean.isEmpty()) return@launch
        val list = _selectedList.value.takeIf { it != starredTab } ?: AgendaEntry.DEFAULT_LIST
        // A time with no date means "today at HH:MM" — the same as Google Tasks.
        val date = due ?: time?.let { LocalDate.now() }
        // A dated task gets a notification by default, just like a reminder added
        // by voice/chat: at its hour if timed, otherwise the morning of. An
        // undated task has nothing to fire on, so it stays silent. The bell can
        // be removed by hand from the dashboard.
        val alerts = if (date != null) {
            listOf(ReminderAlert(if (time != null) ReminderAlertType.AT_TIME else ReminderAlertType.MORNING_OF))
        } else {
            emptyList()
        }
        val entry = AgendaEntry(date = date, time = time, text = clean, list = list, starred = starred, alerts = alerts)
        if (!agenda.add(entry)) _message.value = "Non sono riuscito a salvare l'attività."
    }

    fun addSubtask(parent: AgendaEntry, title: String) = viewModelScope.launch {
        runCatching { agenda.addSubtask(parent.id, title) }
    }

    /** Flips done/to-do by id — the tap target is a dot, never text matching. */
    fun toggle(entry: AgendaEntry) = viewModelScope.launch {
        runCatching { agenda.setDone(entry.id, !entry.done) }
    }

    fun toggleStar(entry: AgendaEntry) = viewModelScope.launch {
        runCatching { agenda.update(entry.id) { it.copy(starred = !it.starred) } }
    }

    fun rename(entry: AgendaEntry, title: String) = viewModelScope.launch {
        val clean = title.trim()
        if (clean.isNotEmpty()) runCatching { agenda.update(entry.id) { it.copy(text = clean) } }
    }

    fun setNotes(entry: AgendaEntry, notes: String) = viewModelScope.launch {
        runCatching { agenda.update(entry.id) { it.copy(notes = notes.trim()) } }
    }

    fun setDue(entry: AgendaEntry, due: LocalDate?) = viewModelScope.launch {
        runCatching { agenda.update(entry.id) { it.copy(date = due) } }
    }

    /** Saves the whole editor at once (title, details, due date, time, star). */
    fun saveDetails(
        entry: AgendaEntry,
        title: String,
        notes: String,
        date: LocalDate?,
        time: java.time.LocalTime?,
        starred: Boolean,
    ) = viewModelScope.launch {
        val clean = title.trim().ifEmpty { entry.text }
        runCatching {
            agenda.update(entry.id) {
                it.copy(text = clean, notes = notes.trim(), date = date, time = time, starred = starred)
            }
        }
    }

    fun moveTo(entry: AgendaEntry, list: String) = viewModelScope.launch {
        runCatching { agenda.update(entry.id) { it.copy(list = list) } }
    }

    fun delete(entry: AgendaEntry) = viewModelScope.launch {
        runCatching { agenda.removeById(entry.id) }
    }
}

/** A tab's tasks, ready for the screen: open (with their sub-tasks) and completed. */
data class TaskView(
    val open: List<Pair<AgendaEntry, List<AgendaEntry>>> = emptyList(),
    val done: List<AgendaEntry> = emptyList(),
)
