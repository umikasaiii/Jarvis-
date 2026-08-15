package com.simone.jarvismobile.tools

import com.simone.jarvismobile.core.agenda.ItalianDateTimeParser
import com.simone.jarvismobile.core.agenda.WhenParsed
import com.simone.jarvismobile.core.protocol.ToolCall
import com.simone.jarvismobile.core.memory.MemoryKind
import com.simone.jarvismobile.core.memory.MemoryStructure
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDateTime
import java.util.UUID

/** What the matcher made of an utterance. */
sealed interface Match {
    /** A tool to run right away. */
    data class Run(val call: ToolCall) : Match

    /**
     * Recognised the intent but a detail is missing. JARVIS asks [question] and
     * remembers [tool] + [missing] (plus anything already understood in
     * [partial]), so the user's next reply completes the action instead of
     * starting over.
     */
    data class Ask(
        val question: String,
        val tool: String,
        val missing: String,
        val partial: Map<String, String> = emptyMap(),
    ) : Match
}

/**
 * Maps plain Italian utterances to tool calls **deterministically**, before the
 * LLM is consulted.
 *
 * Why: a small on-device model is not reliable at emitting strict JSON tool
 * calls, and a mis-parsed command is worse than no command. These patterns are
 * explicit, testable and instant — the model is still used for everything that
 * isn't a recognised command, and it never gains the ability to call anything
 * outside the registry (docs/SECURITY.md §15).
 *
 * Matching is intentionally tolerant: polite wrappers ("puoi …", "potresti …",
 * "per favore") are stripped, and verbs are matched by stem so both
 * "accendi la torcia" and "puoi accendere la torcia" work.
 */
object CommandMatcher {

    private fun call(name: String, vararg args: Pair<String, String>): Match.Run =
        Match.Run(
            ToolCall(
                id = UUID.randomUUID().toString(),
                name = name,
                arguments = JsonObject(args.associate { it.first to JsonPrimitive(it.second) }),
                requiresConfirmation = false,
            ),
        )

    /**
     * Returns a [Match] for [utterance], or null when it isn't a command.
     * [recentContext] is used only to resolve narrow follow-ups such as
     * "È in carica adesso?" after a battery answer; it never supplies arguments
     * for write actions.
     */
    fun match(
        utterance: String,
        now: LocalDateTime = LocalDateTime.now(),
        recentContext: String? = null,
    ): Match? {
        val raw = utterance.trim()
        val t = normalize(raw)

        // --- Modalità Guida (checked first: "mostra Spotify"/"mostra dettagli
        // messaggi" would otherwise be swallowed by the generic app-open or
        // notification-read patterns below). ------------------------------
        drivingModeCall(raw)?.let { return it }
        drivingNavigationCall(raw)?.let { return it }
        drivingPanelCall(raw)?.let { return it }
        replyMessageCall(raw)?.let { return it }

        // --- Controlled Android surfaces and drafts ---------------------
        // These are checked before conversational intents. Arguments always
        // come from the user's own words; missing details become a follow-up
        // question instead of an inferred phone number, destination or title.
        settingsCall(raw)?.let { return it }
        // A task aimed at a named list or flagged "speciale" is a Google-Tasks
        // style task, not a calendar event — intercept before the calendar rule,
        // which would otherwise swallow "aggiungi (un')attività …".
        taskCreateCall(raw, now)?.let { return it }
        calendarEventCall(raw, now)?.let { return it }
        smsDraftCall(raw)?.let { return it }
        dialDraftCall(raw)?.let { return it }
        mediaControlCall(raw)?.let { return it }
        notificationCall(raw)?.let { return it }
        searchVaultCall(raw)?.let { return it }
        navigationCall(raw)?.let { return it }
        playMediaCall(raw)?.let { return it }
        appCall(raw)?.let { return it }

        // --- "quanto manca alle 16?" --------------------------------------
        // Before get_time: this is clock arithmetic, and arithmetic on time is
        // exactly what a small model gets wrong.
        if (TIME_UNTIL_RE.containsMatchIn(t)) {
            timeUntilCall(raw, now)?.let { return it }
        }

        // --- Time / date -------------------------------------------------
        if (TIME_RE.containsMatchIn(t)) return call("get_time")

        // --- Battery -----------------------------------------------------
        // Do not trigger on every mention of "batteria": "la batteria si sta
        // caricando" is a statement, not a request. A subject-less follow-up is
        // accepted only when the recent conversation was explicitly about the
        // phone battery.
        val batteryContext = recentContext?.let(::normalize)?.let {
            BATTERY_CONTEXT_RE.containsMatchIn(it)
        } == true
        if (
            BATTERY_REQUEST_RE.containsMatchIn(t) ||
            (
                (batteryContext || BATTERY_CONTEXT_RE.containsMatchIn(t)) &&
                    CHARGING_FOLLOW_UP_RE.containsMatchIn(t) &&
                    (raw.trimEnd().endsWith('?') || FOLLOW_UP_REQUEST_RE.containsMatchIn(t))
                )
        ) {
            // "Quanta batteria ho?" wants a number. The charging state is extra
            // information, so it is only reported when the words asked for it —
            // either directly or as a follow-up like "e in carica?".
            return call(
                "battery_status",
                "charging_asked" to CHARGING_FOLLOW_UP_RE.containsMatchIn(t).toString(),
            )
        }

        // --- Flashlight --------------------------------------------------
        if (TORCH_RE.containsMatchIn(t)) {
            return if (TORCH_OFF_RE.containsMatchIn(t)) {
                call("flashlight", "on" to "false")
            } else {
                call("flashlight", "on" to "true")
            }
        }

        // --- Timer -------------------------------------------------------
        if (TIMER_WORD_RE.containsMatchIn(t) || COUNTDOWN_RE.containsMatchIn(t)) {
            ItalianNumbers.duration(t)?.let {
                return call("set_timer", "seconds" to it.toString())
            }
            return Match.Ask("Per quanto tempo?", "set_timer", "seconds")
        }

        // --- Alarm -------------------------------------------------------
        if (ALARM_WORD_RE.containsMatchIn(t)) {
            ItalianNumbers.clockTime(t)?.let { (h, m) ->
                return call("set_alarm", "hour" to h.toString(), "minute" to m.toString())
            }
            return Match.Ask("A che ora?", "set_alarm", "time")
        }

        // --- Finish a personal-calendar task ----------------------------
        completeAgendaCall(raw)?.let { return it }

        // --- What's on the calendar ---------------------------------------
        if (AGENDA_RE.containsMatchIn(t)) return agendaCall(raw, now)

        // --- Recall the free-text notes -----------------------------------
        if (RECALL_RE.containsMatchIn(t)) return call("list_memories")

        // --- Re-file a saved note ("sposta l'appunto sulla moto in Lavoro") --
        // Before the edit rule, which would otherwise read "… in Lavoro" as a
        // rewrite of the note's text.
        moveMemoryCall(raw)?.let { return it }

        // --- Edit a saved memory ("cambia l'appunto X in Y") --------------
        updateMemoryCall(raw)?.let { return it }

        // --- Delete a saved memory ("dimentica …") ------------------------
        forgetMemoryCall(raw)?.let { return it }

        // --- Remember (checked before the calculator) ---------------------
        rememberContent(raw)?.let { note ->
            val kind = MemoryStructure.classify(note)
            val memoryOnly = MEMORY_ONLY_RE.containsMatchIn(t) || kind == MemoryKind.TEMPORARY
            return if (!hasTimeReference(note) && memoryOnly) {
                call("remember", "text" to note, "kind" to kind.name)
            } else {
                reminderCall(note, now)
            }
        }
        if (BARE_REMEMBER_RE.matches(t)) {
            return Match.Ask("Cosa vuoi che ricordi?", "remember", "text")
        }

        // --- Calculator ---------------------------------------------------
        arithmetic(raw)?.let { return call("calculate", "expression" to it) }

        return null
    }

