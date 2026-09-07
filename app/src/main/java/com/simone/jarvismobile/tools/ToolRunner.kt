package com.simone.jarvismobile.tools

import android.util.Log
import com.simone.jarvismobile.core.protocol.ToolCall
import com.simone.jarvismobile.core.tools.StructuredToolResult
import com.simone.jarvismobile.core.tools.ToolOutcomeStatus
import com.simone.jarvismobile.core.tools.ToolRegistry
import com.simone.jarvismobile.core.tools.ToolRejection
import com.simone.jarvismobile.core.tools.ToolResolution
import com.simone.jarvismobile.core.tools.ToolResult
import com.simone.jarvismobile.core.tools.resolveOutcomeStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/** What the assistant should say/do after attempting a tool. */
sealed interface ToolOutcome {
    /**
     * Executed successfully; [spoken] is ready to be said aloud. [raw] is the
     * tool's full structured output (e.g. a newly created entry's `id`) for
     * callers that need more than the spoken sentence — today only
     * `ConversationManager`, tracking which agenda entry a fast-path
     * `add_reminder`/`add_task` call just created.
     *
     * [evidence] (§ JARVIS Implementation Master Plan PASSAGGIO 1) is the
     * SAME [StructuredToolResult] the underlying [ToolResult.Success]
     * carried, when the tool sets one — null for every tool not yet
     * migrated, so [spoken] remains the only channel those callers ever
     * needed (§11: the old renderer may keep using [spoken] as-is; the
     * structured result survives alongside it, never replacing it here).
     */
    data class Done(val spoken: String, val raw: JsonObject = JsonObject(emptyMap()), val evidence: StructuredToolResult? = null) : ToolOutcome

    /** The tool needs explicit user confirmation before it may run. */
    data class NeedsConfirmation(val call: ToolCall, val prompt: String) : ToolOutcome

    /** Could not run; [spoken] explains it plainly, [code] is technical. [evidence] as in [Done] — carries the real [ToolOutcomeStatus] when known, never guessed beyond [resolveOutcomeStatus]'s honest fallback. */
    data class Failed(val code: String, val spoken: String, val evidence: StructuredToolResult? = null) : ToolOutcome
}

/**
 * § PASSAGGIO 1 §18 — the real status this outcome represents, falling back
 * to [resolveOutcomeStatus]'s honest legacy mapping when the underlying tool
 * hasn't set [ToolOutcome.Done.evidence]/[ToolOutcome.Failed.evidence] yet.
 * `NeedsConfirmation` has no data outcome of its own (§13 — side-effect gate,
 * out of this phase's scope), so it returns null.
 */
fun ToolOutcome.statusOrNull(): ToolOutcomeStatus? = when (this) {
    is ToolOutcome.Done -> resolveOutcomeStatus(evidence, wasSuccess = true)
    is ToolOutcome.Failed -> resolveOutcomeStatus(evidence, wasSuccess = false)
    is ToolOutcome.NeedsConfirmation -> null
}

/**
 * Executes tool calls against the [ToolRegistry], honouring the registry's
 * authoritative confirmation policy and each tool's timeout. The model can only
 * ever reach tools registered here — never arbitrary code (docs/SECURITY.md §15).
 */
