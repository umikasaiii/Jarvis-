package com.simone.jarvismobile.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simone.jarvismobile.agenda.AgendaRepository
import com.simone.jarvismobile.audio.SessionCoordinator
import com.simone.jarvismobile.audio.WakeWordController
import com.simone.jarvismobile.core.agenda.Agenda
import com.simone.jarvismobile.core.agenda.AgendaEntry
import com.simone.jarvismobile.core.agenda.ReminderAlert
import com.simone.jarvismobile.core.state.ConversationState
import com.simone.jarvismobile.data.SettingsRepository
import com.simone.jarvismobile.llm.LlmLoadState
import com.simone.jarvismobile.memory.MemoryIndex
import com.simone.jarvismobile.promode.ProModeManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val coordinator: SessionCoordinator,
    private val agenda: AgendaRepository,
    private val wakeWord: WakeWordController,
    memory: MemoryIndex,
    settings: SettingsRepository,
    proMode: ProModeManager,
) : ViewModel() {

    val state: StateFlow<ConversationState> = coordinator.state

    /** The "piccolo segnale" for the dashboard header — same source as the Settings toggle. */
    val proModeActive: StateFlow<Boolean> = proMode.isActive
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** True while listening for the wake word (foreground only), for the orb hint. */
    val listeningForWake: StateFlow<Boolean> = wakeWord.listeningForWake

    /** Called by the screen on resume/pause so the wake word runs only when open. */
    fun setWakeActive(active: Boolean) = wakeWord.setForegroundActive(active)
    val llmLoadState: StateFlow<LlmLoadState> = coordinator.llmLoadState
    val loadedModelName: StateFlow<String?> = coordinator.loadedModelName
    val memoryStatus: StateFlow<MemoryIndex.Status> = memory.status

    /** Last recoverable failure, so the orb can show it instead of idling. */
    val lastError: StateFlow<String?> = coordinator.lastError

    val assistantName: StateFlow<String> = settings.assistantName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_NAME)

    // Unread badge for the floating chat button: messages that arrived (e.g. from
    // a voice exchange on the orb) since the chat was last opened.
    private val messageCount: StateFlow<Int> = coordinator.messages
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), coordinator.messages.value.size)
    private val lastSeen = MutableStateFlow(coordinator.messages.value.size)
    val unread: StateFlow<Int> = combine(messageCount, lastSeen) { count, seen ->
        (count - seen).coerceAtLeast(0)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * Marks everything currently in the transcript as seen. Called both when the
     * chat opens and when it closes: clearing only on open left the badge showing
     * messages that arrived while the chat was in front of the user, which then
     * needed a pointless open/close to dismiss.
     */
    fun markChatSeen() {
        lastSeen.value = coordinator.messages.value.size
        // Reading the chat also clears the shade: a "risposta pronta" entry for a
        // message the user is looking at is just noise.
        coordinator.clearResponseNotifications()
    }

    /** Every agenda entry, so a tapped calendar day can list exactly its items. */
    val allEntries: StateFlow<List<AgendaEntry>> = agenda.entries

    /** Marks a day's task done/undone from the dashboard day view. */
    fun toggleDone(entry: AgendaEntry) {
        viewModelScope.launch { runCatching { agenda.setDone(entry.id, !entry.done) } }
    }

    /** Everything still ahead, so the Agenda tile shows the real calendar. */
    val upcoming: StateFlow<List<AgendaEntry>> = agenda.entries
        .map { Agenda.filter(it, java.time.LocalDate.now()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Today's slice of the agenda, for the Panoramica counters. */
    val today: StateFlow<List<AgendaEntry>> = agenda.entries
        .map { entries ->
            val d = java.time.LocalDate.now()
            Agenda.filter(entries, d, day = d)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The dashboard timeline: today's already-completed items shown alongside
     * everything still ahead, in chronological order. Done items are kept in so
     * the "Completato" badge is real and not decoration — the plain [upcoming]
     * flow drops them.
     */
    val timeline: StateFlow<List<AgendaEntry>> = agenda.entries
        .map { entries ->
            val d = java.time.LocalDate.now()
            val doneToday = Agenda.filter(entries, d, day = d, includeDone = true).filter { it.done }
            val ahead = Agenda.filter(entries, d)
            Agenda.sorted(doneToday + ahead)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // Ensure the model auto-loads and the vault index is built even if the
        // user opens the app straight onto the dashboard tab.
        viewModelScope.launch {
            // Bring back the saved conversation first, then treat it as already
            // seen — resuming a chat is not a new notification.
            coordinator.restoreChat()
            lastSeen.value = coordinator.messages.value.size
            coordinator.ensureModelReady()
        }
        viewModelScope.launch { coordinator.ensureMemoryReady() }
        viewModelScope.launch { runCatching { coordinator.ensureKnowledgeReady() } }
        viewModelScope.launch { runCatching { agenda.reload() } }
    }

    fun refreshAgenda() { viewModelScope.launch { runCatching { agenda.reload() } } }

    fun updateAlerts(entryId: String, alerts: List<ReminderAlert>) {
        viewModelScope.launch { runCatching { agenda.updateAlerts(entryId, alerts) } }
    }

    fun hasRecordPermission(): Boolean = coordinator.hasRecordPermission()
    fun onTalkPressed() { coordinator.startSession() }
    fun onCancel() = coordinator.cancel()
    fun onInterruptAndTalk() = coordinator.interruptAndListen()
}