    /**
     * Extracts an arithmetic expression from the user's OWN words.
     *
     * This is deliberately never delegated to the model: asked to echo "5 * 2"
     * a small model may write "7 * 2", silently changing the question. Digits
     * must come from the user, so the answer is to the sum actually asked.
     * Returns null when there is nothing safely computable.
     */
    fun arithmetic(raw: String): String? {
        var t = raw.lowercase()
            .replace('à', 'a').replace('è', 'e').replace('é', 'e')
            .replace('ì', 'i').replace('ò', 'o').replace('ù', 'u')
            .trim().trim('?', '!', '.', ' ')

        // Drop a leading "quanto fa" / "calcola" if present.
        t = t.replace(Regex("""^(calcola|quanto fa|quant'?e|fammi il calcolo di)\s+"""), "")

        // Spoken operators → symbols (word boundaries only, so "percento" is safe).
        t = t
            .replace(Regex("""\bper\b"""), "*")
            .replace(Regex("""\bx\b"""), "*")
            .replace(Regex("""\bdiviso\b"""), "/")
            .replace(Regex("""\bpiu\b"""), "+")
            .replace(Regex("""\bmeno\b"""), "-")
            .replace("×", "*").replace("÷", "/").replace(",", ".")

        // Keep only characters that belong in an expression.
        val expr = t.filter { it.isDigit() || it in "+-*/(). " }.trim()

        // Must contain a real operation between numbers, not just a stray digit.
        if (!Regex("""\d\s*[+\-*/]\s*\(?\s*\d""").containsMatchIn(expr)) return null
        if (!expr.all { it.isDigit() || it in "+-*/(). " }) return null
        return expr
    }

    /**
     * "aggiungi alla lista Lavoro comprare il pane", "attività speciale chiamare
     * Mario", "nella lista Spesa il latte domani" → a Google-Tasks-style task.
     * Fires only when the user names a list or asks for a special/starred task,
     * so ordinary reminders and calendar phrasing are left untouched. The list
     * name and title come from the user's own words; a due date is optional.
     */
    fun taskCreateCall(raw: String, now: LocalDateTime = LocalDateTime.now()): Match? {
        val norm = normalize(raw)
        val listName = TASK_LIST_RE.find(norm)?.groupValues?.getOrNull(1)
            ?.trim()?.takeIf { it.isNotEmpty() }
        val starred = TASK_STAR_RE.containsMatchIn(norm)
        if (listName == null && !starred) return null

        val parsed = ItalianDateTimeParser.parse(raw, now)
        var body = normalize(parsed.remainder)
        body = TASK_LIST_RE.replace(body, " ")
        body = TASK_STAR_RE.replace(body, " ")
        body = TASK_LEAD_RE.replace(body, " ")
        body = body.replace(Regex("""\s+"""), " ").trim().trim(',', ':', '-', '.', ' ')
            .replaceFirstChar { it.uppercase() }

        val list = listName?.replaceFirstChar { it.uppercase() }
        val partial = buildMap {
            list?.let { put("list", it) }
            if (starred) put("starred", "true")
        }
        if (body.length < 2) {
            return Match.Ask("Quale attività aggiungo?", "add_task", "text", partial)
        }
        val args = buildList {
            add("text" to body)
            list?.let { add("list" to it) }
            if (starred) add("starred" to "true")
            // Keep a date only when the user actually named a day.
            if (parsed.date != null && (parsed.dateExplicit || parsed.time != null)) {
                add("date" to parsed.date.toString())
                parsed.time?.let { add("time" to "%02d:%02d".format(it.hour, it.minute)) }
            }
        }
        return call("add_task", *args.toTypedArray())
    }

    /** "apri Spotify" / "apri il calendario". Only fixed aliases are accepted. */
    fun appCall(raw: String): Match? {
        val t = normalize(raw)
        val found = OPEN_APP_RE.matchEntire(t)?.groupValues?.getOrNull(1)
            ?.trim()?.removePrefix("l'")?.removePrefix("il ")?.removePrefix("la ")
            ?: return null
        val app = normalizeApp(found)
        return if (app in SUPPORTED_APPS) call("open_app", "app" to app) else null
    }

    /** "apri le impostazioni Bluetooth". The tool itself also enforces the allowlist. */
    fun settingsCall(raw: String): Match? {
        val t = normalize(raw)
        if (!SETTINGS_REQUEST_RE.containsMatchIn(t)) return null
        val area = SETTINGS_AREAS.entries.firstOrNull { (_, aliases) ->
            aliases.any { alias -> Regex("""\b${Regex.escape(alias)}\b""").containsMatchIn(t) }
        }?.key ?: "generali"
        return call("open_settings", "area" to area)
    }

