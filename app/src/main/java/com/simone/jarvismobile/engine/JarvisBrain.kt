package com.simone.jarvismobile.engine

import android.content.Context
import android.util.Log
import com.simone.jarvismobile.core.engine.BrainEvent
import com.simone.jarvismobile.core.engine.BrainReply
import com.simone.jarvismobile.core.engine.ReasoningMode
import com.simone.jarvismobile.core.engine.SentenceStream
import com.simone.jarvismobile.core.protocol.AssistantResponse
import com.simone.jarvismobile.core.protocol.ParseResult
import com.simone.jarvismobile.core.protocol.ResponseParser
import com.simone.jarvismobile.core.routing.ComplexityHeuristic
import com.simone.jarvismobile.llm.DEFAULT_GENERATION_TIMEOUT_SECONDS
import com.simone.jarvismobile.llm.LlmRouter
import com.simone.jarvismobile.llm.ModelSlot
import com.simone.jarvismobile.tools.ToolRunner
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `ConversationalJarvisEngine`'s reasoning core (spec §3). Turns one user turn
 * (plus whatever `ContextAssembler` has already assembled) into a single
 * structured [AssistantResponse] — never free text parsed with regex when a
 * structured contract is available, and never a direct call into
 * [ToolRunner]/`ToolRegistry`: [tools] is read here ONLY for
 * [ToolRunner.available] (the live tool catalog for the prompt), the same
 * read-only introspection `ProModeCoordinator` already does — actually
 * executing a tool call this brain returns is `ToolRouter`'s job, one layer
 * up in `ConversationalJarvisEngine`. That separation is what keeps "LLM
 * reasoning" and "tool execution" from ever sharing a call stack.
 *
 * The underlying [LlmRouter]/`LitertLmEngine` stack has no token-streaming
 * API (see `BrainEvent`'s doc comment) — [reply] is a single blocking call;
 * [replyEvents] is the honest, already-real post-hoc chunking of a completed
 * reply into incremental UI/TTS-ready events.
 *
 * **Deliberately never tries JARVIS Core (§ audit "chat ordinaria non
 * produce nessuna POST verso Core", Motore = Conversazionale case)**:
 * `jarvis-protocol/main v1.0.0`'s `JarvisCoreRequest` (see `CoreModels.kt`)
 * has no field to carry [systemPrompt] at all — only `text`/`context` — so
 * Core would never see [PROTOCOL_BLOCK] or the live tool catalog appended
 * below. Routing this brain's calls there would silently degrade every
 * `ParseResult.Valid`/`Repaired` response to `ParseResult.PlainText`
 * (technically safe — [ResponseParser] already handles it — but it would
 * quietly turn off tool-calling for any turn Core happened to answer,
 * without the user ever choosing that). `SessionCoordinator.tryRemoteChat`
 * (Motore = Classico) is the one place Core routing is safe today, because
 * that path is already a plain user-text-in/assistant-text-out exchange
 * with no JSON contract to lose — see its own doc comment. Extending the
 * protocol to carry a system/tool channel is out of scope here (§ "NON
 * modificare jarvis-protocol").
 */
@Singleton
class JarvisBrain @Inject constructor(
    @ApplicationContext private val context: Context,
    private val router: LlmRouter,
    private val tools: ToolRunner,
) {
    private val parser = ResponseParser()

    /** Resolves which model slot answers this turn, from the reasoning-mode setting. */
    fun resolveSlot(mode: ReasoningMode, text: String): ModelSlot = when (mode) {
        ReasoningMode.FAST -> ModelSlot.FAST
        ReasoningMode.DEEP -> if (router.hasAdvanced) ModelSlot.ADVANCED else ModelSlot.FAST
        ReasoningMode.AUTO -> router.selectSlot(ComplexityHeuristic.needsReasoning(text))
    }

    /**
     * One model turn. [userText] is the raw user message; [contextBlock] is
     * whatever `ContextAssembler` decided is worth including (memory,
     * pending-task state, prior tool results) — appended, never silently
     * merged into [userText], so a caller can log/inspect them separately.
     * Returns null only when the model itself is unavailable. [timeoutSeconds]
     * bounds this one native call — see [com.simone.jarvismobile.llm.LlmEngine.chat].
     * A follow-up "compose the final answer from these tool results" round only
     * needs to phrase already-known text, so callers should pass a shorter
     * budget there than the full [DEFAULT_GENERATION_TIMEOUT_SECONDS] a first,
     * real-reasoning round needs.
     */
    suspend fun reply(
        userText: String,
        contextBlock: String,
        slot: ModelSlot,
        timeoutSeconds: Long = DEFAULT_GENERATION_TIMEOUT_SECONDS,
    ): BrainReply {
        val prompt = if (contextBlock.isBlank()) userText else "$contextBlock\n\n$userText"
        val raw = router.chat(prompt, systemPrompt, slot, timeoutSeconds) ?: return BrainReply.Unavailable
        return when (val parsed = parser.parse(raw)) {
            is ParseResult.Valid -> BrainReply.Ready(parsed.response, parsedCleanly = true)
            is ParseResult.Repaired -> BrainReply.Ready(parsed.response, parsedCleanly = true)
            // Never treated as tool calls — ResponseParser's contract already
            // guarantees invalid/unrepairable JSON never reaches a caller as
            // anything but plain text.
            is ParseResult.PlainText -> BrainReply.Ready(
                AssistantResponse(assistantText = parsed.rawText.trim()),
                parsedCleanly = false,
            )
        }
    }

    /** Post-hoc sentence-chunked delivery of an already-produced [response]. */
    fun replyEvents(response: AssistantResponse): List<BrainEvent> = SentenceStream.from(response)

    /**
     * Built once per process, like `ProModeCoordinator.systemPrompt`: the same
     * persona asset plus a protocol block and the live tool catalog, so a tool
     * registered in `ToolsModule` is automatically available here too — with
     * no separate prompt to keep in sync. Distinct instructions from Pro
     * mode's block: this brain is expected to hold multi-turn state and ask
     * clarifying questions ([AssistantResponse.followUpExpected]), which Pro
     * mode's single-shot protocol never uses.
     */
    private val systemPrompt: String by lazy {
        val persona = runCatching {
            context.assets.open("prompts/jarvis_system_it.md").bufferedReader().use { it.readText() }
        }.getOrDefault("Sei JARVIS, un assistente personale offline. Rispondi in italiano, breve e naturale.")
        val catalog = tools.available().joinToString("\n") { (name, description) -> "- $name: $description" }
        Log.i(TAG, "conversational_prompt_built tools=${tools.available().size}")
        buildString {
            append(persona.trim())
            append("\n\n")
            append(PROTOCOL_BLOCK)
            append("\n\nStrumenti disponibili (usa ESATTAMENTE questi nomi, mai altri):\n")
            append(catalog)
        }
    }

    private companion object {
        const val TAG = "JarvisBrain"

        val PROTOCOL_BLOCK = """
            Sei in MODALITÀ CONVERSAZIONALE. Rispondi SEMPRE e SOLO con un
            oggetto JSON, in questa forma esatta, senza testo prima o dopo:
            {"assistant_text": "...", "tool_calls": [], "memory_proposal": null, "follow_up_expected": false}

            - "assistant_text": quello che vuoi dire a Simone, in italiano naturale.
              Se non hai bisogno di uno strumento, usa solo questo campo.
            - "tool_calls": se un'operazione richiede uno strumento, aggiungi un
              oggetto {"id": "un id qualsiasi", "name": "nome_esatto_dello_strumento",
              "arguments": {...}}. Gli argomenti vanno presi SOLO da quello che
              Simone ha detto o dal contesto fornito, mai inventati. Usa solo nomi
              di strumenti presenti nell'elenco qui sotto.
            - Se il contesto indica un'operazione già in corso (es. un impegno
              appena creato) e Simone la corregge o la completa senza rinominarla
              di nuovo ("anzi, alle 18"), usa l'id indicato nel contesto invece di
              chiedere a quale impegno si riferisce.
            - Se ti mancano informazioni per procedere e non puoi ragionevolmente
              assumerle, lascia tool_calls vuoto, fai la domanda in assistant_text
              e imposta "follow_up_expected": true.
            - Non lasciare mai "assistant_text" vuoto se tool_calls è vuoto.
            - Ignora "memory_proposal" (lascialo null): per salvare qualcosa nella
              memoria personale usa uno strumento di memoria, non questo campo.
        """.trimIndent()
    }
}
