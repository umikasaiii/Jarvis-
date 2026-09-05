package com.simone.jarvismobile.core.tools

import com.simone.jarvismobile.core.routing.ToolIntentGate

/**
 * Coarse-grained grouping of today's full tool registry (`ToolsModule`, 53
 * tools) by name — the stable identifier every [Tool] already exposes, never
 * its free-text [Tool.description]. Used only to decide which tools belong
 * in a given turn's prompt catalog (§ FASE 2A.2); it changes nothing about
 * how a tool is resolved or executed — [ToolRegistry] remains the sole
 * authority for that.
 */
enum class ToolFamily {
    TIME, DEVICE, AGENDA, MEMORY, KNOWLEDGE, ARCHIVE, SYSTEM_APP, COMMUNICATION, MEDIA, DRIVING, UTILITY,
    // § FASE 2A.5-bis — two families new tools grounding real runtime/personal
    // data (weather, Health Connect sleep/heart-rate) belong to; see
    // GROUNDED_FAMILIES below for why they, and several families above, are
    // never allowed to be answered from the model's own "knowledge".
    WEATHER, HEALTH,
    // § FASE 2A.8 RELEASE GATE C — real on-device metrics (RAM/storage/
    // Android version/model), distinct from DEVICE (flashlight/battery
    // hardware actions/state): a "how much RAM do I have" DATA_QUERY, never
    // answerable from the model's own guess about this specific phone.
    DEVICE_INFO,
}

/**
 * Families whose tools answer runtime/personal data that the model must
 * never fabricate (§ FASE 2A.5-bis §3, "grounding obbligatorio") — weather,
 * calendar/reminders/tasks, personal memory, the local archive (including
 * the shopping list), device/notification state, and health data. A turn
 * whose selected catalog includes one of these families is expected to
 * actually call a tool before answering; [EngineTurnDiagnostics] uses this
 * set (by name) to compute `groundingRequired`/`groundingSatisfied` without
 * a second, parallel classification.
 */
val GROUNDED_FAMILIES: Set<ToolFamily> = setOf(
    ToolFamily.WEATHER, ToolFamily.AGENDA, ToolFamily.MEMORY, ToolFamily.ARCHIVE,
    ToolFamily.HEALTH, ToolFamily.DEVICE, ToolFamily.SYSTEM_APP, ToolFamily.DEVICE_INFO,
)

/**
 * Deterministic, keyword-based selection of which registered tools are
 * plausibly relevant to one user request (§ FASE 2A.2 — "prima dell'LLM
 * determina deterministicamente quali tool/famiglie di tool sono
 * plausibilmente pertinenti"). Mirrors
 * [com.simone.jarvismobile.core.snapshot.RelevantContextSelector]'s "simple
 * deterministic rules, never a second LLM" philosophy — that selector
 * narrows the *personal-context snapshot* (time/place/agenda/...); this one
 * narrows the *tool catalog* instead, a different concern with no shared
 * state, so it is a new object rather than an extension of that one. Pure,
 * no I/O, no Android dependency — testable in isolation.
 *
 * [ToolIntentGate.shouldClassify] — the same deterministic gate the
 * fast-path classifier already uses to decide whether a request is
 * tool-shaped at all — is reused here as the first cut: a request with no
 * tool-shaped signal whatsoever (an ordinary "Ciao, come stai?") needs no
 * tool catalog in the prompt at all, never a second parallel gate.
 *
 * A tool name absent from [FAMILY_BY_TOOL_NAME] (e.g. a future tool added to
 * the registry before this table is updated) is always treated as relevant —
 * silently losing tool-calling capability for an unclassified tool would be
 * worse than an occasionally larger catalog, which the existing wire-level
 * truncation (`RemoteAiEngine.truncateSystemPromptForWire`) already guards
 * as a safety net, never the primary strategy here.
 */
object RelevantToolSelector {

