package com.simone.jarvismobile.ui.automation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simone.jarvismobile.automation.AutomationRepository
import com.simone.jarvismobile.automation.AutomationRunner
import com.simone.jarvismobile.automation.DeferredCommand
import com.simone.jarvismobile.core.automation.Automation
import com.simone.jarvismobile.core.automation.AutomationCodec
import com.simone.jarvismobile.core.automation.AutomationPhrase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AutomationsViewModel @Inject constructor(
    private val repository: AutomationRepository,
    private val runner: AutomationRunner,
) : ViewModel() {

    val automations: StateFlow<List<Automation>> = repository.automations

    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message

    init {
        viewModelScope.launch { runCatching { repository.reload() } }
    }

    /**
     * Creates a rule from a plain Italian sentence — the same grammar the chat
     * understands, so there is one thing to learn rather than a form and a
     * phrasebook.
     */
    fun create(phrase: String) = viewModelScope.launch {
        // Recurring/conditional first, then a one-shot device command
        // ("alle 11.45 accendi la torcia"): the same grammar the chat understands.
        val parsed = AutomationPhrase.parse(phrase) ?: DeferredCommand.parse(phrase)
        if (parsed == null) {
            _message.value = "Non ho capito la regola. Prova: «ogni giorno alle 8 " +
                "ricordami di prendere le vitamine», «quando la batteria " +
                "scende sotto il 20% avvisami» oppure «alle 11.45 accendi la torcia»."
            return@launch
        }
        _message.value = if (repository.add(parsed)) {
            "Creata: ${AutomationCodec.describe(parsed)}"
        } else {
            // Name the reason (vault, disk, or a bug): a bare "could not save" is a
            // dead end for the user and for whoever has to fix it.
            val why = repository.lastError.value
            "Non sono riuscito a salvare la regola" + (if (why.isBlank()) "." else " ($why).")
        }
    }

    /**
     * Creates a rule from a structured [trigger] + [action] chosen in the UI —
     * the way device-event automations (unlock, headphones, airplane…) are made,
     * since those have no spoken grammar. Goes through the same [repository] as a
     * phrased rule, so it lands in `Automazioni.md` identically.
     */
    fun createBuilt(trigger: com.simone.jarvismobile.core.automation.Trigger, action: com.simone.jarvismobile.core.automation.Action) =
        viewModelScope.launch {
            val rule = Automation(name = action.payload.take(60).ifBlank { "regola" }, trigger = trigger, action = action)
            _message.value = if (repository.add(rule)) {
                "Creata: ${AutomationCodec.describe(rule)}"
            } else {
                val why = repository.lastError.value
                "Non sono riuscito a salvare la regola" + (if (why.isBlank()) "." else " ($why).")
            }
        }

    fun toggle(automation: Automation) = viewModelScope.launch {
        repository.setEnabled(automation.id, !automation.enabled)
    }

    fun delete(automation: Automation) = viewModelScope.launch {
        if (repository.remove(automation.id)) {
            _message.value = "Eliminata: ${AutomationCodec.describe(automation)}"
        }
    }

    /** Runs a rule now, so its effect can be judged without waiting for it. */
    fun testRun(automation: Automation) = viewModelScope.launch {
        _message.value = if (runner.run(automation)) {
            "Eseguita adesso."
        } else {
            "Non è stato possibile eseguirla: controlla i permessi di notifica."
        }
    }

    fun clearMessage() {
        _message.value = ""
    }
}
