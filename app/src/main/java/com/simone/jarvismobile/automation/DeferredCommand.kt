package com.simone.jarvismobile.automation

import com.simone.jarvismobile.core.agenda.ItalianDateTimeParser
import com.simone.jarvismobile.core.automation.Action
import com.simone.jarvismobile.core.automation.Automation
import com.simone.jarvismobile.core.automation.AutomationPhrase
import com.simone.jarvismobile.core.automation.Trigger
import com.simone.jarvismobile.tools.CommandMatcher
import com.simone.jarvismobile.tools.Match
import java.time.LocalDateTime

/**
 * Turns "alle 11.45 accendi la torcia" into a one-shot rule instead of running
 * the torch this instant.
 *
 * The distinction the recurring parser can't make lives here: a phrase that
 * names an absolute time *and* whose remainder is a recognised device command
 * becomes an [Automation] with a [Trigger.Once] and an [Action.Tool]. This
 * necessarily sits in `app/` rather than `:core`: only here can the words be run
 * back through [CommandMatcher] to check they map to a permitted tool. Nothing
 * ever executes at parse time — only the text is stored, and it only becomes a
 * tool call by passing the same allowlist again when the alarm fires
 * (docs/SECURITY.md §15, ADR 0008).
 *
 * An appointment ("domani alle 15 dentista") is *not* a command — its remainder
 * matches no tool — so it still falls through to the agenda untouched.
 */
object DeferredCommand {

    /**
     * The tools worth running unattended, at a wall-clock minute, from a
     * background alarm — and that actually take effect with no foreground
     * window. The torch (CameraManager) and media transport controls
     * (MediaSession) run headless; anything that would have to *launch an
     * Activity* is deliberately excluded, because Android blocks background
     * Activity starts and a rule must never promise an action it can't deliver
     * while the phone is asleep. Every entry is already on the CommandMatcher
     * allowlist; this is a strict subset of it.
     */
    val ELIGIBLE_TOOLS = setOf("flashlight", "media_control")

    /**
     * Returns the one-shot command rule the sentence describes, or null when it
     * isn't one. Null is the common case (every appointment and every plain
     * command lands here) and stays cheap.
     */
    fun parse(text: String, now: LocalDateTime = LocalDateTime.now()): Automation? {
        val raw = text.trim()
        if (raw.isEmpty()) return null
        // A recurring or conditional phrase belongs to AutomationPhrase; do not
        // steal it here.
        if (AutomationPhrase.parse(raw) != null) return null

        val whenParsed = ItalianDateTimeParser.parse(raw, now)
        val time = whenParsed.time ?: return null
        // The parser fills a bare clock time in as today (or tomorrow if already
        // past), so `at` is always in the future for a time-only phrase.
        val date = whenParsed.date ?: now.toLocalDate()
        val at = LocalDateTime.of(date, time)
        if (!at.isAfter(now)) return null

        val command = whenParsed.remainder.trim().trim('.', ',', ':', ';', '!', '?', ' ')
        if (command.length < 2) return null

        val match = CommandMatcher.match(command, now) as? Match.Run ?: return null
        if (match.call.name !in ELIGIBLE_TOOLS) return null

        return Automation(
            name = command.take(NAME_CHARS),
            trigger = Trigger.Once(at),
            action = Action.Tool(command),
        )
    }

    private const val NAME_CHARS = 60
}