    /**
     * Files events in JARVIS's personal calendar. Only an explicit request for
     * Google/the phone calendar opens an external editable draft.
     */
    fun calendarEventCall(raw: String, now: LocalDateTime = LocalDateTime.now()): Match? {
        val t = normalize(raw)
        if (!CALENDAR_WRITE_RE.containsMatchIn(t)) return null
        val parsed = ItalianDateTimeParser.parse(raw, now)
        val title = cleanCalendarTitle(parsed.remainder)
        val external = CALENDAR_EXPORT_RE.containsMatchIn(t)
        val tool = if (external) "create_calendar_event" else "add_reminder"
        val titleField = if (external) "title" else "text"
        val partial = buildMap {
            if (title.isNotBlank()) put(titleField, title)
            parsed.date?.let { put("date", it.toString()) }
            parsed.time?.let { put("time", "%02d:%02d".format(it.hour, it.minute)) }
        }
        if (title.isBlank()) {
            return Match.Ask("Qual è il titolo?", tool, titleField, partial)
        }
        if (parsed.date == null || !parsed.dateExplicit) {
            return Match.Ask(
                "Per quale giorno devo segnare “$title”?",
                tool,
                "when",
                partial,
            )
        }
        return call(tool, *partial.entries.map { it.key to it.value }.toTypedArray())
    }

    /** Marks one open activity as done, using its own words as the lookup key. */
    fun completeAgendaCall(raw: String): Match? {
        val t = normalize(raw)
        if (COMPLETE_AGENDA_BARE_RE.matches(t)) {
            return Match.Ask("Quale attività devo segnare come completata?", "complete_agenda", "text")
        }
        val match = COMPLETE_AGENDA_RE.matchEntire(t) ?: return null
        val target = match.groupValues.drop(1).firstOrNull(String::isNotBlank)
            .orEmpty()
            .replace(AGENDA_TARGET_PREFIX_RE, "")
            .trim(' ', '.', '?', '!', ':')
        return if (target.length >= 2) {
            call("complete_agenda", "text" to target)
        } else {
            Match.Ask("Quale attività devo segnare come completata?", "complete_agenda", "text")
        }
    }

    /** "chiama 333..." opens only ACTION_DIAL; contact-name guessing is forbidden. */
    fun dialDraftCall(raw: String): Match? {
        val t = normalize(raw)
        if (!DIAL_REQUEST_RE.containsMatchIn(t)) return null
        if (Regex("""\b(?:tra|fra)\b""").containsMatchIn(t) && ItalianNumbers.duration(t) != null) {
            return null // "chiamami tra 10 minuti" is a countdown, not a phone call.
        }
        val number = extractPhone(raw)
        return if (number != null) {
            call("prepare_call", "number" to number)
        } else {
            Match.Ask("Quale numero devo preparare nel dialer?", "prepare_call", "number")
        }
    }

    /** "prepara un SMS al 333...: arrivo" creates an editable draft only. */
    fun smsDraftCall(raw: String): Match? {
        val t = normalize(raw)
        if (!SMS_REQUEST_RE.containsMatchIn(t)) return null
        val number = extractPhone(raw)
        val body = extractSmsBody(raw, number)
        val partial = buildMap {
            number?.let { put("number", it) }
            body?.let { put("body", it) }
        }
        return when {
            number == null -> Match.Ask("A quale numero preparo l'SMS?", "compose_sms", "number", partial)
            body.isNullOrBlank() -> Match.Ask("Quale testo devo inserire nell'SMS?", "compose_sms", "body", partial)
            else -> call("compose_sms", "number" to number, "body" to body)
        }
    }

    /** "attiva/chiudi/termina modalità guida (o moto)". */
    fun drivingModeCall(raw: String): Match? {
        val t = normalize(raw)
        if (DRIVING_MODE_START_RE.containsMatchIn(t)) return call("start_driving_mode")
        if (DRIVING_MODE_STOP_RE.containsMatchIn(t)) return call("stop_driving_mode")
        return null
    }

    /** "imposta navigazione per X" (prepares only) / "avvia percorso" (starts turn-by-turn). */
    fun drivingNavigationCall(raw: String): Match? {
        val t = normalize(raw)
        DRIVING_NAV_SET_RE.find(t)?.let { m ->
            val destination = m.groupValues[1].trim().trim('.', '?', '!')
            return if (destination.length >= 2) {
                call("set_driving_navigation", "destination" to destination)
            } else {
                Match.Ask("Verso quale destinazione?", "set_driving_navigation", "destination")
            }
        }
        if (DRIVING_NAV_START_RE.matches(t)) return call("start_driving_route")
        return null
    }

    /** Expand/collapse the messages or Spotify panel inside Modalità Guida. */
    fun drivingPanelCall(raw: String): Match? {
        val t = normalize(raw)
        if (DRIVING_PANEL_MESSAGES_SHOW_RE.containsMatchIn(t)) return call("show_driving_panel", "panel" to "messages")
        if (DRIVING_PANEL_MESSAGES_HIDE_RE.containsMatchIn(t)) return call("hide_driving_panel")
        if (DRIVING_PANEL_MEDIA_SHOW_RE.containsMatchIn(t)) return call("show_driving_panel", "panel" to "media")
        if (DRIVING_PANEL_MEDIA_HIDE_RE.containsMatchIn(t)) return call("hide_driving_panel")
        return null
    }

    /** "rispondi a Marco: arrivo tra dieci minuti" — the reply text keeps its original case. */
    fun replyMessageCall(raw: String): Match? {
        val m = REPLY_MESSAGE_RE.find(raw.trim()) ?: return null
        val name = m.groupValues[1].trim().trim('.', ',', ' ', '«', '»', '"')
        val text = m.groupValues[2].trim()
        if (name.length < 2 || text.isEmpty()) return null
        return call("reply_message", "name" to name, "text" to text)
    }

