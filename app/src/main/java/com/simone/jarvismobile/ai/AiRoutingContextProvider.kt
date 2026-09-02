package com.simone.jarvismobile.ai

import com.simone.jarvismobile.core.ai.AiRequestType
import com.simone.jarvismobile.core.ai.AiRoutingPreferences
import com.simone.jarvismobile.corebridge.CoreConnectionManager
import com.simone.jarvismobile.data.SettingsRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gathers the inputs [com.simone.jarvismobile.core.ai.AiRoutingHeuristic.decide]
 * needs — split out from [AiRouter] itself (§ convenzione del progetto
 * "Interfaces first... Fakes for tests") so [AiRouter]'s fallback/logging
 * logic is unit-testable with a canned fake instead of requiring a real
 * Android `Context`-backed [SettingsRepository]/[CoreConnectionManager],
 * which nothing in this JVM-only test environment can provide.
 */
fun interface AiRoutingContextProvider {
    suspend fun preferencesFor(requestType: AiRequestType): AiRoutingPreferences
}

@Singleton
class DefaultAiRoutingContextProvider @Inject constructor(
    private val settings: SettingsRepository,
    private val connectionManager: CoreConnectionManager,
) : AiRoutingContextProvider {
    override suspend fun preferencesFor(requestType: AiRequestType): AiRoutingPreferences {
        val remoteAiEnabled = settings.remoteAiEnabled.first()
        val coreState = if (remoteAiEnabled) connectionManager.ensureFresh() else connectionManager.state.value
        return AiRoutingPreferences(
            remoteAiEnabled = remoteAiEnabled,
            coreState = coreState,
            // No capability-negotiation endpoint is defined yet (§ onestà: no real
            // Core server exists to query) — never claim a brain model is present.
            coreHasBrainModel = false,
        )
    }
}
