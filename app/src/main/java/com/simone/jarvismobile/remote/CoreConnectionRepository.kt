package com.simone.jarvismobile.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.simone.jarvismobile.core.remote.CoreClientConfig
import com.simone.jarvismobile.core.remote.CoreConnectionCheck
import com.simone.jarvismobile.core.remote.CoreConnectionClassifier
import com.simone.jarvismobile.core.remote.CoreConnectionState
import com.simone.jarvismobile.core.remote.CoreResult
import com.simone.jarvismobile.core.remote.JarvisCoreClient
import com.simone.jarvismobile.data.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns JARVIS Core reachability: the centralized [CoreConnectionState] (task
 * §8) and the config-aware [JarvisCoreClient] instances built from current
 * Settings — never a hardcoded IP. Deliberately NOT a poller: a health check
 * only ever runs when [testConnection] is called explicitly (Settings screen)
 * or when [ensureFreshState] is asked for a decision and the cached result is
 * older than [CACHE_TTL_MS] — i.e. at most once per conversational turn that
 * is actually eligible for Core, never on a timer.
 */
@Singleton
class CoreConnectionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    private val _state = MutableStateFlow(CoreConnectionState.DISABLED)
    val state: StateFlow<CoreConnectionState> = _state.asStateFlow()

    private val _lastCheck = MutableStateFlow<CoreConnectionCheck?>(null)
    val lastCheck: StateFlow<CoreConnectionCheck?> = _lastCheck.asStateFlow()

    private var lastCheckAtMs = 0L
    private val checkMutex = Mutex()

    /** True only when the device has a real, validated internet-capable network. */
    val networkAvailable: Boolean
        get() {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }

    private suspend fun currentConfig(): CoreClientConfig? {
        if (!settings.coreEnabled.first()) return null
        val host = settings.coreHost.first()
        if (host.isBlank()) return null
        return CoreClientConfig(
            enabled = true,
            host = host,
            port = settings.corePort.first(),
            useHttps = settings.coreUseHttps.first(),
            timeoutMs = settings.coreTimeoutMs.first(),
            apiToken = settings.coreApiToken.first().ifBlank { null },
        )
    }

    /** A client for the CURRENT settings, or null if Core is disabled/unconfigured. */
    suspend fun clientOrNull(): JarvisCoreClient? = currentConfig()?.let { JarvisCoreClient(it) }

    /** Explicit, user-triggered probe ("Testa connessione"). Always fresh, bypasses the cache. */
    suspend fun testConnection(): CoreConnectionCheck = checkMutex.withLock { runCheckLocked() }

    /**
     * Cheap, cache-first reachability read for the AI router. Reuses the last
     * result within [CACHE_TTL_MS]; otherwise runs exactly one fresh check.
     */
    suspend fun ensureFreshState(): CoreConnectionState = checkMutex.withLock {
        val config = currentConfig()
        if (config == null) {
            _state.value = CoreConnectionState.DISABLED
            return@withLock CoreConnectionState.DISABLED
        }
        val age = System.currentTimeMillis() - lastCheckAtMs
        if (_state.value != CoreConnectionState.DISABLED && age in 0 until CACHE_TTL_MS) {
            return@withLock _state.value
        }
        runCheckLocked().state
    }

    /**
     * A remote request/stream failed AFTER a fresh ONLINE read (task §8:
     * "usa cache/refresh/eventi" — a real failure is exactly such an event).
     * Invalidates the cache so the next turn re-checks instead of trusting a
     * now-stale ONLINE for the next [CACHE_TTL_MS].
     */
    fun reportRuntimeFailure() {
        lastCheckAtMs = 0L
        _state.value = CoreConnectionState.OFFLINE
    }

    private suspend fun runCheckLocked(): CoreConnectionCheck {
        val config = currentConfig()
        if (config == null) {
            val check = CoreConnectionCheck(CoreConnectionState.DISABLED, reachable = false, latencyMs = null)
            _state.value = check.state
            _lastCheck.value = check
            return check
        }
        _state.value = CoreConnectionState.CONNECTING
        val client = JarvisCoreClient(config)
        val start = System.currentTimeMillis()
        val healthResult = client.health()
        val latency = System.currentTimeMillis() - start
        val capabilities = (healthResult as? CoreResult.Success)?.let {
            (client.capabilities() as? CoreResult.Success)?.value
        }
        val check = CoreConnectionClassifier.classify(healthResult, latency, capabilities)
        _state.value = check.state
        _lastCheck.value = check
        lastCheckAtMs = System.currentTimeMillis()
        return check
    }

    private companion object {
        const val CACHE_TTL_MS = 20_000L
    }
}
