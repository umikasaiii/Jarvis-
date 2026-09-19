package com.simone.jarvismobile.core.proactive

import com.simone.jarvismobile.core.tools.ToolOutcomeStatus
import com.simone.jarvismobile.core.weather.WeatherCategory
import java.time.LocalDate
import java.time.LocalTime

/**
 * The built-in proactive behaviours. The set is closed and named so every
 * suggestion the user sees maps to a category they can switch off or mute — there
 * is no open-ended "the assistant decided to say something".
 */
enum class ProactiveKind {
    /** "Buongiorno" plus today's appointments/tasks, at the first morning unlock. */
    MORNING_DIGEST,

    /** "Batteria bassa e domani sveglia presto: caricala stanotte." */
    BATTERY_BEFORE_ALARM,

    /** An end-of-day recap of what's still open / what's tomorrow. */
    EVENING_DIGEST,

    /**
     * § JARVIS Implementation Master Plan — PASSAGGIO 14.2. An evening-before
     * warning that meaningful rain or a thunderstorm is forecast for
     * tomorrow — content the user would want even during quiet hours (§
     * [ProactiveGovernor]'s `QUIET_HOURS_EXEMPT`, same reasoning already
     * applied to the digests), never a discretionary tip like
     * [BATTERY_BEFORE_ALARM].
     */
    WEATHER_ALERT,
}

/** One thing JARVIS could say, already composed. Not yet shown — the governor decides. */
data class ProactiveSuggestion(
    val kind: ProactiveKind,
    val message: String,
    /** Higher wins when several are eligible at once. */
    val priority: Int,
    /** Stable per-day key so the same suggestion is never delivered twice in a day. */
    val dedupKey: String,
)

/**
 * The user's control surface (spec: keep what you like, mute what you don't).
 * [disabledKinds] is a category switched off in settings; [mutedKinds] is a
 * "non avvisarmi più di questo" tapped on a message. They suppress the same way;
 * both are explicit and reversible, never inferred.
 */
data class ProactiveSettings(
    val enabled: Boolean = false,
    val maxPerDay: Int = 3,
    val quietStart: LocalTime = LocalTime.of(22, 0),
    val quietEnd: LocalTime = LocalTime.of(8, 0),
    val disabledKinds: Set<ProactiveKind> = emptySet(),
    val mutedKinds: Set<ProactiveKind> = emptySet(),
) {
    fun allows(kind: ProactiveKind): Boolean =
        enabled && kind !in disabledKinds && kind !in mutedKinds
}

/**
 * What the day's delivery looks like so far, so the budget and the "once a day"
 * rule are enforced. The caller persists the state returned on a delivery; a new
 * calendar day resets it.
 */
data class ProactiveState(
    val day: LocalDate,
    val deliveredCount: Int = 0,
    val deliveredKeys: Set<String> = emptySet(),
) {
    fun rolledTo(today: LocalDate): ProactiveState =
        if (day == today) this else ProactiveState(today)
}

/**
 * § JARVIS Implementation Master Plan — PROACTIVITY RELIABILITY CLOSURE
 * WORK PACKAGE C §5 — one date's grounded agenda facts, never inferred from
 * a list's name or from "now": [date] is explicit and carried alongside the
 * data, so a composer reading this never has to guess which calendar day
 * [appointments]/[datedTasks]/[birthdays] actually belong to. [agendaStatus]
 * is the real outcome of the query that produced this section — reused
 * verbatim from [com.simone.jarvismobile.core.tools.ToolOutcomeStatus] (§6/§17: no
 * second grounding vocabulary) — so a composer can tell a genuinely empty
 * day ([ToolOutcomeStatus.SUCCESS_EMPTY]) from one the agenda source failed
 * to read ([ToolOutcomeStatus.SOURCE_FAILURE]/[ToolOutcomeStatus.DATA_UNAVAILABLE]):
 * EMPTY must never be asserted from the latter (§7/§8's critical invariant).
 */