    /** "portami a Piazza Navona" hands an explicit destination to the map app. */
    fun navigationCall(raw: String): Match? {
        val t = normalize(raw)
        val match = NAVIGATION_RE.find(t) ?: return null
        val destination = match.groupValues.drop(1).firstOrNull(String::isNotBlank)
            .orEmpty().trim().trim('.', '?', '!')
        return if (destination.length >= 2) {
            call("navigate", "destination" to destination)
        } else {
            Match.Ask("Verso quale destinazione?", "navigate", "destination")
        }
    }

    /** Play/pause/skip the currently active media session. */
    fun mediaControlCall(raw: String): Match? {
        val t = normalize(raw)
        val action = when {
            MEDIA_PAUSE_RE.containsMatchIn(t) -> "pause"
            MEDIA_NEXT_RE.containsMatchIn(t) -> "next"
            MEDIA_PREVIOUS_RE.containsMatchIn(t) -> "previous"
            MEDIA_RESUME_RE.containsMatchIn(t) -> "play"
            else -> return null
        }
        return call("media_control", "action" to action)
    }

    /** Search/play request routed through the platform media-search intent. */
    fun playMediaCall(raw: String): Match? {
        val t = normalize(raw)
        val match = PLAY_MEDIA_RE.find(t) ?: return null
        val query = match.groupValues.drop(1).firstOrNull(String::isNotBlank)
            .orEmpty().trim().trim('.', '?', '!')
        return if (query.length >= 2) {
            call("play_media", "query" to query)
        } else {
            Match.Ask("Cosa vuoi ascoltare?", "play_media", "query")
        }
    }

    /** Active notification access always remains confirmation-gated by policy. */
    fun notificationCall(raw: String): Match? {
        val t = normalize(raw)
        if (!NOTIFICATION_READ_RE.containsMatchIn(t)) return null
        val app = NOTIFICATION_APP_RE.find(t)?.groupValues?.getOrNull(1)
            ?.trim()?.takeIf { it.length >= 2 }
        return if (app == null) call("list_notifications") else call("list_notifications", "app" to app)
    }

    /** Searches only the SAF vault explicitly selected by the user. */
    fun searchVaultCall(raw: String): Match? {
        val t = normalize(raw)
        val match = VAULT_SEARCH_RE.find(t) ?: return null
        val query = match.groupValues.getOrNull(1).orEmpty().trim().trim('.', '?', '!')
        return if (query.length >= 2) {
            call("search_vault", "query" to query)
        } else {
            Match.Ask("Cosa devo cercare nel vault?", "search_vault", "query")
        }
    }

    /** Public so callers can reuse the reminder-body extraction. */
    fun rememberBody(raw: String): String? = rememberContent(raw)

    /**
     * "sposta l'appunto sulla moto in Lavoro" → move_memory.
     *
     * Requires an explicit note cue ("l'appunto", "la nota", "il memo"), so a
     * planner reschedule is never mistaken for a re-filing. When the note itself
     * isn't named ("sposta questa nota in Lavoro") JARVIS asks which one rather
     * than picking a note on its own.
     */
    fun moveMemoryCall(raw: String): Match? {
        val m = MOVE_MEMORY_RE.find(normalize(raw)) ?: return null
        val needle = m.groupValues[1].trim()
            .replace(Regex("""^(?:su\w*|di|del|dello|della|che)\s+"""), "")
            .trim('.', ',', ' ', '\'', '«', '»', '"')
        val category = m.groupValues[2].trim()
            .replace(Regex("""^(?:la\s+)?categoria\s+|^(?:la\s+)?cartella\s+"""), "")
            .trim('.', ',', ' ', '«', '»', '"')
        if (category.length < 2) return null
        if (needle.length < 2) {
            return Match.Ask(
                "Quale appunto devo spostare in $category?",
                "move_memory",
                "text",
                mapOf("category" to category),
            )
        }
        return call("move_memory", "text" to needle, "category" to category)
    }

    /** "cambia l'appunto sul gelato in mi piace il cioccolato" → update_memory. */
    fun updateMemoryCall(raw: String): Match? {
        // "modifica X in Y" is shaped the same whether X is a note or a calendar
        // entry. When the sentence names a planner item ("l'appuntamento dal
        // dentista"), leave it to the agenda path — otherwise a rename of an
        // appointment would silently edit a note instead.
        if (PLANNER_NOUN_RE.containsMatchIn(normalize(raw))) return null
        val m = UPDATE_MEMORY_RE.find(raw.trim()) ?: return null
        val old = m.groupValues[1].trim().trim('.', ',', ' ', '«', '»', '"')
        val new = m.groupValues[2].trim().trim('.', ',', ' ', '«', '»', '"')
        if (old.length < 2 || new.length < 2) return null
        return call("update_memory", "old" to old, "new" to new)
    }

    /** "dimentica che mi piace il ketchup", "elimina l'appunto sul gelato". */
    fun forgetMemoryCall(raw: String): Match? {
        val body = forgetBody(raw) ?: return null
        return call("forget_memory", "text" to body)
    }

    private fun forgetBody(raw: String): String? {
        val m = FORGET_MEMORY_RE.find(raw.trim()) ?: return null
        val body = (m.groupValues[1].ifBlank { m.groupValues[2] }).trim().trim('.', ',', ' ', '«', '»', '"')
        return body.takeIf { it.length >= 2 }
    }

    /**
     * Turns a reminder body into an agenda entry.
     *
     * The date is parsed OUT of the sentence and stored as a real field, so
     * "domani" becomes a day on the calendar instead of a word inside the
     * description. Without a day there is nothing to file (and nothing to notify
     * about later), so JARVIS asks instead of guessing.
     */
    fun reminderCall(note: String, now: LocalDateTime = LocalDateTime.now()): Match {
        val w = ItalianDateTimeParser.parse(note, now)
        val title = w.remainder.ifBlank { note }.trim()
        val date = w.date
        return if (date != null && (w.dateExplicit || w.time != null)) {
            val args = buildList {
                add("text" to title)
                add("date" to date.toString())
                w.time?.let { add("time" to "%02d:%02d".format(it.hour, it.minute)) }
            }
            call("add_reminder", *args.toTypedArray())
        } else {
            Match.Ask("Quando?", "add_reminder", "when", mapOf("text" to title))
        }
    }

