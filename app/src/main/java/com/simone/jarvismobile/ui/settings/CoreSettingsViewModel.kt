package com.simone.jarvismobile.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simone.jarvismobile.ai.AiRoutingContextProvider
import com.simone.jarvismobile.core.ai.AiExecutionTarget
import com.simone.jarvismobile.core.ai.AiRequestType
import com.simone.jarvismobile.core.ai.AiRoutingHeuristic
import com.simone.jarvismobile.core.ai.JarvisCoreState
import com.simone.jarvismobile.corebridge.CoreClient
import com.simone.jarvismobile.corebridge.CoreConnectionManager
import com.simone.jarvismobile.corebridge.CoreConnectionTestResult
import com.simone.jarvismobile.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the «JARVIS Core (PC)» settings section — self-contained so
 * [SettingsViewModel] stays lean, same pattern as [EngineSettingsViewModel].
 * Every field here already existed in [SettingsRepository] (§ fondamenta
 * fase 8): this ViewModel only exposes them to a screen, it does not add
 * new persisted state.
 */
@HiltViewModel
class CoreSettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val coreClient: CoreClient,
    private val connectionManager: CoreConnectionManager,
    private val routingContext: AiRoutingContextProvider,
) : ViewModel() {

    val coreEnabled: StateFlow<Boolean> =
        settings.coreEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val coreHost: StateFlow<String> =
        settings.coreHost.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")
    val corePort: StateFlow<Int> =
        settings.corePort.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_CORE_PORT)
    val coreHttps: StateFlow<Boolean> =
        settings.coreHttps.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val coreTimeoutMs: StateFlow<Int> =
        settings.coreTimeoutMs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_CORE_TIMEOUT_MS)
    val corePreferRemote: StateFlow<Boolean> =
        settings.corePreferRemote.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /**
     * "AiRouter può instradare al PC" — distinta da [coreEnabled] apposta
     * (§ `SettingsRepository.remoteAiEnabled`'s own doc comment): tenere il
     * Core configurato e raggiungibile ma in pausa, senza doverne cancellare
     * host/porta. Spenta di default finché l'utente non l'accende qui.
     */
    val remoteAiEnabled: StateFlow<Boolean> =
        settings.remoteAiEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Live heartbeat state — the same one [com.simone.jarvismobile.audio.SessionCoordinator]'s routing decision reads. */
    val connectionState: StateFlow<JarvisCoreState> = connectionManager.state

    /**
     * "Se mandassi ora un messaggio ordinario, dove andrebbe?" — computed with
     * the exact same [AiRoutingHeuristic]/[AiRoutingContextProvider] the live
     * chat turn uses (§ richiesta esplicita: "verifica che i toggle siano
     * realmente letti dal router della chat"), recomputed whenever any input
     * changes. Exists because "Testa connessione" probes Core directly and
     * can report Online **independently of `coreEnabled`** (see
     * [CoreClient.testConnection]) — a real, easy-to-hit trap where that
     * button shows success while ordinary chat still never leaves the
     * device, because `coreEnabled` off keeps [CoreConnectionManager.state]
     * at [JarvisCoreState.DISABLED] regardless. This line makes the two
     * signals impossible to confuse instead of relying on Logcat.
     */
    val liveRoutingPreview: StateFlow<String> = combine(
        settings.coreEnabled,
        settings.remoteAiEnabled,
        connectionManager.state,
    ) { _, _, _ ->
        val decision = AiRoutingHeuristic.decide(AiRequestType.CHAT, routingContext.preferencesFor(AiRequestType.CHAT))
        when (decision.target) {
            AiExecutionTarget.REMOTE_FAST -> "Ora: JARVIS Core (rapido)"
            AiExecutionTarget.REMOTE_BRAIN -> "Ora: JARVIS Core (avanzato)"
            AiExecutionTarget.LOCAL -> "Ora: modello locale — ${localReasonLabel(decision.reason)}"
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "Ora: —")

    private fun localReasonLabel(reason: String): String = when (reason) {
        "remote_ai_disabled" -> "\"Instrada le richieste al PC\" è spento"
        "core_disabled" -> "\"Abilita Core\" è spento (la connessione riuscita di \"Testa connessione\" non basta da sola)"
        "core_offline" -> "Core non raggiungibile"
        "core_connecting" -> "connessione a Core in corso"
        "core_error" -> "Core in errore"
        "caller_forbids_remote" -> "instradamento remoto non permesso in questo contesto"
        else -> reason
    }

    fun setCoreEnabled(value: Boolean) = viewModelScope.launch { settings.setCoreEnabled(value) }
    fun setCoreHost(value: String) = viewModelScope.launch { settings.setCoreHost(value) }
    fun setCorePort(value: Int) = viewModelScope.launch { settings.setCorePort(value) }
    fun setCoreHttps(value: Boolean) = viewModelScope.launch { settings.setCoreHttps(value) }
    fun setCoreTimeoutMs(value: Int) = viewModelScope.launch { settings.setCoreTimeoutMs(value) }
    fun setCorePreferRemote(value: Boolean) = viewModelScope.launch { settings.setCorePreferRemote(value) }
    fun setRemoteAiEnabled(value: Boolean) = viewModelScope.launch { settings.setRemoteAiEnabled(value) }

    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing.asStateFlow()

    private val _testResult = MutableStateFlow<CoreConnectionTestResult?>(null)
    val testResult: StateFlow<CoreConnectionTestResult?> = _testResult.asStateFlow()

    /**
     * Chiama davvero `GET /v1/health` (+ `GET /v1/capabilities`) tramite
     * [CoreClient.testConnection] — non un ping finto. Funziona anche prima
     * di accendere [coreEnabled] (utile per verificare host/porta prima di
     * attivare il routing remoto), e forza anche un refresh dell'heartbeat
     * vivo così lo stato che [connectionState] mostra si allinea subito al
     * test invece di aspettare il prossimo ciclo in background.
     */
    fun testConnection() {
        viewModelScope.launch {
            _testing.value = true
            _testResult.value = runCatching { coreClient.testConnection() }
                .getOrElse { e -> CoreConnectionTestResult(reachable = false, error = e.javaClass.simpleName) }
            _testing.value = false
            connectionManager.refreshNow()
        }
    }
}
