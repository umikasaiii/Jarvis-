package com.simone.jarvismobile.core.agenda

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** What a phrase said about when something happens. */
data class WhenParsed(
    val date: LocalDate?,
    val time: LocalTime?,
    val period: DayPeriod?,
    /** The phrase with the date/time words removed, so it can become the title. */
    val remainder: String,
    /**
     * True when the user actually named a day ("domani", "venerdì", "12 agosto").
     * False when [date] was merely inferred from a bare clock time, which callers
     * like "quanto manca alle 16?" must not treat as a chosen day.
     */
    val dateExplicit: Boolean = false,
)

/**
 * Understands Italian date and time expressions relative to a reference moment.
 *
 * This is deliberately deterministic and lives in `:core` so it can be unit
 * tested on any JVM: dates and clock arithmetic are exactly the kind of thing a
 * small language model gets wrong (it once answered "2 ore e 45 minuti" for the
 * gap between 08:03 and 16:00), and a reminder filed on the wrong day is worse
 * than no reminder.
 */
object ItalianDateTimeParser {

    /**
     * Word boundary that also works next to accented letters. Java's `\b` is
     * defined over `[A-Za-z0-9_]`, so `\bvenerdì\b` never matches — the trailing
     * "ì" is not a word character. Italian needs Unicode-aware boundaries.
     */
    private fun word(pattern: String) = Regex("""(?<!\p{L})(?:$pattern)(?!\p{L})""")

    /**
     * Purely grammatical connectives left dangling at the START of the
     * remainder once the date/time phrase in the middle of the sentence is
     * removed — "il fatto che"/"il fatto di" first (longest match), then the
     * bare che/di/del/della/dei/degli/delle/d'.
     */
    private val LEADING_CONNECTIVE_RE = Regex(
        """^\s*(?:il\s+fatto\s+che|il\s+fatto\s+di|che|di|del|della|dei|degli|delle|d')\s+""",
    )

    private val WEEKDAYS: List<Pair<Regex, DayOfWeek>> = listOf(
        word("luned[iì]") to DayOfWeek.MONDAY,
        word("marted[iì]") to DayOfWeek.TUESDAY,
        word("mercoled[iì]") to DayOfWeek.WEDNESDAY,
        word("gioved[iì]") to DayOfWeek.THURSDAY,
        word("venerd[iì]") to DayOfWeek.FRIDAY,
        word("sabato") to DayOfWeek.SATURDAY,
        word("domenica") to DayOfWeek.SUNDAY,
    )

    private val MONTHS = mapOf(
        "gennaio" to 1, "febbraio" to 2, "marzo" to 3, "aprile" to 4,
        "maggio" to 5, "giugno" to 6, "luglio" to 7, "agosto" to 8,
        "settembre" to 9, "ottobre" to 10, "novembre" to 11, "dicembre" to 12,
    )

    private val UNITS = mapOf(
        "un" to 1, "uno" to 1, "una" to 1, "due" to 2, "tre" to 3, "quattro" to 4,
        "cinque" to 5, "sei" to 6, "sette" to 7, "otto" to 8, "nove" to 9,
        "dieci" to 10, "undici" to 11, "dodici" to 12, "tredici" to 13,
        "quattordici" to 14, "quindici" to 15, "sedici" to 16, "diciassette" to 17,
        "diciotto" to 18, "diciannove" to 19, "venti" to 20, "ventuno" to 21,
        "ventidue" to 22, "ventitre" to 23, "ventiquattro" to 24, "trenta" to 30,
    )