    /**
     * Completes a reminder once the user has answered "Quando?". Accepts an
     * explicit refusal ("nessuna data") by falling back to a plain note.
     */
    fun reminderFromAnswer(
        text: String,
        answer: String,
        now: LocalDateTime = LocalDateTime.now(),
    ): Match? {
        val reply = answer.trim()
        if (text.isBlank()) return null
        if (NO_DATE_RE.containsMatchIn(normalize(reply))) {
            return call("remember", "text" to text)
        }
        val w = ItalianDateTimeParser.parse(reply, now)
        val date = w.date ?: return null
        val args = buildList {
            add("text" to text)
            add("date" to date.toString())
            w.time?.let { add("time" to "%02d:%02d".format(it.hour, it.minute)) }
        }
        return call("add_reminder", *args.toTypedArray())
    }

    /** "cosa devo fare oggi pomeriggio" → list_agenda restricted to that window. */
    fun agendaCall(raw: String, now: LocalDateTime = LocalDateTime.now()): Match {
        val w: WhenParsed = ItalianDateTimeParser.parse(raw, now)
        val args = buildList {
            if (w.dateExplicit && w.date != null) add("day" to w.date.toString())
            w.period?.let { add("period" to it.name) }
        }
        return call("list_agenda", *args.toTypedArray())
    }

    /** "quanto manca alle 16?" → time_until with the hour taken from the user. */
    fun timeUntilCall(raw: String, now: LocalDateTime = LocalDateTime.now()): Match? {
        val w = ItalianDateTimeParser.parse(raw, now)
        val time = w.time
        if (time != null) {
            val args = buildList {
                add("hour" to time.hour.toString())
                add("minute" to time.minute.toString())
                // Only pin a date when the user actually named a day; otherwise
                // "quanto manca alle 16" means the NEXT 16:00.
                if (w.dateExplicit && w.date != null) add("date" to w.date.toString())
            }
            return call("time_until", *args.toTypedArray())
        }
        if (w.dateExplicit && w.date != null) {
            return call("time_until", "date" to w.date.toString())
        }
        return null
    }

    /**
     * Strips accents and polite wrappers so one pattern covers many phrasings.
     * "Puoi accendere la torcia?" -> "accendere la torcia"
     */
    fun normalize(s: String): String {
        var t = s.lowercase()
            .replace('à', 'a').replace('è', 'e').replace('é', 'e')
            .replace('ì', 'i').replace('ò', 'o').replace('ù', 'u')
            .trim().trim('.', '!', '?', ',')
        var changed = true
        while (changed) {
            changed = false
            for (p in POLITE_PREFIXES) {
                if (t.startsWith(p)) {
                    t = t.removePrefix(p).trim()
                    changed = true
                }
            }
        }
        return t.replace(" per favore", "").replace("per favore ", "").trim()
    }

    /** Extracts the note body of a "remember this" command, preserving case. */
    private fun rememberContent(raw: String): String? {
        val lower = normalize(raw)
        for (p in REMEMBER_PREFIXES) {
            if (lower.startsWith(p)) {
                // Map the normalized offset back onto the original text as best we
                // can: take the same number of trailing characters.
                val body = lower.removePrefix(p).trim().trim(':', '-', ' ')
                if (body.isEmpty()) return null
                val original = raw.takeLast(body.length).trim()
                return if (original.length == body.length) original else body
            }
        }
        return null
    }

    private fun normalizeApp(raw: String): String = raw
        .replace("google maps", "maps")
        .replace("google chrome", "chrome")
        .replace("gestore file", "file")
        .replace("i file", "file")
        .replace("files", "file")
        .replace("la fotocamera", "fotocamera")
        .replace("il calendario", "calendario")
        .replace("l'orologio", "orologio")
        .replace("le impostazioni", "impostazioni")
        .trim()

    private fun extractPhone(raw: String): String? = PHONE_RE.find(raw)?.value
        ?.trim()?.replace(Regex("""[ ()-]"""), "")
        ?.takeIf { it.count(Char::isDigit) in 3..15 }

    private fun extractSmsBody(raw: String, number: String?): String? {
        val marked = SMS_BODY_RE.find(raw)?.groupValues?.getOrNull(1)?.trim()
        if (!marked.isNullOrBlank()) return marked.take(500)
        if (number == null) return null
        val phoneInRaw = PHONE_RE.find(raw) ?: return null
        return raw.substring(phoneInRaw.range.last + 1)
            .trim().trimStart(':', ',', '-', ' ')
            .takeIf { it.length >= 2 }
            ?.take(500)
    }

    private fun cleanCalendarTitle(raw: String): String = normalize(raw)
        .replace(CALENDAR_TITLE_PREFIX_RE, "")
        .replace(CALENDAR_WORDS_RE, " ")
        // Whitespace is collapsed BEFORE the leading-article cleanup, which is
        // anchored to ^: dropping "calendario" out of the middle of the phrase
        // leaves spaces at the start, and those made the anchor miss, so
        // "crea un evento calendario per il dentista" was titled "per il
        // dentista" instead of "dentista".
        .replace(Regex("""\s+"""), " ")
        .trim(' ', ':', '-', ',')
        // "il|la|lo" must be followed by a space, or a title like "iliade" would
        // be amputated to "iade"; "l'" attaches directly to its noun.
        .replace(Regex("""^(?:per\s+)?(?:(?:il|la|lo)\s+|l')?"""), "")
        .trim(' ', ':', '-', ',')

    private val POLITE_PREFIXES = listOf(
        "puoi ", "potresti ", "per favore ", "mi ", "ti ", "jarvis ", "ehi ", "hey ",
        "vorrei che ", "voglio che ", "riesci a ", "sai ",
    )

    // --- Google-Tasks-style task creation -------------------------------
    /** Captures the list name after "lista" (with an optional preposition). */
    private val TASK_LIST_RE = Regex(
        """\b(?:(?:alla|nella|in|della|dalla|sulla)\s+)?lista\s+([\p{L}][\p{L}0-9]*)""",
    )
    /**
     * Marks the task as starred. Only an explicit "speciale/speciali" flag — the
     * adjective "preferita/preferito" was catching preference statements ("la mia
     * pizza preferita è …") and even questions and turning them into starred
     * tasks instead of memories.
     */
    private val TASK_STAR_RE = Regex("""\b(special[ei])\b""")
    /** Leading scaffolding stripped from the task title. */
    private val TASK_LEAD_RE = Regex(
        """^(?:(?:aggiung\w*|crea\w*|mett\w*|segna\w*|inserisc\w*|nuov[ao]|un[' ]?|una)\s+)*""" +
            """(?:(?:attivit[àa]|task|impegno|cosa)\s+)?(?:(?:di|da|come|:)\s+)*""",
    )

