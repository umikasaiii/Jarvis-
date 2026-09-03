package com.simone.jarvismobile.corebridge

import android.util.Log
import com.simone.jarvismobile.core.ai.CoreStateDecision
import com.simone.jarvismobile.core.ai.JarvisCoreState
import com.simone.jarvismobile.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the single observable [state] toward JARVIS Core, reachable from any
 * Hilt-injected class exactly the way `HealthConnectManager`/`AresViewModel`
 * expose their own `StateFlow`s (§ audit item 8). [DISABLED] until the user
 * turns Core on in settings (§ richiesta esplicita: default disattivato).
 *
 * **Performance (§ richiesta esplicita: "non eseguirlo ad ogni messaggio;
 * usa stato cache; heartbeat leggero; retry con backoff")**: [ensureFresh]
 * is the one thing [RemoteAiEngine]/[EventBridge] actually call before a
 * remote attempt — it reuses [state] if it was refreshed within
 * [FRESH_WINDOW_MS] instead of hitting the network again. A lightweight
 * background loop (this class's own lifetime, not WorkManager — sub-minute
 * cadence would be too fine-grained for a periodic `WorkManager` job) keeps
 * [state] warm while Core is enabled, backing off up to [MAX_INTERVAL_MS]
 * on repeated failure and resetting to [BASE_INTERVAL_MS] on the first
 * success — never a tight, battery-draining poll.
 */
@Singleton
class CoreConnectionManager @Inject constructor(
    private val settings: SettingsRepository,
    private val coreClient: CoreClient,
) {
    private val _state = MutableStateFlow(JarvisCoreState.DISABLED)
    val state: StateFlow<JarvisCoreState> = _state.asStateFlow()

    /** Last server-reported version from a successful heartbeat, if any — for diagnostics only. */
    @Volatile var lastKnownServerVersion: String? = null
        private set

    private var lastCheckAtMs: Long = 0L
    private val checkMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            settings.coreEnabled.distinctUntilChanged().collectLatest { enabled ->
                if (!enabled) {
                    _state.value = JarvisCoreState.DISABLED
                    return@collectLatest
                }
                heartbeatLoop()
            }
        }
    }

    /** Runs until [SettingsRepository.coreEnabled] flips off (this coroutine is then cancelled by `collectLatest`). */
    private suspend fun heartbeatLoop() {
        var intervalMs = BASE_INTERVAL_MS
        while (true) {
            refreshNow()
            intervalMs = if (_state.value == JarvisCoreState.ONLINE || _state.value == JarvisCoreState.DEGRADED) {
                BASE_INTERVAL_MS
            } else {
                (intervalMs * 2).coerceAtMost(MAX_INTERVAL_MS)
            }
            delay(intervalMs)
        }
    }

    /** Uses the cached [state] if it was refreshed within [FRESH_WINDOW_MS]; otherwise does one real health check. */
    suspend fun ensureFresh(): JarvisCoreState {
        if (!settings.coreEnabled.first()) return JarvisCoreState.DISABLED
        val ageMs = System.currentTimeMillis() - lastCheckAtMs
        if (ageMs in 0..FRESH_WINDOW_MS && _state.value != JarvisCoreState.DISABLED) return _state.value
        refreshNow()
        return _state.value
    }

    /** Forces one real health check now, updating [state]. Safe to call concurrently — collapses to one in-flight check. */
    suspend fun refreshNow() {
        checkMutex.withLock {
            if (!settings.coreEnabled.first()) {
                _state.value = JarvisCoreState.DISABLED
                return
            }
            if (_state.value == JarvisCoreState.DISABLED) _state.value = JarvisCoreState.CONNECTING
            val result = runCatching { coreClient.healthCheck() }.getOrNull()
            lastCheckAtMs = System.currentTimeMillis()
            _state.value = CoreStateDecision.fromHealthCheck(
                reachable = result?.reachable ?: false,
                protocolVersion = result?.protocolVersion,
                expectedProtocolVersion = CORE_PROTOCOL_VERSION,
                llmAvailable = result?.llmAvailable,
            )
            result?.serverVersion?.let { lastKnownServerVersion = it }
            Log.i(TAG, "core_state=${_state.value}")
        }
    }

    private companion object {
        const val TAG = "JarvisCoreConnection"
        const val FRESH_WINDOW_MS = 15_000L
        const val BASE_INTERVAL_MS = 30_000L
        const val MAX_INTERVAL_MS = 5 * 60_000L
    }
}
