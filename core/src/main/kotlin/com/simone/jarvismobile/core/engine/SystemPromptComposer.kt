package com.simone.jarvismobile.core.engine

/**
 * § FASE 2A.3 — builds `JarvisBrain`'s per-turn system prompt text. Pure, no
 * I/O, no Android dependency — testable in isolation, unlike `JarvisBrain`
 * itself (Hilt/Context/`LlmRouter` in its constructor).
 *
 * Two tiers, decided by the caller from the same [com.simone.jarvismobile.llm.ModelSlot]
 * already used to pick FAST vs ADVANCED (§ "una rappresentazione compatta
 * specifica per REMOTE_FAST... il prompt completo/rich può restare
 * disponibile per BRAIN/local"):
 *
 *  - [Tier.FAST]: a short, compact persona plus a minimal tool protocol —
 *    only the JSON shape actually needed to call the selected tools, or a
 *    one-line "no tool needed" sentence when none were selected, plus a soft
 *    brevity instruction for that no-tool case (§ FASE 2A.1's measured
 *    bottleneck: a FAST turn's prompt/output was dominated by an ~8700-char
 *    system prompt and unbounded output, even for a plain "Ciao").
 *  - [Tier.RICH]: byte-for-byte the same persona + protocol block FASE
 *    2A.2 already used for every turn — unconstrained, no brevity
 *    instruction, so BRAIN/local never lose capability from this change
 *    ("non limitare BRAIN").
 *
 * The tool-catalog gating from FASE 2A.2 ([selectedTools] empty vs
 * non-empty, decided upstream by `RelevantToolSelector`) applies to BOTH
 * tiers: there is no reason even BRAIN should be told about the full ~53-tool
 * catalog for a turn that plainly needs none of them.
 *
 * `jarvis-protocol`'s 8000-char wire limit is enforced elsewhere
 * (`RemoteAiEngine.truncateSystemPromptForWire`, unchanged) as a safety net,
 * never the primary strategy here — the primary strategy is simply never
 * building an oversized prompt to begin with.
 */
object SystemPromptComposer {

    enum class Tier { FAST, RICH }

    /**
     * [richPersona] is `JarvisBrain`'s existing asset-loaded persona text
     * (trimmed, unmodified) — used verbatim for [Tier.RICH], ignored for
     * [Tier.FAST] (which uses [FAST_COMPACT_PERSONA] instead). [selectedTools]
     * is `RelevantToolSelector.select(...)`'s output, in its original order.
     */
    fun compose(tier: Tier, richPersona: String, selectedTools: List<Pair<String, String>>): String =
        when (tier) {
            Tier.FAST -> composeFast(selectedTools)
            Tier.RICH -> composeRich(richPersona, selectedTools)
        }

    private fun composeFast(selectedTools: List<Pair<String, String>>): String = buildString {
        append(FAST_COMPACT_PERSONA)
        append("\n\n")
        if (selectedTools.isEmpty()) {
            append(FAST_NO_TOOL_LINE)
            append(' ')
            append(FAST_BUDGET_LINE)
        } else {
            append(FAST_TOOL_PROTOCOL_HEADER)
            append(catalogLines(selectedTools))
        }
    }

    private fun composeRich(richPersona: String, selectedTools: List<Pair<String, String>>): String = buildString {
        append(richPersona.trim())
        append("\n\n")
        append(RICH_PROTOCOL_BLOCK)
        if (selectedTools.isEmpty()) {
            append("\n\n")
            append(RICH_NO_TOOL_ADDENDUM)
        } else {
            append("\n\n")
            append(RICH_TOOL_HEADER)
            append(catalogLines(selectedTools))
        }
    }

    private fun catalogLines(tools: List<Pair<String, String>>): String =
        tools.joinToString("\n") { (name, description) -> "- $name: $description" }

    // --- § FASE 2A.3 FAST tier — compact, kept deliberately short -----------------------

    /**
     * Keeps exactly the elements requested: JARVIS identity, Italian as the
     * default language, concise style, and — new, § root cause of "Rispondi
     * solo: TEST CORE" -> "Ciao!" found by this same phase's audit — an
     * explicit priority rule for the user's current literal instruction. The
     * shared rich persona asset (`jarvis_system_it.md`) tells the model to
     * "always answer in your own words, never just repeat back" — sound
     * advice for ordinary conversation, but with nothing carving out "unless
     * Simone is explicitly asking you to output specific text", a small FAST
     * model can read a literal-echo instruction as just another question to
     * paraphrase, producing a generic pleasantry instead of complying. This
     * rule is deliberately added ONLY here (the FAST compact persona), not
     * to the shared asset: the same failure has not been observed/measured
     * on Classic mode or BRAIN, and editing a persona several other
     * surfaces already depend on is a larger, riskier change than this
     * phase's explicit scope justifies.
     */
    const val FAST_COMPACT_PERSONA = "Sei JARVIS, l'assistente personale di Simone. Rispondi sempre in italiano, " +
        "con uno stile breve e diretto: la risposta viene spesso letta ad alta voce.\n" +
        "Segui l'istruzione più recente di Simone così com'è, anche alla lettera se te lo chiede " +
        "esplicitamente (es. \"rispondi solo con: ...\"): non sostituirla mai con una risposta generica " +
        "o di cortesia.\n" +
        "Non inventare mai fatti, dati o risultati di uno strumento: se non lo sai o non l'hai davvero " +
        "eseguito, dillo."

    const val FAST_NO_TOOL_LINE =
        "Nessuno strumento è necessario in questo turno: rispondi direttamente in testo semplice, senza JSON."

    /**
     * Only ever appended in the no-tool case (§ "Richieste FAST che
     * richiedono risposta più articolata possono ricevere un budget
     * maggiore" — a tool-bearing turn's `tool_calls` JSON and its
     * accompanying explanation are left unconstrained by this line). This is
     * a soft, prompt-level instruction, not a hard token cutoff: neither
     * `jarvis-protocol`'s `JarvisRequest` nor `jarvis-core`'s
     * `RequestOrchestrator` expose a `max_tokens`/`num_predict` field on the
     * wire today (confirmed by reading both repos in this phase's audit) —
     * adding one would mean touching the protocol and Core, explicitly out
     * of scope this round. A soft instruction has one honest advantage over
     * a hard cutoff it cannot yet have: it can never truncate output
     * mid-JSON, because it never truncates at all.
     */
    const val FAST_BUDGET_LINE =
        "Sii sintetico: 1-2 frasi brevi (circa 40 parole), a meno che Simone chieda esplicitamente più dettaglio."

    const val FAST_TOOL_PROTOCOL_HEADER = "Se ti serve una di queste operazioni, rispondi in puro JSON " +
        "(nessun testo fuori dalle graffe): " +
        "{\"assistant_text\":\"...\",\"tool_calls\":[{\"id\":\"1\",\"name\":\"NOME_ESATTO\",\"arguments\":{...}}]}. " +
        "Usa solo i nomi esatti elencati sotto; prendi gli argomenti solo da quanto detto da Simone, mai " +
        "inventati. Se non ti serve alcuno strumento, rispondi in testo semplice, senza JSON.\n\n" +
        "Strumenti disponibili:\n"

    // --- RICH tier — byte-for-byte what FASE 2A.2 already sent ----------------------------

    const val RICH_NO_TOOL_ADDENDUM =
        "Nessuno strumento è necessario per questa richiesta: lascia \"tool_calls\" vuoto."

    const val RICH_TOOL_HEADER = "Strumenti disponibili (usa ESATTAMENTE questi nomi, mai altri):\n"

    val RICH_PROTOCOL_BLOCK = """
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