    private val SUPPORTED_APPS = setOf(
        "fotocamera", "calendario", "orologio", "file", "impostazioni",
        "whatsapp", "spotify", "youtube", "gmail", "maps", "chrome", "obsidian",
    )
    private val OPEN_APP_RE = Regex("""^(?:apri|avvia|lancia|mostra)\s+(.+)$""")

    private val SETTINGS_REQUEST_RE = Regex(
        """\b(?:apri|mostra|vai\s+(?:a|alle|nelle)|portami\s+(?:a|alle|nelle))\b.{0,20}\bimpostazion\w*\b|^impostazion\w*(?:\s+.+)?$""",
    )
    private val SETTINGS_AREAS = linkedMapOf(
        "accesso notifiche" to listOf("accesso notifiche", "notification access"),
        "archiviazione" to listOf("archiviazione", "memoria telefono", "spazio"),
        "bluetooth" to listOf("bluetooth"),
        "batteria" to listOf("batteria", "risparmio energetico"),
        "notifiche" to listOf("notifiche"),
        "posizione" to listOf("posizione", "localizzazione", "gps"),
        "privacy" to listOf("privacy"),
        "schermo" to listOf("schermo", "display"),
        "audio" to listOf("audio", "suono", "volume"),
        "voce" to listOf("voce", "sintesi vocale", "tts"),
        "assistente" to listOf("assistente", "app predefinite"),
        "wifi" to listOf("wifi", "wi fi"),
    )

    private val CALENDAR_WRITE_RE = Regex(
        """\b(?:crea|aggiungi|inserisci|prepara|metti|segna|esporta)\w*\b.{0,45}\b(?:evento|appuntamento|attivita|task|calendario|calendar)\b|""" +
            """\b(?:evento|appuntamento|attivita|task)\b.{0,30}\b(?:calendario|calendar|crea|aggiungi|inserisci)\w*\b""",
    )
    private val CALENDAR_EXPORT_RE = Regex(
        """\b(?:google\s+calendar|calendario\s+(?:del\s+)?telefono|calendario\s+android)\b|""" +
            """\b(?:esporta|prepara\w*\s+(?:una\s+)?bozza)\w*\b.{0,30}\bcalendario\b""",
    )
    private val CALENDAR_TITLE_PREFIX_RE = Regex(
        """^(?:crea|aggiungi|inserisci|prepara|metti|segna|esporta)\w*\s+(?:(?:un|una)\s+)?(?:(?:evento|appuntamento|attivita|task)\s+)?(?:chiamat[oa]\s+|per\s+)?""",
    )
    private val CALENDAR_WORDS_RE = Regex(
        """\b(?:(?:su|nel|nello|sul|al|in)\s+)?(?:google\s+calendar|calendario(?:\s+(?:del\s+)?telefono|\s+android)?)\b|""" +
            """\b(?:un|una)\s+(?:evento|appuntamento|attivita|task)\b""",
    )

    private val COMPLETE_AGENDA_RE = Regex(
        """^(?:segna|imposta|considera)\w*\s+(.+?)\s+come\s+(?:fatt[oa]|completat[oa]|conclus[oa])$|""" +
            """^(?:ho\s+)?(?:completat[oa]|fatto|finito)\s+(.+)$|""" +
            """^spunta\w*\s+(.+)$""",
    )
    private val COMPLETE_AGENDA_BARE_RE = Regex(
        """^(?:segna|imposta)\w*\s+come\s+(?:fatt[oa]|completat[oa])$|^spunt\w*$""",
    )
    private val AGENDA_TARGET_PREFIX_RE = Regex(
        """^(?:(?:l[' ]?)?attivita|(?:il\s+)?task|(?:l[' ]?)?impegno)\s+""",
    )

    private val DIAL_REQUEST_RE = Regex(
        """\b(?:chiama|telefon\w*|prepara\w*\s+(?:una\s+)?chiamat\w*|apri\w*\s+(?:il\s+)?dialer)\b""",
    )
    private val SMS_REQUEST_RE = Regex(
        """\b(?:sms|messaggio\s+sms)\b|\b(?:prepara|scrivi|componi|manda|invia)\w*\b.{0,20}\b(?:messaggio|sms)\b""",
    )
    private val PHONE_RE = Regex("""(?<!\w)\+?\d[\d ()-]{1,28}\d(?!\w)""")
    private val SMS_BODY_RE = Regex(
        """(?i)(?:dicendo|con\s+scritto|con\s+testo|testo\s*:|messaggio\s*:|:)\s*(.+)$""",
    )

    // --- Modalità Guida --------------------------------------------------
    private val DRIVING_MODE_START_RE = Regex("""\battiva\w*\s+modalit[àa]\s+(?:guida|moto)\b""")
    private val DRIVING_MODE_STOP_RE = Regex(
        """\b(?:chiudi|termina)\w*\s+modalit[àa]\s+(?:guida|moto)\b|""" +
            """\besci\w*\s+dalla\s+modalit[àa]\s+(?:guida|moto)\b""",
    )
    private val DRIVING_NAV_SET_RE = Regex("""\bimposta\w*\s+(?:la\s+)?navigazione\s+per\s+(.+)$""")
    private val DRIVING_NAV_START_RE = Regex("""^avvia\w*\s+(?:il\s+)?percorso$""")
    private val DRIVING_PANEL_MESSAGES_SHOW_RE = Regex("""\bmostra\w*\s+dettagli\s+(?:messaggi|notifiche)\b""")
    private val DRIVING_PANEL_MESSAGES_HIDE_RE = Regex(
        """\briduci\w*\s+messaggi\b|\bnascond\w*\s+dettagli\s+messaggi\b""",
    )
    private val DRIVING_PANEL_MEDIA_SHOW_RE = Regex(
        """\bmostra\w*\s+spotify\b|^apri\w*\s+(?:la\s+)?playlist\s+.+$""",
    )
    private val DRIVING_PANEL_MEDIA_HIDE_RE = Regex("""\briduci\w*\s+spotify\b|\bchiudi\w*\s+spotify\b""")
    private val REPLY_MESSAGE_RE = Regex("""^rispondi\s+a\s+(.+?)\s*[:,]\s*(.+)$""", RegexOption.IGNORE_CASE)

