package com.simone.jarvismobile.core.automation

import java.time.DayOfWeek
import java.time.LocalTime

/**
 * Turns a spoken or typed Italian sentence into an [Automation].
 *
 * "ogni giorno alle 8 ricordami di prendere le vitamine" is a rule; "domani alle
 * 15 dentista" is an appointment. The difference is the whole point of this
 * parser: a one-off date belongs in the agenda, and quietly turning it into a
 * daily rule would have JARVIS nagging forever. Nothing is an automation unless
 * it says so — "ogni", "tutti i", "quando", "se".
 *
 * Word boundaries are written the long way. Java's `\b` is defined over
 * `[A-Za-z0-9_]`, so "lunedì" and "così" never match it — a trap this repository
 * has fallen into three times (ADR 0004).
 */
object AutomationPhrase {

    private fun word(w: String) = "(?<![\\p{L}\\p{Nd}])$w(?![\\p{L}\\p{Nd}])"

    private val WEEKDAYS = mapOf(
        "lunedì" to DayOfWeek.MONDAY, "lunedi" to DayOfWeek.MONDAY,
        "martedì" to DayOfWeek.TUESDAY, "martedi" to DayOfWeek.TUESDAY,
        "mercoledì" to DayOfWeek.WEDNESDAY, "mercoledi" to DayOfWeek.WEDNESDAY,
        "giovedì" to DayOfWeek.THURSDAY, "giovedi" to DayOfWeek.THURSDAY,
        "venerdì" to DayOfWeek.FRIDAY, "venerdi" to DayOfWeek.FRIDAY,
        "sabato" to DayOfWeek.SATURDAY,
        "domenica" to DayOfWeek.SUNDAY,
    )

    /** Says "this repeats" — without one of these nothing here applies. */
    private val RECURRING = Regex(
        "(${word("ogni")}|${word("tutti")}\\s+i\\s+giorni|${word("sempre")})",
        RegexOption.IGNORE_CASE,
    )
    private val CONDITIONAL = Regex(
        "(${word("quando")}|${word("se")}|${word("appena")})",
        RegexOption.IGNORE_CASE,
    )

    private val AT_TIME = Regex(
        """(?:${word("alle")}|${word("all'")}|${word("ore")})\s*(\d{1,2})(?:[:.](\d{2}))?""",
        RegexOption.IGNORE_CASE,
    )
    private val BATTERY = Regex(
        """${word("batteria")}[^0-9]{0,40}?(\d{1,2})\s*%?""",
        RegexOption.IGNORE_CASE,
    )
    private val CHARGING = Regex(
        "(${word("in")}\\s+carica|${word("carica")}|${word("caricabatterie")}|${word("caricatore")})",
        RegexOption.IGNORE_CASE,
    )

    private val SPEAK_HINT = Regex(
        "(${word("dimmi")}|${word("dillo")}|${word("annuncia")}|${word("ad")}\\s+alta\\s+voce)",
        RegexOption.IGNORE_CASE,
    )
    private val AGENDA_HINT = Regex(
        "(${word("in")}\\s+agenda|${word("aggiungi")}\\s+(?:in\\s+)?agenda|${word("segna")})",
        RegexOption.IGNORE_CASE,
    )

    /** Leading fluff to strip off the message once the trigger is removed. */
    private val LEAD = Regex(
        "^(?:(?:${word("di")}|${word("che")}|${word("mi")}|${word("ricordami")}|${word("ricorda")}|" +
            "${word("dimmi")}|${word("dillo")}|${word("avvisami")}|${word("notificami")}|" +
            "${word("annuncia")}|${word("devo")}|${word("di'")}|:|,|-)[\\s,:.-]*)+",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Returns the rule the sentence describes, or null when it does not describe
     * one. Null is the common case and must stay cheap: every chat message goes
     * past here.
     */
    fun parse(text: String): Automation? {
        val raw = text.trim()
        if (raw.isEmpty()) return null

        val recurring = RECURRING.containsMatchIn(raw)
        val conditional = CONDITIONAL.containsMatchIn(raw)
        if (!recurring && !conditional) return null

        var rest = raw
        val trigger = when {
            // Battery and charger are conditions; a clock time is a schedule.
            // Battery is checked first because "quando la batteria scende sotto
            // il 20% avvisami alle 8" is a battery rule with noise in it.
            conditional && BATTERY.containsMatchIn(raw) -> {
                val m = BATTERY.find(raw)!!
                val percent = m.groupValues[1].toIntOrNull()?.takeIf { it in 1..99 } ?: return null
                rest = raw.removeRange(m.range)
                Trigger.BatteryBelow(percent)
            }
            conditional && CHARGING.containsMatchIn(raw) && !AT_TIME.containsMatchIn(raw) -> {
                val m = CHARGING.find(raw)!!
                rest = raw.removeRange(m.range)
                Trigger.ChargingStarted
            }
            recurring -> {
                val at = AT_TIME.find(raw) ?: return null
                val hour = at.groupValues[1].toIntOrNull()?.takeIf { it in 0..23 } ?: return null
                val minute = at.groupValues[2].toIntOrNull()?.takeIf { it in 0..59 } ?: 0
                val days = weekdays(raw)
                rest = raw.removeRange(at.range)
                Trigger.TimeOfDay(LocalTime.of(hour, minute), days)
            }
            else -> return null
        }

        val message = cleanMessage(rest)
        if (message.isBlank()) return null

        val action = when {
            AGENDA_HINT.containsMatchIn(raw) -> Action.AddAgenda(message)
            SPEAK_HINT.containsMatchIn(raw) -> Action.Speak(message)
            else -> Action.Notify(message)
        }
        return Automation(
            name = message.take(NAME_CHARS),
            trigger = trigger,
            action = action,
        )
    }

    private fun weekdays(text: String): Set<DayOfWeek> {
        val found = LinkedHashSet<DayOfWeek>()
        WEEKDAYS.forEach { (name, day) ->
            if (Regex(word(name), RegexOption.IGNORE_CASE).containsMatchIn(text)) found.add(day)
        }
        return found
    }

    /** Strips the scaffolding words so only what the user wants to hear is left. */
    private fun cleanMessage(text: String): String {
        var s = text
        // Everything that was structure rather than content.
        listOf(
            RECURRING, CONDITIONAL, CHARGING,
            Regex(word("giorno"), RegexOption.IGNORE_CASE),
            Regex(word("giorni"), RegexOption.IGNORE_CASE),
            Regex(word("batteria"), RegexOption.IGNORE_CASE),
            Regex(word("scende"), RegexOption.IGNORE_CASE),
            Regex(word("sotto"), RegexOption.IGNORE_CASE),
            Regex(
                "(${word("metto")}|${word("collego")}|${word("attacco")}|${word("inserisco")})",
                RegexOption.IGNORE_CASE,
            ),
            Regex(word("agenda"), RegexOption.IGNORE_CASE),
            AGENDA_HINT,
        ).forEach { s = it.replace(s, " ") }
        WEEKDAYS.keys.forEach { name ->
            s = Regex(word(name), RegexOption.IGNORE_CASE).replace(s, " ")
        }
        s = s.replace(Regex("""\d+\s*%"""), " ")
        s = s.replace(Regex("""\s+"""), " ").trim().trim(',', ':', '-', '.', ' ')
        s = LEAD.replace(s, "").trim()
        return s
    }

    private const val NAME_CHARS = 60
}
