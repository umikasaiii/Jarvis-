package com.simone.jarvismobile.core.ai

/**
 * Everything the heuristic needs to know to decide a target, gathered up front
 * by the caller (`app/ai/AiRouter.kt`) so the decision itself stays a pure
 * function — easy to fake, easy to test, easy to replace later with something
 * smarter without touching any Android code (§ richiesta esplicita:
 * "euristiche semplici, configurabili e facilmente sostituibili in futuro").
 */
data class AiRoutingPreferences(
    /** "AiRouter can ever pick a REMOTE_* target" feature flag — `SettingsRepository.remoteAiEnabled`. */
    val remoteAiEnabled: Boolean,
    /** Current JARVIS Core connection state — only [JarvisCoreState.remoteUsable] states allow remote routing. */
    val coreState: JarvisCoreState,
    /** Whether a second, larger remote model is actually available to route [AiRequestType.COMPLEX]/[AiRequestType.MEMORY] to. */
    val coreHasBrainModel: Boolean,
    /** Caller-level override: some callers (e.g. a background/offline-only context) may forbid remote outright. */
    val allowRemote: Boolean = true,
)

/**
 * The routing decision itself, distinct from [AiRoutingPreferences] (the
 * inputs) so a caller can log/inspect *why* a target was chosen without
 * re-deriving it.
 */
data class AiRouteDecision(
    val target: AiExecutionTarget,
    /** Short, non-personal reason string for logging (§ "non loggare contenuti personali sensibili"). */
    val reason: String,
)

/**
 * Simple, deliberately un-clever routing table (§ richiesta esplicita: "per
 * questa fase NON creare classificatori inutilmente complessi"). [decide] never
 * throws and never needs I/O — every input is already resolved by the caller.
 */
object AiRoutingHeuristic {

    fun decide(requestType: AiRequestType, prefs: AiRoutingPreferences): AiRouteDecision {
        val remoteAvailable = prefs.remoteAiEnabled && prefs.allowRemote && prefs.coreState.remoteUsable
        if (!remoteAvailable) {
            return AiRouteDecision(AiExecutionTarget.LOCAL, reasonForLocal(requestType, prefs))
        }
        return when (requestType) {
            AiRequestType.COMMAND ->
                // A deterministic command should stay instant and offline-safe —
                // never worth a network round trip even when Core is online.
                AiRouteDecision(AiExecutionTarget.LOCAL, "command_local_fast")
            AiRequestType.PROACTIVE ->
                // Never worth blocking a background suggestion on network latency;
                // the existing local path already handles this today.
                AiRouteDecision(AiExecutionTarget.LOCAL, "proactive_local")
            AiRequestType.CHAT, AiRequestType.TOOL ->
                AiRouteDecision(AiExecutionTarget.REMOTE_FAST, "remote_normal")
            AiRequestType.COMPLEX, AiRequestType.MEMORY ->
                if (prefs.coreHasBrainModel) {
                    AiRouteDecision(AiExecutionTarget.REMOTE_BRAIN, "remote_complex")
                } else {
                    // Core is online but hasn't got a bigger model loaded — its
                    // fast model still beats falling all the way back to local.
                    AiRouteDecision(AiExecutionTarget.REMOTE_FAST, "remote_complex_no_brain_model")
                }
        }
    }

    private fun reasonForLocal(requestType: AiRequestType, prefs: AiRoutingPreferences): String = when {
        !prefs.remoteAiEnabled -> "remote_ai_disabled"
        !prefs.allowRemote -> "caller_forbids_remote"
        requestType == AiRequestType.COMMAND -> "command_local_fast"
        requestType == AiRequestType.PROACTIVE -> "proactive_local"
        else -> "core_${prefs.coreState.name.lowercase()}"
    }
}