    /** Parses when [text] refers to, relative to [now]. */
    fun parse(text: String, now: LocalDateTime): WhenParsed {
        val lower = text.lowercase()
        var rest = lower
        var date: LocalDate? = null
        var time: LocalTime? = null
        var period: DayPeriod? = null

        // --- Part of day (also disambiguates "alle 4" → 16:00) -------------
        when {
            Regex("""\b(pomeriggio|primo pomeriggio)\b""").containsMatchIn(lower) -> period = DayPeriod.POMERIGGIO
            Regex("""\b(stasera|sera|serata|stanotte|notte)\b""").containsMatchIn(lower) -> period = DayPeriod.SERA
            Regex("""\b(stamattina|mattina|mattino)\b""").containsMatchIn(lower) -> period = DayPeriod.MATTINA
        }

        // "questa mattina" / "questo pomeriggio" / "stasera" mean today. Strip the
        // whole demonstrative phrase from the title so a leftover "questa" doesn't
        // end up in the reminder text (e.g. "questa mattina alle 10 fare l'ordine"
        // must become "fare l'ordine", never "questa fare l'ordine").
        val demonstrative = Regex(
            """(?<!\p{L})quest['oa]?\s+(?:mattina|mattino|pomeriggio|sera|serata|notte)(?!\p{L})""",
        )
        if (demonstrative.containsMatchIn(rest)) {
            date = now.toLocalDate()
            rest = demonstrative.replace(rest, " ")
        }

        // --- Explicit clock time -------------------------------------------
        Regex("""\b(?:alle|ore|all')\s*(\d{1,2})[:.](\d{2})\b""").find(lower)?.let { m ->
            val h = m.groupValues[1].toInt()
            val mi = m.groupValues[2].toInt()
            if (h in 0..23 && mi in 0..59) {
                time = LocalTime.of(h, mi)
                rest = rest.replace(m.value, " ")
            }
        }
        if (time == null) {
            Regex("""\b(\d{1,2})[:.](\d{2})\b""").find(lower)?.let { m ->
                val h = m.groupValues[1].toInt()
                val mi = m.groupValues[2].toInt()
                if (h in 0..23 && mi in 0..59) {
                    time = LocalTime.of(h, mi)
                    rest = rest.replace(m.value, " ")
                }
            }
        }
        if (time == null) {
            Regex("""\b(?:alle|ore|all')\s*(\d{1,2}|[a-zà-ù]+)\b""").find(lower)?.let { m ->
                val raw = m.groupValues[1]
                val h = raw.toIntOrNull() ?: UNITS[raw]
                if (h != null && h in 0..24) {
                    time = LocalTime.of(h % 24, 0)
                    rest = rest.replace(m.value, " ")
                }
            }
        }
        if (time == null && Regex("""\bmezzogiorno\b""").containsMatchIn(lower)) time = LocalTime.NOON
        if (time == null && Regex("""\bmezzanotte\b""").containsMatchIn(lower)) time = LocalTime.MIDNIGHT

        // "e mezza" / "e un quarto" attach to the hour just read.
        time?.let { t ->
            if (Regex("""\be\s+mezz[oa]\b""").containsMatchIn(lower)) time = t.plusMinutes(30)
            else if (Regex("""\be\s+un quarto\b""").containsMatchIn(lower)) time = t.plusMinutes(15)
        }

        // Afternoon/evening shifts a 1–11 hour into the 13–23 range.
        time?.let { t ->
            if ((period == DayPeriod.POMERIGGIO || period == DayPeriod.SERA) && t.hour in 1..11) {
                time = t.plusHours(12)
            }
        }

        // --- Date ----------------------------------------------------------
        val today = now.toLocalDate()
        when {
            Regex("""\bdopodomani\b""").containsMatchIn(lower) -> date = today.plusDays(2)
            Regex("""\bdomani\b""").containsMatchIn(lower) -> date = today.plusDays(1)
            Regex("""\b(oggi|stasera|stamattina|stanotte|oggi pomeriggio)\b""").containsMatchIn(lower) -> date = today
            Regex("""\bieri\b""").containsMatchIn(lower) -> date = today.minusDays(1)
        }

        if (date == null) {
            Regex("""\b(?:tra|fra)\s+(\d{1,3}|[a-zà-ù]+)\s+(giorn\w*|settiman\w*|mes\w*)\b""")
                .find(lower)?.let { m ->
                    val n = m.groupValues[1].toIntOrNull() ?: UNITS[m.groupValues[1]]
                    if (n != null) {
                        date = when {
                            m.groupValues[2].startsWith("settiman") -> today.plusWeeks(n.toLong())
                            m.groupValues[2].startsWith("mes") -> today.plusMonths(n.toLong())
                            else -> today.plusDays(n.toLong())
                        }
                        rest = rest.replace(m.value, " ")
                    }
                }
        }

        if (date == null) {
            // "il 12 agosto" / "12 agosto"
            Regex("""\b(?:il\s+)?(\d{1,2})\s+([a-zà-ù]+)\b""").find(lower)?.let { m ->
                val day = m.groupValues[1].toIntOrNull()
                val month = MONTHS[m.groupValues[2]]
                if (day != null && month != null && day in 1..31) {
                    var d = runCatching { LocalDate.of(today.year, month, day) }.getOrNull()
                    if (d != null && d.isBefore(today)) d = d.plusYears(1)
                    date = d
                    rest = rest.replace(m.value, " ")
                }
            }
        }

        if (date == null) {
            // Weekday name → the next such day (today counts only with "oggi").
            for ((re, dow) in WEEKDAYS) {
                if (re.containsMatchIn(lower)) {
                    var d = today
                    do { d = d.plusDays(1) } while (d.dayOfWeek != dow)
                    if (Regex("""prossim""").containsMatchIn(lower) && d.isBefore(today.plusDays(7))) {
                        d = d.plusWeeks(1)
                    }
                    date = d
                    rest = re.replace(rest, " ")
                    break
                }
            }
        }

        if (date == null) {
            Regex("""\b(\d{1,2})[/\-](\d{1,2})(?:[/\-](\d{2,4}))?\b""").find(lower)?.let { m ->
                val day = m.groupValues[1].toInt()
                val month = m.groupValues[2].toInt()
                val yearRaw = m.groupValues[3].toIntOrNull()
                val year = when {
                    yearRaw == null -> today.year
                    yearRaw < 100 -> 2000 + yearRaw
                    else -> yearRaw
                }
                var d = runCatching { LocalDate.of(year, month, day) }.getOrNull()
                if (d != null && yearRaw == null && d.isBefore(today)) d = d.plusYears(1)
                date = d
                rest = rest.replace(m.value, " ")
            }
        }

        val dateExplicit = date != null

        // An explicit time with no date means today, or tomorrow if already past.
        if (date == null && time != null) {
            date = if (time!!.isBefore(now.toLocalTime())) today.plusDays(1) else today
        }

        rest = rest
            .replace(Regex("""\b(oggi|domani|dopodomani|ieri|stasera|stamattina|stanotte|pomeriggio|mattina|mattino|sera|serata|notte|prossimo|prossima)\b"""), " ")
            .replace(Regex("""\b(alle|ore|all'|di|del|nel|questo|questa)\s*$"""), " ")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
            .trim(',', '.', ';', '—', '-', ' ')

        // A leading connective is what's left over when the date/time phrase sat
        // in the MIDDLE of the sentence ("domani alle 21 di sistemare…" → after
        // removing "domani alle 21", "di sistemare…" remains) — the trailing-only
        // strip above never catches this. Looped since more than one can stack
        // ("che di sistemare…" is unlikely but not impossible after cleanup).
        var changedLeading = true
        while (changedLeading) {
            val stripped = LEADING_CONNECTIVE_RE.replaceFirst(rest, "")
            changedLeading = stripped != rest
            rest = stripped
        }

        return WhenParsed(date, time, period, rest, dateExplicit)
    }

    /**
     * Minutes from [now] until the next occurrence of the clock time in [text].
     * Null when no time is mentioned. "alle 4 di pomeriggio" at 08:03 → 477.
     */
    fun minutesUntil(text: String, now: LocalDateTime): Long? {
        val parsed = parse(text, now)
        val t = parsed.time ?: return null
        val target = (parsed.date ?: now.toLocalDate()).atTime(t)
        val fixed = if (target.isBefore(now)) target.plusDays(1) else target
        return java.time.Duration.between(now, fixed).toMinutes()
    }
}