    private val NAVIGATION_RE = Regex(
        """^naviga\w*\s+(?:a|al|alla|verso|fino\s+a)?\s*(.+)$|""" +
            """^(?:portami|guidami)\s+(?:a|al|alla|verso|fino\s+a)\s+(.+)$|""" +
            """^(?:(?:dammi\s+)?indicazioni\s+(?:per|verso)|apri\s+maps\s+(?:per|verso))\s+(.+)$""",
    )
    private val MEDIA_PAUSE_RE = Regex(
        """\b(?:metti|mettere|vai)?\s*(?:in\s+)?pausa\b.{0,18}\b(?:musica|brano|riproduzione|audio|podcast)\b|""" +
            """\bferma\w*\b.{0,15}\b(?:musica|brano|riproduzione|audio|podcast)\b|""" +
            // Bare "metti pausa" (Modalità Guida, spec §21): anchored to the whole
            // utterance so a sentence merely containing "pausa" elsewhere is not
            // mistaken for a media command.
            """^metti\w*\s+(?:in\s+)?pausa$""",
    )
    private val MEDIA_NEXT_RE = Regex(
        """\b(?:brano|canzone|traccia)\s+successiv\w*\b|\b(?:salta|passa)\w*\b.{0,15}\b(?:brano|canzone|traccia)\b|""" +
            """\bprossim\w*\s+(?:brano|canzone|traccia)\b""",
    )
    private val MEDIA_PREVIOUS_RE = Regex("""\b(?:brano|canzone|traccia)\s+precedent\w*\b|\btorna\w*\b.{0,15}\b(?:brano|canzone|traccia)\b""")
    private val MEDIA_RESUME_RE = Regex(
        """\b(?:riprendi|continua)\w*\b.{0,15}\b(?:musica|brano|riproduzione)\b|^riprendi\w*$""",
    )
    private val PLAY_MEDIA_RE = Regex(
        """^(?:riproduci|fammi\s+sentire)\s+(.+)$|""" +
            """^(?:ascolta|metti)\s+(?:la\s+|il\s+|un\s+|una\s+)?(?:musica|canzone|traccia|playlist|album|podcast)\s+(.+)$""",
    )

    private val NOTIFICATION_READ_RE = Regex(
        """\b(?:leggi|elenca|dimmi|mostra)\w*\b.{0,24}\b(?:notifiche?|messagg\w*)\b|""" +
            """\b(?:che|quali|quante)\s+(?:notifiche?|messagg\w*)\b|\bchi\s+mi\s+ha\s+scritto\b""",
    )
    private val NOTIFICATION_APP_RE = Regex("""\b(?:notifiche?|messagg\w*)\s+(?:di|da)\s+([\p{L}0-9._ -]+)$""")
    private val VAULT_SEARCH_RE = Regex(
        """\b(?:cerca|trova)\w*\s+(?:nel|nello|tra\s+i|nei)\s+(?:vault|miei\s+file|miei\s+appunti|note)\s+(.+)$""",
    )

    private val TIME_RE = Regex("""\b(che ore sono|che ora (e|e')|ora esatta|che giorno (e|e')|che data (e|e')|dimmi l'ora|dimmi che ore)\b""")
    private val BATTERY_REQUEST_RE = Regex(
            """\b(quanta|quanto)\b.{0,24}\bbatteri[ae]\b|""" +
            """\b(livello|percentuale|stato)\b.{0,24}\bbatteri[ae]\b|""" +
            """\bbatteri[ae]\b.{0,28}\b(quanta|quanto|livello|percentuale)\b|""" +
            """\b(dimmi|controlla|verifica|sai)\b.{0,28}\bbatteri[ae]\b""",
    )
    private val BATTERY_CONTEXT_RE = Regex("""\b(batteri[ae]|percento.*carica|telefono.*carica)\b""")
    private val CHARGING_FOLLOW_UP_RE = Regex(
        """\b(e|sta|si trova)\b.{0,18}\b(in carica|caricando|scarica)\b|""" +
            """\b(in carica|caricando|scarica)\b.{0,18}\b(adesso|ora|momento)\b""",
    )
    private val FOLLOW_UP_REQUEST_RE = Regex("""\b(dimmi|controlla|verifica|sai)\b""")

    // Any torch verb + the word torcia/flash, in either order.
    private val TORCH_RE = Regex("""\b(accend\w*|attiv\w*|spegn\w*|disattiv\w*)\b.{0,20}\b(torcia|flash)\b|\b(torcia|flash)\b.{0,20}\b(accend\w*|attiv\w*|spegn\w*|disattiv\w*)\b""")
    private val TORCH_OFF_RE = Regex("""\b(spegn\w*|disattiv\w*)\b""")

    private val TIMER_WORD_RE = Regex("""\b(timer|conto alla rovescia)\b""")

    /** "avvisami fra 10 minuti", "ricordamelo tra un'ora" — a countdown by any name. */
    private val COUNTDOWN_RE = Regex("""\b(avvis\w*|chiam\w*|sveglia\w*)\b.{0,15}\b(fra|tra)\b|\b(fra|tra)\b.{0,25}\b(minut\w*|second\w*|or[ae])\b""")

    private val ALARM_WORD_RE = Regex("""\b(sveglia|svegliami|sveglie|destami)\b""")

    /** A bare "ricorda"/"prendi nota" with nothing to store yet. */
    private val BARE_REMEMBER_RE = Regex("""^(ricorda(mi|ti)?|prendi (una )?nota|annota|segna(ti)?)\s*$""")

    private val MEMORY_ONLY_RE = Regex(
        """^(?:ricorda(?:ti)? che|prendi (?:una )?nota|annota|nota che)\b|\bsolo per (?:questa|la) conversazione\b""",
    )

