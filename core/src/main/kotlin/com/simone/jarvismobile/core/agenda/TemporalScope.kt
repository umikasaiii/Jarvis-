package com.simone.jarvismobile.core.agenda

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters

/**
 * § FASE 2A.10 SEMANTIC ROUTER AUTHORITATIVE — a normalized calendar meaning,
 * distinct from the Semantic Interpreter's own job: the interpreter only
 * recognizes THAT a turn carries a temporal scope and preserves the exact
 * words used ([com.simone.jarvismobile.core.semantic.SemanticFrame.temporalExpression]);
 * turning those words into real dates is this resolver's job, never the
 * interpreter's — the same separation already established between
 * `HealthQueryParser` (semantics) and `ItalianDateTimeParser`/`AgendaWeekRange`
 * (calendar normalization).
 */
sealed interface TemporalScope {
    data object None : TemporalScope
    data class Date(val date: LocalDate) : TemporalScope

    /** Inclusive [start]..[end], [start] never after [end]. */
    data class Range(val start: LocalDate, val end: LocalDate) : TemporalScope
}

/**
 * § FASE 2A.10 — the genuinely missing piece behind "Che impegni ho tra oggi
 * e venerdì?"/"Da lunedì a giovedì?": neither [ItalianDateTimeParser] (single
 * day/period only) nor [AgendaWeekRange] (named week phrases only) could
 * express an arbitrary two-endpoint interval before this. Deliberately NOT a
 * hardcoded phrase list — [BETWEEN]/[FROM_TO] recognize the two *shapes*
 * ("tra X e Y", "da X a Y") and resolve each endpoint by reusing
 * [ItalianDateTimeParser] on the isolated token (oggi/domani/dopodomani/a
 * weekday name/an explicit day+month), the same parser every other date
 * phrase in this project already goes through — no second date grammar.
 */
object TemporalScopeResolver {

    private val WEEKEND = Regex("""(?i)\b(?:questo\s+)?weekend\b""")
    // § single-word endpoints only ("oggi"/"venerdì"/"lunedì"/…), deliberately
    // NOT an optional extra word per side: a greedy `(\s+\w+)?` would swallow
    // the mandatory " e "/" a " separator itself (both are also valid single
    // Italian words), making the split ambiguous. A two-word endpoint like an
    // explicit "12 agosto" is a real, accepted gap — see the class doc
    // comment's honesty note — not silently mismatched.
    //
    // The trailing boundary is `(?!\p{L})`, NOT `\b`: Java/Kotlin's `\b` is
    // defined only over `[A-Za-z0-9_]`, so `\bvenerdì\b` actually matches
    // just "venerd" (the ASCII `\b` fires between "d" and the accented "ì",
    // which it never treats as a word character) — the exact same pitfall
    // `ItalianDateTimeParser.word()` already documents and works around.
    private val BETWEEN = Regex("""(?i)\b(?:tra|fra)\s+(\p{L}+)\s+e\s+(\p{L}+)(?!\p{L})""")
    private val FROM_TO = Regex("""(?i)\bda\s+(\p{L}+)\s+a\s+(\p{L}+)(?!\p{L})""")

    /**
     * Resolves [text] against [now]. Order matters: a named week phrase
     * ([AgendaWeekRange]) is checked first (it is the more specific match —
     * "tra" doesn't appear in it), then an explicit weekend/interval shape,
     * finally a single day via [ItalianDateTimeParser]. [TemporalScope.None]
     * means [text] names no date at all — a caller falls back to its own
     * existing single-day/period builder, never guesses.
     */
    fun resolve(text: String, now: LocalDateTime): TemporalScope {
        AgendaWeekRange.resolve(text, now)?.let { return TemporalScope.Range(it.start, it.endInclusive) }

        if (WEEKEND.containsMatchIn(text)) {
            val today = now.toLocalDate()
            val saturday = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
            return TemporalScope.Range(saturday, saturday.plusDays(1))
        }

        BETWEEN.find(text)?.let { m -> resolvePair(m, now)?.let { return it } }
        FROM_TO.find(text)?.let { m -> resolvePair(m, now)?.let { return it } }

        val parsed = ItalianDateTimeParser.parse(text, now)
        if (parsed.dateExplicit && parsed.date != null) return TemporalScope.Date(parsed.date)
        return TemporalScope.None
    }

    private fun resolvePair(match: MatchResult, now: LocalDateTime): TemporalScope.Range? {
        val start = ItalianDateTimeParser.parse(match.groupValues[1], now).date ?: return null
        val end = ItalianDateTimeParser.parse(match.groupValues[2], now).date ?: return null
        return if (!end.isBefore(start)) TemporalScope.Range(start, end) else TemporalScope.Range(end, start)
    }
}
