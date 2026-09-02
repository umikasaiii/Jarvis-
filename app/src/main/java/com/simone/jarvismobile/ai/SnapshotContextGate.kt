package com.simone.jarvismobile.ai

import com.simone.jarvismobile.core.snapshot.ContextBudget
import com.simone.jarvismobile.data.SettingsRepository
import com.simone.jarvismobile.engine.snapshotBudget
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What [AiRouter] needs to decide whether/how to auto-attach a
 * [com.simone.jarvismobile.core.snapshot.RelevantPersonalContext] — split
 * out from [SettingsRepository] (§ convenzione del progetto "Interfaces
 * first... Fakes for tests", same pattern as [AiRoutingContextProvider]/
 * `EventBridgeGate`) so [AiRouter] stays testable with a plain fake instead
 * of requiring a real Android `Context`-backed [SettingsRepository].
 */
interface SnapshotContextGate {
    suspend fun enabled(): Boolean
    suspend fun budget(): ContextBudget
}

@Singleton
class DefaultSnapshotContextGate @Inject constructor(
    private val settings: SettingsRepository,
) : SnapshotContextGate {
    override suspend fun enabled(): Boolean = settings.jarvisPersonalSnapshotEnabled.first()
    override suspend fun budget(): ContextBudget = settings.snapshotBudget()
}