    /** "che impegni ho", "cosa devo fare oggi", "cosa ho in agenda" → the calendar. */
    private val AGENDA_RE = Regex(
        """\b(che|quali|quanti)\s+(impegni|appuntamenti|promemoria|attivita|task)\b|""" +
            """\bcosa\s+(devo|ho da)\s+fare\b|""" +
            """\bcosa\s+ho\s+(in\s+)?(agenda|programma|previsto)\b|""" +
            """\bne\s+ho\s+(uno|una|qualcuno|qualcuna|qualcosa)(\s+in\s+programma)?\b|""" +
            """\bin\s+agenda\b|\bla mia agenda\b|\bi miei (impegni|appuntamenti|promemoria|task)\b|""" +
            """\bho\s+(qualcosa|impegni|appuntamenti)\b""",
    )

    /**
     * "cosa hai segnato", "cosa ho salvato (nei vault)", "cosa mi piace",
     * "cosa sai di me", "i miei appunti" → the saved notes, answered from the
     * file itself so recall is never invented by the model.
     */
    private val RECALL_RE = Regex(
        """\bcosa\s+(hai|ho)\s+(segnato|annotato|scritto|salvato|memorizzato)\b|""" +
            """\bi miei appunti\b|\bcosa mi ricordi\b|\bche (cose|appunti) hai\b|""" +
            """\bcosa\s+(sai|ricordi)\s+di\s+me\b|\bcosa\s+ti\s+ho\s+detto\s+di\s+me\b|""" +
            """\b(cosa|che\s+cosa|che\s+\w+)\s+mi\s+piace\b""",
    )

    /**
     * Delete a memory. "dimentica/scorda" are inherently about memory, so they
     * take the rest as the target; the generic verbs (cancella/elimina/togli/
     * rimuovi) require a memory cue ("l'appunto", "il ricordo", "dalla memoria")
     * so they don't hijack an agenda or alarm deletion.
     */
    private val FORGET_MEMORY_RE = Regex(
        """^(?:dimentica(?:ti)?|scorda(?:ti)?)\s+(?:che\s+|di\s+|del\s+|dello\s+|della\s+|il\s+|lo\s+|la\s+)?(.+)$""" +
            """|^(?:cancella|elimina|togli|rimuovi)\s+(?:dalla memoria|dagli appunti|l['’]appunto|il ricordo)""" +
            """\s*(?:su\w*\s+|che\s+|di\s+|del\s+|dello\s+|della\s+)?(.+)$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Re-file a note: a move verb, an explicit note cue, optionally the words
     * that identify the note, then the destination category.
     */
    private val MOVE_MEMORY_RE = Regex(
        """(?:spost|mett|archivi|classific|sistem)\w*\s+""" +
            """(?:l['’]appunt[oi]|(?:la\s+|questa\s+)?not[ae]|(?:il\s+|questo\s+)?memo|""" +
            """quest[oa]\s+appunt[oi]|il\s+ricordo)\s*""" +
            """(.*?)\s*(?:\bin\b|\bnella\b|\bnel\b|\bsotto\b)\s+(.+)$""",
    )

    /** Planner nouns that make a generic edit belong to the calendar, not the notes. */
    private val PLANNER_NOUN_RE = Regex(
        """\b(?:appuntament[oi]|event[oi]|impegn[oi]|riunion[ei]|meeting|agenda|calendario)\b""",
    )

    /** Edit a memory: "cambia/aggiorna/modifica/correggi/sostituisci <vecchio> in/con <nuovo>". */
    private val UPDATE_MEMORY_RE = Regex(
        """^(?:cambia|aggiorna|modifica|correggi|sostituisci)\s+""" +
            """(?:l['’]appunto\s+(?:su\w+\s+)?|il ricordo\s+(?:su\w+\s+)?|la memoria\s+|che\s+)?""" +
            """(.+?)\s+(?:in|con)\s+(.+)$""",
        RegexOption.IGNORE_CASE,
    )

    /** "quanto manca alle 16?", "fra quanto è l'appuntamento?" */
    private val TIME_UNTIL_RE = Regex(
        """\bquanto\s+(tempo\s+)?(manca|manchera|ci vuole|rimane|resta)\b|""" +
            """\b(fra|tra)\s+quanto\b|\bquanto\s+ci\s+vuole\b""",
    )

    /** The user answering "Quando?" with "no idea" — save it as a plain note instead. */
    private val NO_DATE_RE = Regex(
        """\b(nessuna data|senza data|niente data|non lo so|non so|non ho una data|indefinit\w*|boh|quando capita)\b""",
    )

    /** Does the note already say WHEN? Used to decide whether to ask. */
    fun hasTimeReference(text: String): Boolean = TIME_REFERENCE_RE.containsMatchIn(text.lowercase())

    private val TIME_REFERENCE_RE = Regex(
        """\b(oggi|domani|dopodomani|stasera|stamattina|stanotte|adesso|subito|""" +
            """luned[ìi]|marted[ìi]|mercoled[ìi]|gioved[ìi]|venerd[ìi]|sabato|domenica|""" +
            """gennaio|febbraio|marzo|aprile|maggio|giugno|luglio|agosto|settembre|ottobre|novembre|dicembre|""" +
            """settimana|mese|weekend|fine settimana|prossim\w+|""" +
            """(tra|fra)\s+\w+\s+(giorn\w+|settiman\w+|mes\w+|or[ae]|minut\w+)|""" +
            """\bil\s+\d{1,2}\b|\bentro\b|\balle\s+\d{1,2}\b)""",
    )

    private val CALC_RE = Regex("""\b(?:calcola|quanto fa|quant'?e)\s+(.+)$""")

    /** Longest-first so "ricordami che " wins over "ricorda ". */
    private val REMEMBER_PREFIXES = listOf(
        "ricordami che ", "ricordami di ", "ricordati che ", "ricordati di ",
        "ricorda che ", "ricorda di ", "ricordami ", "ricordati ",
        "prendi nota che ", "prendi nota di ", "prendi nota ",
        "segna che ", "segnati che ", "segna ",
        "annota che ", "annota ", "nota che ", "ricorda ",
    )
}