    private val FAMILY_BY_TOOL_NAME: Map<String, ToolFamily> = buildMap {
        listOf("get_time", "time_until", "set_alarm", "set_timer").forEach { put(it, ToolFamily.TIME) }
        listOf("battery_status", "flashlight").forEach { put(it, ToolFamily.DEVICE) }
        listOf(
            "add_reminder", "add_task", "list_agenda", "query_agenda", "complete_agenda",
            "delete_agenda", "move_agenda", "rename_agenda", "update_agenda_notes", "create_calendar_event",
        ).forEach { put(it, ToolFamily.AGENDA) }
        listOf(
            "remember", "forget_memory", "update_memory", "list_memories", "move_memory", "search_memory",
        ).forEach { put(it, ToolFamily.MEMORY) }
        listOf(
            "search_knowledge", "search_documents", "read_document_context", "search_images", "search_vault",
        ).forEach { put(it, ToolFamily.KNOWLEDGE) }
        listOf(
            "create_archive_item", "read_archive_item", "update_archive_item", "delete_archive_item",
            "search_archive", "list_items", "create_list", "add_list_item", "update_list_item", "remove_list_item",
        ).forEach { put(it, ToolFamily.ARCHIVE) }
        listOf("open_app", "open_settings", "list_notifications").forEach { put(it, ToolFamily.SYSTEM_APP) }
        listOf("compose_sms", "prepare_call", "reply_message").forEach { put(it, ToolFamily.COMMUNICATION) }
        listOf("play_media", "media_control").forEach { put(it, ToolFamily.MEDIA) }
        listOf(
            "navigate", "start_driving_mode", "stop_driving_mode", "set_driving_navigation",
            "start_driving_route", "show_driving_panel", "hide_driving_panel",
        ).forEach { put(it, ToolFamily.DRIVING) }
        put("calculate", ToolFamily.UTILITY)
        // § FASE 2A.5-bis — capabilities that already exist in the app
        // (WeatherManager, HealthConnectManager) but were never exposed as
        // tools to the conversational engine, the real root cause behind
        // "Che tempo fa domani?"/"Quante ore ho dormito questa settimana?"
        // being answered from the model's own guess instead of real data.
        put("get_weather", ToolFamily.WEATHER)
        put("get_health_summary", ToolFamily.HEALTH)
        // § FASE 2A.8 RELEASE GATE C.
        put("get_device_info", ToolFamily.DEVICE_INFO)
    }

