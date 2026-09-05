package com.simone.jarvismobile.promode

import android.content.Context
import android.util.Log
import com.simone.jarvismobile.context.ContextEngine
import com.simone.jarvismobile.core.intent.IntentAliases
import com.simone.jarvismobile.core.protocol.AssistantResponse
import com.simone.jarvismobile.core.protocol.ParseResult
import com.simone.jarvismobile.core.protocol.ResponseParser
import com.simone.jarvismobile.core.protocol.ToolCall
import com.simone.jarvismobile.llm.LlmRouter
import com.simone.jarvismobile.tools.ToolOutcome
import com.simone.jarvismobile.tools.ToolRunner
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The whole PRO conversation loop (spec §2/§5): every ordinary message goes
 * STT/testo → here → Local LLM (+tools) → risposta, with none of NORMAL
 * mode's CommandMatcher/TurnPlanner/LlmIntentClassifier alias machinery in
 * between. [com.simone.jarvismobile.audio.SessionCoordinator] only ever calls
 * [handle] — it does not know [ResponseParser], [ToolCall] or any tool name.
 *
 * Reuses, unchanged: the tool-call JSON protocol ([ResponseParser]/[AssistantResponse],
 * `:core`, tested — built for this but never wired to the local model before
 * this class), [ToolRunner]/the tool registry (identical validation and
 * confirmation policy as NORMAL mode — see [handle]'s pending-confirmation
 * branch, which mirrors `SessionCoordinator.generateAtomicAnswer`'s), and
 * [LlmRouter] (always the FAST slot here — PRO does not yet replicate NORMAL
 * mode's fast/advanced routing; see class doc in the final report).
 *
 * No cloud path exists anywhere in this class: [router] only ever reaches a
 * local [com.simone.jarvismobile.llm.LitertLmEngine]. "No cloud fallback in
 * PRO" is therefore true by construction, not by a runtime check that could
 * be forgotten.
 */
@Singleton
class ProModeCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val router: LlmRouter,
    private val tools: ToolRunner,
    private val contextEngine: ContextEngine,
) {
    private val parser = ResponseParser()

    /** A tool call PRO itself is waiting on the user to confirm/deny. Independent of NORMAL mode's own field. */
    @Volatile private var pendingConfirmation: ToolCall? = null

    /**
     * § FASE 2A.6 §8 — same latent bug as [com.simone.jarvismobile.engine.ConversationalJarvisEngine]
     * fixed in FASE 2A.5-bis: this coordinator shares the same [ToolRunner]/
     * [ToolRegistry][com.simone.jarvismobile.core.tools.ToolRegistry] and its
     * own LLM sees the full live tool catalog, so it could in principle name
     * a network-requiring tool (`get_weather`) — hardcoding `online = false`
     * would make that call always fail even when genuinely connected.
     */
    private fun isOnline(): Boolean = contextEngine.state.value.networkAvailable == true

    /** Clears any PRO-specific mid-conversation state — called when Modalità Pro is switched off. */
    fun reset() {
        pendingConfirmation = null
    }

    /** Handles one PRO-mode turn (voice transcript or typed chat message) and returns what to say/show. */
    suspend fun handle(transcript: String): String {
        pendingConfirmation?.let { call ->
            pendingConfirmation = null
            if (IntentAliases.isCancellationOfPendingAction(transcript) || IntentAliases.isNegative(transcript)) {
                return "Va bene, annullato."
            }
            if (IntentAliases.isAffirmative(transcript)) {
                return when (val outcome = tools.run(call, online = isOnline(), confirmed = true)) {
                    is ToolOutcome.Done -> outcome.spoken
                    is ToolOutcome.Failed -> outcome.spoken
                    is ToolOutcome.NeedsConfirmation -> "Non posso eseguirlo senza conferma."
                }
            }
            // Neither yes nor no: drop the pending confirmation and treat this as a new message.
        }
        return runTurn(transcript)
    }

    private suspend fun runTurn(transcript: String): String {
        val raw = router.chat(transcript, systemPrompt, needsReasoning = false)
            ?: return "Il modello locale non è disponibile in questo momento: la Modalità Pro non ha un modo di rispondere senza di lui, e non uso servizi cloud."
        return when (val parsed = parser.parse(raw)) {
            is ParseResult.PlainText -> parsed.rawText.trim().ifBlank { "Non ho capito, puoi ripetere?" }
            is ParseResult.Valid -> afterModelResponse(parsed.response)
            is ParseResult.Repaired -> afterModelResponse(parsed.response)
        }
    }

    private suspend fun afterModelResponse(response: AssistantResponse): String {
        if (response.toolCalls.isEmpty()) {
            return response.assistantText.trim().ifBlank { "Fatto." }
        }
        val spokenResults = StringBuilder()
        for (call in response.toolCalls) {
            when (val outcome = tools.run(call, online = isOnline(), confirmed = false)) {
                is ToolOutcome.Done -> spokenResults.append(outcome.spoken).append('\n')
                is ToolOutcome.Failed -> spokenResults.append(outcome.spoken).append('\n')
                is ToolOutcome.NeedsConfirmation -> {
                    // Stop at the first tool that needs confirmation — any calls
                    // after it in the same model turn are not attempted, so
                    // nothing runs the user has not actually approved yet.
                    pendingConfirmation = outcome.call
                    return outcome.prompt
                }
            }
        }
        return composeFinalReply(spokenResults.toString().trim())
    }

    /**
     * One bounded follow-up call so the model can turn raw tool output into a
     * natural sentence — never a second round of tool_calls. If the model (or
     * the parse) gives nothing usable, the tools' own [ToolOutcome.spoken]
     * text is said as-is, which is already a complete, correct answer.
     */
    private suspend fun composeFinalReply(toolResultsSpoken: String): String {
        if (toolResultsSpoken.isBlank()) return "Fatto."
        val followUp = "Risultato degli strumenti eseguiti:\n$toolResultsSpoken\n\n" +
            "Componi ora, nel campo assistant_text, la risposta finale per Simone in linguaggio naturale, " +
            "basata SOLO su questo risultato. Lascia tool_calls vuoto."
        val raw = router.chat(followUp, systemPrompt, needsReasoning = false) ?: return toolResultsSpoken
        return when (val parsed = parser.parse(raw)) {
            is ParseResult.PlainText -> parsed.rawText.trim().ifBlank { toolResultsSpoken }
            is ParseResult.Valid -> parsed.response.assistantText.trim().ifBlank { toolResultsSpoken }
            is ParseResult.Repaired -> parsed.response.assistantText.trim().ifBlank { toolResultsSpoken }
        }
    }

    /**
     * Built once: the existing persona prompt plus the tool-call protocol
     * (docs/MODELS.md §"Response protocol") and the live tool catalog from
     * [ToolRunner.available] — so a tool added to [com.simone.jarvismobile.di.ToolsModule]
     * is automatically offered to PRO mode with no prompt edit needed.
     */
    private val systemPrompt: String by lazy {
        val persona = runCatching {
            context.assets.open("prompts/jarvis_system_it.md").bufferedReader().use { it.readText() }
        }.getOrDefault("Sei JARVIS, un assistente personale offline. Rispondi in italiano, breve e naturale.")
        val catalog = tools.available().joinToString("\n") { (name, description) -> "- $name: $description" }
        Log.i(TAG, "pro_mode_prompt_built tools=${tools.available().size}")
        buildString {
            append(persona.trim())
            append("\n\n")
            append(PROTOCOL_BLOCK)
            append("\n\nStrumenti disponibili (usa ESATTAMENTE questi nomi, mai altri):\n")
            append(catalog)
        }
    }

    private companion object {
        const val TAG = "JarvisProMode"

        val PROTOCOL_BLOCK = """
            Sei in MODALITÀ PRO. In questa modalità rispondi SEMPRE e SOLO con un
            oggetto JSON, in questa forma esatta, senza testo prima o dopo:
            {"assistant_text": "...", "tool_calls": [], "memory_proposal": null, "follow_up_expected": false}

            - "assistant_text": quello che vuoi dire a Simone, in italiano naturale.
              Se non hai bisogno di uno strumento, usa solo questo campo e lascia
              tool_calls vuoto.
            - "tool_calls": se un'operazione richiede uno strumento, aggiungi un
              oggetto {"id": "un id qualsiasi", "name": "nome_esatto_dello_strumento",
              "arguments": {...}}. Gli argomenti vanno presi SOLO da quello che
              Simone ha detto, mai inventati. Usa solo nomi di strumenti presenti
              nell'elenco qui sotto — se non esiste uno strumento adatto, dillo in
              assistant_text invece di inventare un nome.
            - Non lasciare mai "assistant_text" vuoto se tool_calls è vuoto.
            - Ignora "memory_proposal" (lascialo null): per salvare qualcosa nella
              memoria personale usa lo strumento "remember", non questo campo.
        """.trimIndent()
    }
}
