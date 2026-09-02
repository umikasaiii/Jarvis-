package com.simone.jarvismobile.corebridge

import android.util.Log
import com.simone.jarvismobile.core.ai.JarvisCoreState
import com.simone.jarvismobile.core.bridge.JarvisEvent
import com.simone.jarvismobile.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Qualifies the background [CoroutineScope] [EventBridge.publish] fires its
 * fire-and-forget work on. Pulled out to a real, Hilt-resolvable binding
 * (`di/CoreModule.kt`'s `@Provides`) — not just an inline
 * `CoroutineScope(SupervisorJob() + Dispatchers.IO)` field like most of this
 * codebase's ad-hoc scopes — specifically so a unit test can swap in a
 * deterministic `TestScope`/`UnconfinedTestDispatcher` and assert on
 * [EventBridge.publish]'s effects synchronously, without weakening its
 * "never suspends the caller" contract in production.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class EventBridgeScope

/**
 * Everything [EventBridge] needs to decide *whether* it may talk to Core —
 * split out from [SettingsRepository]/[CoreConnectionManager] (§ convenzione
 * del progetto "Interfaces first... Fakes for tests") so [EventBridge]'s
 * publish/flush logic is unit-testable with a canned fake instead of
 * requiring a real Android `Context`-backed DataStore, which nothing in
 * this JVM-only test environment can provide.
 */
interface EventBridgeGate {
    /** `settings.eventBridgeEnabled && settings.coreEnabled`. */
    suspend fun enabled(): Boolean

    /** The freshest known Core connection state, for deciding whether a flush is worth attempting now. */
    suspend fun coreState(): JarvisCoreState
}

@Singleton
class DefaultEventBridgeGate @Inject constructor(
    private val settings: SettingsRepository,
    private val connectionManager: CoreConnectionManager,
) : EventBridgeGate {
    override suspend fun enabled(): Boolean = settings.eventBridgeEnabled.first() && settings.coreEnabled.first()
    override suspend fun coreState(): JarvisCoreState = connectionManager.ensureFresh()
}

/**
 * Fire-and-forget publication point for Android → JARVIS Core events (§
 * richiesta esplicita: `eventBridge.publish(event)`, "non deve mai bloccare
 * Android, non deve generare errori visibili in UI"). Callers (§
 * `JarvisApplication`, `AutomationEventService`) never touch the queue or
 * the network directly — this is the one entry point, matching the
 * project's existing "callers depend only on an interface/manager" house
 * style (`WeatherManager`, `HealthConnectManager`).
 *
 * Android stays fully responsible for geofencing/automations/notifications/
 * driving mode — this class only reports events onward, it never decides
 * anything by itself (§ vincolo esplicito, ripetuto qui perché è il punto
 * più a rischio di malinteso di tutto il modulo).
 */
@Singleton
class EventBridge @Inject constructor(
    private val gate: EventBridgeGate,
    private val queue: EventQueue,
    private val coreClient: CoreClient,
    @EventBridgeScope private val scope: CoroutineScope,
) {
    /** Never suspends the caller — always returns immediately, all real work happens on [scope]. */
    fun publish(event: JarvisEvent) {
        scope.launch {
            if (!gate.enabled()) return@launch
            runCatching { queue.enqueue(event) }
                .onFailure { Log.w(TAG, "event_publish_enqueue_failed ${it.javaClass.simpleName}") }
            flushIfOnline()
        }
    }

    /** Best-effort delivery of whatever is currently queued — never throws, never blocks a caller since it only runs on [scope]. */
    suspend fun flushIfOnline() {
        if (!gate.enabled()) return
        val state = gate.coreState()
        if (!state.remoteUsable) return
        val pending = runCatching { queue.peekAll() }.getOrDefault(emptyList())
        if (pending.isEmpty()) return
        val delivered = mutableSetOf<String>()
        for (queued in pending) {
            val ok = runCatching { coreClient.publishEvent(queued.event) }.getOrDefault(false)
            if (ok) delivered += queued.event.id
        }
        if (delivered.isNotEmpty()) {
            runCatching { queue.removeDelivered(delivered) }
            Log.i(TAG, "event_bridge_flushed count=${delivered.size}")
        }
    }

    private companion object {
        const val TAG = "EventBridge"
    }
}