    private val TIME_KEYWORDS = setOf(
        "sveglia", "svegliami", "timer", "allarme", "che ore", "che ora", "orario",
        "quanto manca", "fra quanto", "tra quanto",
    )
    // § FASE 2A.6 — "luce"/"accendi"/"spegni" removed: they were the actual
    // root cause of "Accendi la luce della camera" being able to reach
    // `flashlight` at all (matched DEVICE via the bare word "luce", with no
    // real smart-home tool to distinguish room lighting from the phone's own
    // torch). "torcia"/"flash" are the words `CommandMatcher.TORCH_RE` (the
    // deterministic fast path) itself already requires for a real torch
    // command — kept here as the only DEVICE-selecting words for the same
    // reason, so the model's tool catalog draws the identical line the fast
    // path already draws deterministically. Bare "accendi"/"spegni" alone say
    // nothing about DEVICE specifically (they also mean turning on music, an
    // app, a light that isn't the torch, …) so they are gated by
    // [HomeControlDetector] instead (§ below), never by this family.
    private val DEVICE_KEYWORDS = setOf(
        "torcia", "flash", "batteria", "carica", "caricando", "percentuale",
    )
    private val AGENDA_KEYWORDS = setOf(
        "appuntamento", "appuntamenti", "impegno", "impegni", "agenda", "riunione", "riunioni",
        "promemoria", "evento", "eventi", "calendario", "scadenza", "scadenze", "attivita", "task",
    )
    // internal, not private: § FASE 2A.5 — shared verbatim with
    // `RelevantContextSelector` (a different concern, same "is this
    // memory-shaped language" signal) so the two selectors can never drift
    // out of sync on what counts as a memory-related request — one source
    // of truth instead of two keyword lists to keep aligned by hand.
    internal val MEMORY_KEYWORDS = setOf(
        "ricorda", "ricordami", "ricordati", "memoria", "appunto", "appunti", "dimentica", "annota",
    )
    private val KNOWLEDGE_KEYWORDS = setOf(
        "manuale", "guida a", "come si fa", "come funziona", "significa", "vault", "documento",
        "documenti", "wiki", "conoscenza",
    )
    private val ARCHIVE_KEYWORDS = setOf(
        "lista", "liste", "archivio", "nota", "note", "spesa", "da vedere", "elenco",
    )
    private val SYSTEM_APP_KEYWORDS = setOf("apri", "app", "impostazioni", "notifica", "notifiche")
    private val COMMUNICATION_KEYWORDS = setOf(
        "chiama", "telefona", "sms", "messaggio", "numero di telefono", "rispondi a",
    )
    private val MEDIA_KEYWORDS = setOf("musica", "riproduci", "play", "pausa", "brano", "spotify", "canzone")
    private val DRIVING_KEYWORDS = setOf(
        "naviga", "navigazione", "portami", "guidami", "percorso", "guido", "guidando",
        "strada", "parcheggio", "modalita guida", "traffico", "autostrada",
    )
    private val UTILITY_KEYWORDS = setOf("calcola", "quanto fa", "quanto e", "somma", "diviso", "moltiplica")
    // § FASE 2A.5-bis — deliberately general Italian phrasing for "what's the
    // weather" in any tense/form, not the literal test phrases from the bug
    // report (e.g. "che tempo fa domani" is covered by "che tempo"/"domani"
    // is NOT itself a weather keyword — "domani" alone is far too broad and
    // belongs to no family here).
    private val WEATHER_KEYWORDS = setOf(
        "meteo", "che tempo", "previsioni", "piove", "pioggia", "piovera", "nuvoloso", "sereno",
        "temperatura", "gradi fuori", "che caldo", "che freddo", "vento", "ombrello", "temporale",
    )
    private val HEALTH_KEYWORDS = setOf(
        "dormito", "dormire", "sonno", "ore di sonno", "battito", "bpm", "frequenza cardiaca",
        "salute", "health connect", "riposo notturno",
    )
    // § FASE 2A.8 RELEASE GATE C — deliberately QUANTITY-PHRASES, never the
    // bare noun alone: "Che differenza c'è tra RAM e VRAM?" (a KNOWLEDGE
    // question, answered by the model) must NOT match this family just
    // because it says "ram" — only a clear "how much do I have" framing does.
    // The bare-pronoun follow-up ("Quanta ne ho?") is deliberately NOT a
    // keyword here at all — it has no metric noun of its own to match on;
    // resolving it is `DeviceInfoFollowUp`'s job, one layer up.
    private val DEVICE_INFO_KEYWORDS = setOf(
        "quanta ram", "quanto ram", "ram ho", "ram disponibile", "ram libera", "ram del telefono",
        "quanto spazio", "spazio libero", "spazio di archiviazione", "memoria interna",
        "versione di android", "versione android", "che telefono ho", "modello del telefono", "modello di telefono",
    )

    private val FAMILY_KEYWORDS: Map<ToolFamily, Set<String>> = mapOf(
        ToolFamily.TIME to TIME_KEYWORDS,
        ToolFamily.DEVICE to DEVICE_KEYWORDS,
        ToolFamily.AGENDA to AGENDA_KEYWORDS,
        ToolFamily.MEMORY to MEMORY_KEYWORDS,
        ToolFamily.KNOWLEDGE to KNOWLEDGE_KEYWORDS,
        ToolFamily.ARCHIVE to ARCHIVE_KEYWORDS,
        ToolFamily.SYSTEM_APP to SYSTEM_APP_KEYWORDS,
        ToolFamily.COMMUNICATION to COMMUNICATION_KEYWORDS,
        ToolFamily.MEDIA to MEDIA_KEYWORDS,
        ToolFamily.DRIVING to DRIVING_KEYWORDS,
        ToolFamily.UTILITY to UTILITY_KEYWORDS,
        ToolFamily.WEATHER to WEATHER_KEYWORDS,
        ToolFamily.HEALTH to HEALTH_KEYWORDS,
        ToolFamily.DEVICE_INFO to DEVICE_INFO_KEYWORDS,
    )

