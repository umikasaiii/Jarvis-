package com.simone.jarvismobile.ui.agenda

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simone.jarvismobile.agenda.AgendaRepository
import com.simone.jarvismobile.core.agenda.AgendaEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AgendaViewModel @Inject constructor(
    private val agenda: AgendaRepository,
) : ViewModel() {

    val entries: StateFlow<List<AgendaEntry>> = agenda.entries

    init {
        viewModelScope.launch { runCatching { agenda.reload() } }
    }

    /**
     * Flips an item between done and to-do. Toggling by id rather than by text
     * so two items with similar wording can never be confused — the chat path
     * has to match on words, this one does not.
     */
    fun toggle(entry: AgendaEntry) {
        viewModelScope.launch { runCatching { agenda.setDone(entry.id, !entry.done) } }
    }
}
