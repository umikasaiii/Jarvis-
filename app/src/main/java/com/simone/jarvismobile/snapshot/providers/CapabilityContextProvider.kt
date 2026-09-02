package com.simone.jarvismobile.snapshot.providers

import com.simone.jarvismobile.context.ContextEngine
import com.simone.jarvismobile.core.snapshot.CapabilityContext
import com.simone.jarvismobile.corebridge.CoreConnectionManager
import com.simone.jarvismobile.llm.LlmLoadState
import com.simone.jarvismobile.llm.LlmRouter
import com.simone.jarvismobile.memory.MemoryIndex
import com.simone.jarvismobile.navigation.NavigationRepository
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A contextual "what can JARVIS actually do right now" read, distinct from
 * [com.simone.jarvismobile.core.tools.ToolRegistry] (§ richiesta esplicita:
 * "Questo NON deve sostituire ToolRegistry. Serve solamente come
 * rappresentazione contestuale delle capacità") — every field reads an
 * existing observable/one-shot state, nothing new is tracked.
 */
fun interface CapabilityContextProvider {
    suspend fun provide(): CapabilityContext?
}

@Singleton
class DefaultCapabilityContextProvider @Inject constructor(
    private val llmRouter: LlmRouter,
    private val coreConnectionManager: CoreConnectionManager,
    private val memoryIndex: MemoryIndex,
    private val navigationRepository: NavigationRepository,
    private val contextEngine: ContextEngine,
) : CapabilityContextProvider {

    override suspend fun provide(): CapabilityContext = CapabilityContext(
        localAiAvailable = llmRouter.loadState.value == LlmLoadState.LOADED,
        coreAvailable = coreConnectionManager.state.value.remoteUsable,
        navigationAvailable = navigationRepository.regions.value.isNotEmpty(),
        memoryAvailable = runCatching { memoryIndex.isConfigured() }.getOrDefault(false),
        agendaAvailable = true,
        networkAvailable = contextEngine.state.value.networkAvailable ?: false,
        capturedAt = Instant.now(),
    )
}