@Singleton
class ToolRunner @Inject constructor(
    private val registry: ToolRegistry,
) {
    /** Names of the available tools, for the Commands screen. */
    fun available() = registry.tools.map { it.name to it.description }

    /**
     * Resolves and (unless confirmation is required) runs [call].
     * Pass [confirmed] = true to execute a call the user has just approved.
     */
    suspend fun run(call: ToolCall, online: Boolean = false, confirmed: Boolean = false): ToolOutcome {
        return when (val resolution = registry.resolve(call, online)) {
            is ToolResolution.Rejected -> ToolOutcome.Failed(
                code = rejectionCode(resolution.reason),
                spoken = rejectionMessage(resolution.reason),
                evidence = rejectionEvidence(resolution.reason),
            )

            is ToolResolution.Approved -> {
                if (resolution.needsBiometric) {
                    return ToolOutcome.Failed(
                        "biometric_required",
                        "Questa azione resta bloccata finché non è disponibile la conferma biometrica.",
                    )
                }
                if (resolution.needsConfirmation && !confirmed) {
                    return ToolOutcome.NeedsConfirmation(
                        call = call,
                        prompt = resolution.tool.confirmationPrompt(call.arguments)
                            ?: "Confermi: ${resolution.tool.description}?",
                    )
                }
                val tool = resolution.tool
                try {
                    when (val result = withTimeout(tool.timeoutMs) { tool.execute(call.arguments) }) {
                        is ToolResult.Success -> {
                            val spoken = result.output["spoken"]?.jsonPrimitive?.content
                                ?: result.output["result"]?.jsonPrimitive?.content
                                    ?.let { "Risultato: ${prettyNumber(it)}" }
                                ?: "Fatto."
                            Log.i(TAG, "tool_ok ${tool.name}")
                            ToolOutcome.Done(spoken, raw = result.output, evidence = result.evidence)
                        }
                        is ToolResult.Failure -> {
                            Log.w(TAG, "tool_fail ${tool.name} ${result.code}")
                            ToolOutcome.Failed(result.code, failureMessage(tool.name, result.code), evidence = result.evidence)
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    ToolOutcome.Failed(
                        "timeout", "L'operazione ha impiegato troppo tempo.",
                        evidence = StructuredToolResult.toolFailure(reasonCode = "timeout", retryable = true),
                    )
                } catch (e: CancellationException) {
                    // § PASSAGGIO 1 §17 — real (non-timeout) cancellation is NOT
                    // a tool failure: the generic `catch (e: Exception)` below
                    // would otherwise catch it too (`CancellationException` IS
                    // an `Exception`) and turn a user-cancelled turn into a
                    // spoken "non sono riuscito a completare l'operazione" —
                    // the exact class of bug `util/RunCancellable.kt` already
                    // documents elsewhere in this project. Propagate, per
                    // structured concurrency, never swallow it here.
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "tool_crash ${tool.name} ${e.javaClass.simpleName}")
                    ToolOutcome.Failed(
                        "crash", "Non sono riuscito a completare l'operazione.",
                        evidence = StructuredToolResult.toolFailure(reasonCode = "crash"),
                    )
                }
            }
        }
    }

    /** "25.0" -> "25", "2.5" -> "2,5" (spoken Italian decimals use a comma). */
    private fun prettyNumber(raw: String): String {
        val d = raw.toDoubleOrNull() ?: return raw
        return if (d == Math.floor(d) && !d.isInfinite()) {
            d.toLong().toString()
        } else {
            raw.trimEnd('0').trimEnd('.').replace('.', ',')
        }
    }

    private fun rejectionCode(r: ToolRejection): String = when (r) {
        is ToolRejection.Unknown -> "unknown_tool"
        is ToolRejection.InvalidArguments -> "invalid_arguments"
        is ToolRejection.NetworkRequiredButOffline -> "offline"
    }

    /**
     * § PASSAGGIO 1 — a rejection before execution is still a real, typed
     * outcome: offline is a known, retryable [ToolOutcomeStatus.DATA_UNAVAILABLE]
     * gap (retrying once online works), while an unknown tool/invalid
     * arguments are [ToolOutcomeStatus.TOOL_FAILURE] (retrying the identical
     * call would fail identically again).
     */
    private fun rejectionEvidence(r: ToolRejection): StructuredToolResult = when (r) {
        is ToolRejection.NetworkRequiredButOffline ->
            StructuredToolResult.dataUnavailable(reasonCode = "offline", retryable = true)
        is ToolRejection.Unknown, is ToolRejection.InvalidArguments ->
            StructuredToolResult.toolFailure(reasonCode = rejectionCode(r), retryable = false)
    }

    private fun rejectionMessage(r: ToolRejection): String = when (r) {
        is ToolRejection.Unknown -> "Non ho uno strumento per farlo."
        is ToolRejection.InvalidArguments -> "Non ho capito i dettagli: ${r.reason}."
        is ToolRejection.NetworkRequiredButOffline -> "Serve la rete e siamo offline."
    }

    private fun failureMessage(tool: String, code: String): String = when (code) {
        "no_vault" -> "Non ho un vault collegato dove salvare. Aprilo in Impostazioni › Memoria."
        "no_clock_app" -> "Non trovo l'app Orologio per farlo."
        "no_torch" -> "Questo telefono non espone la torcia."
        "app_unavailable" -> "Non trovo quell'app sul telefono."
        "settings_unavailable" -> "Questa schermata delle Impostazioni non è disponibile."
        "calendar_unavailable" -> "Non trovo un'app Calendario che possa preparare l'evento."
        "agenda_item_not_found" ->
            "Non trovo una sola attività aperta con quel nome nel calendario personale. Prova a essere più preciso."
        "dialer_unavailable" -> "Non trovo un'app Telefono che possa aprire il numero."
        "messaging_unavailable" -> "Non trovo un'app Messaggi che possa preparare l'SMS."
        "maps_unavailable" -> "Non trovo un'app di navigazione disponibile."
        "media_app_unavailable" -> "Non trovo un'app multimediale che possa riprodurlo."
        "notification_access_required" ->
            "Serve l'Accesso alle notifiche. Puoi abilitarlo in Impostazioni Android › Accesso notifiche."
        "notification_listener_unavailable" ->
            "L'accesso alle notifiche è abilitato ma non ancora pronto. Riapri JARVIS e riprova."
        "no_active_media" -> "Non c'è una riproduzione multimediale attiva da controllare."
        // § FASE 2A.5-bis — grounding obbligatorio: mai un dato inventato, mai
        // un silenzio; queste sono le uniche risposte oneste quando il meteo
        // reale o Health Connect non sono davvero raggiungibili in questo momento.
        "weather_unavailable" -> "Non riesco ad accedere ai dati meteo in questo momento."
        // § JARVIS Implementation Master Plan PASSAGGIO 7 — distinct from the
        // generic "unavailable" above: the user has turned the weather
        // capability off entirely (a setting, not a failure), and a genuine
        // provider/network failure (never presented as an empty/negative
        // forecast).
        "weather_disabled" -> "Il meteo non è attivo. Puoi accenderlo in Impostazioni › Meteo."
        "weather_source_failure" -> "Ho avuto un problema a leggere i dati meteo in questo momento."
        "health_unavailable" -> "Non riesco ad accedere a Health Connect in questo momento."
        "health_permission_missing" ->
            "Non ho ancora il permesso per leggere i dati di Health Connect. Puoi concederlo in Impostazioni."
        // § JARVIS Implementation Master Plan PASSAGGIO 5 — a genuine
        // read/sync exception (SOURCE_FAILURE), distinct from the generic
        // "unavailable" above and never presented as an empty result.
        "health_source_failure" -> "Ho avuto un problema a leggere i dati di Health Connect in questo momento."
        // § PASSAGGIO 5 — the requested date/range was never actually
        // queried/cached (DATA_UNAVAILABLE) — distinct from a genuinely
        // covered-but-empty result, which now answers honestly instead of
        // failing (see GetHealthSummaryTool's SUCCESS_EMPTY paths).
        "health_range_not_covered" -> "Non ho ancora sincronizzato i dati di Health Connect per quel periodo."
        // Retained only for any lingering caller of the old, now-unused
        // reason code — superseded by "health_range_not_covered" above and
        // the honest SUCCESS_EMPTY results GetHealthSummaryTool now returns
        // directly for a covered-but-empty query.
        "health_no_data" -> "Health Connect non ha ancora nessun dato reale per questo periodo."
        // § JARVIS Implementation Master Plan PASSAGGIO 4 — same honest,
        // deterministic pattern: a genuine local-storage read failure,
        // never silently reported as "no impegni".
        "agenda_unavailable" -> "Non riesco ad accedere all'agenda in questo momento."
        else -> "Non sono riuscito a completare: $tool."
    }

    private companion object {
        const val TAG = "JarvisTools"
    }
}