    // § FASE 2A.6 §4 — one compiled word/phrase-boundary regex per keyword,
    // built once. Root cause fixed: `keywords.any(normalized::contains)` was
    // a raw substring test, so e.g. the SYSTEM_APP keyword "app" matched
    // *inside* "appuntamenti"/"appuntamento" (AGENDA's own words) — "Che
    // appuntamenti ho domani?" silently pulled in SYSTEM_APP tools that have
    // nothing to do with the request. `\b` works correctly around a
    // multi-word keyword too (e.g. "che ore"), matching only when it appears
    // as whole words, not as a fragment of a longer one. Multi-family
    // selection itself is unaffected and still intentional (§ "Considerando
    // come ho dormito e gli impegni di domani" must still match HEALTH+AGENDA
    // both) — only spurious *incidental* substring matches are removed.
    private val FAMILY_PATTERNS: Map<ToolFamily, List<Regex>> = FAMILY_KEYWORDS.mapValues { (_, keywords) ->
        keywords.map { Regex("\\b" + Regex.escape(it) + "\\b") }
    }

    /**
     * The [ToolFamily] values whose keyword set has a real word/phrase-
     * boundary match in [userText] — the *specific*, high-confidence signal,
     * as opposed to [select]'s conservative "ambiguous → full catalog"
     * fallback (which is not a specific match at all). §FASE 2A.6 §1/§2: this
     * is what a capability-first router and grounding enforcement should
     * treat as "the user specifically asked about this family" — the
     * ambiguous-fallback catalog must never be read as "every one of these
     * families was specifically requested."
     */
    fun matchedFamilies(userText: String): Set<ToolFamily> {
        val normalized = normalize(userText)
        return FAMILY_PATTERNS.filterValues { patterns -> patterns.any { it.containsMatchIn(normalized) } }.keys
    }

    /**
     * [availableTools] is `ToolRunner.available()`'s live (name, description)
     * pairs — the very same catalog the full prompt used to embed
     * unconditionally, never a second registry. Returns the subset plausibly
     * relevant to [userText], preserving [availableTools]' original order:
     *
     *  - One or more [ToolFamily] keyword sets match ([matchedFamilies]) →
     *    only tools in those families, plus any tool [FAMILY_BY_TOOL_NAME]
     *    does not yet classify (see the class doc comment on why those are
     *    never dropped). Checked FIRST, regardless of [ToolIntentGate] (§
     *    FASE 2A.5-bis root cause: a factual question like "Che tempo fa
     *    domani?" does not match [ToolIntentGate]'s action/device-question
     *    signals at all — it is a plain question, not a command — so gating
     *    on that check first, as an earlier phase did, silently starved
     *    every such family of any chance to be selected. A specific
     *    family-keyword match is a stronger, more targeted signal than the
     *    coarser intent gate, so it is checked independently instead of
     *    behind it).
     *  - No family matched, but [ToolIntentGate] says a tool is plausibly
     *    needed anyway (a genuinely ambiguous request, e.g. "Attivalo per
     *    favore") → every tool: the conservative fallback that never costs
     *    capability. The existing wire-level truncation is what keeps this
     *    within the protocol limit on that rarer path, not this function.
     *  - Neither matched → empty: a plain "Ciao, come stai?" needs no tool
     *    catalog.
     */
    fun select(availableTools: List<Pair<String, String>>, userText: String): List<Pair<String, String>> {
        val matched = matchedFamilies(userText)

        if (matched.isNotEmpty()) {
            return availableTools.filter { (name, _) ->
                val family = FAMILY_BY_TOOL_NAME[name]
                family == null || family in matched
            }
        }

        if (!ToolIntentGate.shouldClassify(userText)) return emptyList()

        return availableTools
    }

    /**
     * § FASE 2A.5 diagnostica richiesta esplicitamente ("tool family
     * selezionata") — privacy-safe by construction: a [ToolFamily] name is
     * never personal content, only a coarse capability label. `null` for a
     * tool [FAMILY_BY_TOOL_NAME] does not yet classify (see [select]'s doc
     * comment on why those are never dropped from a selection either).
     */
    fun familyOf(toolName: String): ToolFamily? = FAMILY_BY_TOOL_NAME[toolName]

    /** The distinct, non-null families represented in [selectedTools] — for diagnostics only. */
    fun familiesOf(selectedTools: List<Pair<String, String>>): Set<ToolFamily> =
        selectedTools.mapNotNull { (name, _) -> familyOf(name) }.toSet()

    private fun normalize(text: String): String = text.lowercase()
        .replace('à', 'a').replace('è', 'e').replace('é', 'e')
        .replace('ì', 'i').replace('ò', 'o').replace('ù', 'u')
}