data class ProactiveDaySection(
    val date: LocalDate,
    val agendaStatus: ToolOutcomeStatus,
    /** Timed appointments for [date], already formatted ("dentista 15:00"). */
    val appointments: List<String> = emptyList(),
    /** Untimed tasks genuinely DATED [date] — never a starred task from another day (§10). */
    val datedTasks: List<String> = emptyList(),
    /** Names/entries flagged as [date]'s birthdays, already formatted. */
    val birthdays: List<String> = emptyList(),
)

/**
 * § WORK PACKAGE C §12 — a date-targeted, honestly-graded weather fact.
 * [status] follows the exact same freshness discipline the rest of this
 * codebase already uses (see `ContextEngine.todayForecastFacts`/
 * `tomorrowForecastFacts`): only [ToolOutcomeStatus.SUCCESS_DATA] licenses a
 * composer to show an emoji or a qualitative clause built from [category]/
 * [rain] — [ToolOutcomeStatus.STALE]/[ToolOutcomeStatus.DATA_UNAVAILABLE]/
 * [ToolOutcomeStatus.SOURCE_FAILURE] must never be silently treated as
 * "known". No new weather confidence semantics are introduced here — this is
 * Work Package D's territory, deliberately untouched.
 */
data class ProactiveWeatherFacts(
    val targetDate: LocalDate,
    val category: WeatherCategory? = null,
    val status: ToolOutcomeStatus = ToolOutcomeStatus.DATA_UNAVAILABLE,
    val rain: Boolean? = null,
)

/**
 * § WORK PACKAGE C §5 — the typed, date-explicit input every digest composer
 * consumes. Replaces the old ambiguous `ProactiveSnapshot` (which had only
 * "today*" fields and no notion of a target date at all — the structural
 * reason the Evening Digest defect in §4 was possible: there was no typed
 * "tomorrow" data for it to draw from). [deliveryDate] is the calendar day
 * this snapshot was assembled for (always "today" from the device's own
 * clock, never re-derived by a composer — § "never silently recompute using
 * system clock"); [today]/[tomorrow] are self-contained, independently
 * gradeable sections. [todayCarryoverForEvening] and [openPriorities] are
 * kept as their OWN fields, never folded into [tomorrow], so a composer can
 * never accidentally present them as if they were dated tomorrow (§9/§10).
 */
data class ProactiveDigestSnapshot(
    val deliveryDate: LocalDate,
    val today: ProactiveDaySection,
    val tomorrow: ProactiveDaySection,
    /**
     * § §9 — today's still-open dated tasks, carried into the Evening Digest
     * under their OWN explicit heading ("Da oggi restano: ..."), never
     * merged into [tomorrow]'s items and never implicitly re-dated. Empty
     * when there is nothing left open, or when the product chooses not to
     * surface carryover at all.
     */
    val todayCarryoverForEvening: List<String> = emptyList(),
    /**
     * § §10 — starred, UNDATED tasks: a star is priority, not a date. Chosen
     * policy (documented here, not silently decided): a separate "Priorità
     * aperte" grouping, never folded into [today]/[tomorrow], never used to
     * fabricate a deadline for an undated item.
     */
    val openPriorities: List<String> = emptyList(),
    val todayWeather: ProactiveWeatherFacts? = null,
    val tomorrowWeather: ProactiveWeatherFacts? = null,
    val batteryPercent: Int = -1,
    val charging: Boolean = false,
    /** The next wake alarm, when known — used for "charge before your early alarm". */
    val nextAlarm: LocalTime? = null,
)

/** The governor's verdict: deliver exactly one suggestion, or stay silent with a reason. */
sealed interface ProactiveDecision {
    data class Deliver(val suggestion: ProactiveSuggestion, val newState: ProactiveState) : ProactiveDecision
    data class Skip(val reason: String) : ProactiveDecision
}
