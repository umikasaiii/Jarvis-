package com.simone.jarvismobile.core.proactive

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

/** The everyday signals a suggestion is composed from. All optional/­defaulted. */
data class ProactiveSnapshot(
    val batteryPercent: Int = -1,
    val charging: Boolean = false,
    /** The next wake alarm, when known — used for "charge before your early alarm". */
    val nextAlarm: LocalTime? = null,
    /** Today's timed appointments, already formatted ("dentista 15:00"). */
    val todayAppointments: List<String> = emptyList(),
    /** Today's due or starred tasks, already formatted. */
    val todayTasks: List<String> = emptyList(),
    /** Names/entries flagged as today's birthdays, already formatted. */
    val birthdaysToday: List<String> = emptyList(),
    /**
     * True when rain is forecast for today. Null means "unknown" (offline, or the
     * weather source not configured) — the digest omits the clause rather than
     * guessing (§ conditions three-valued rule applies here too).
     */
    val rainToday: Boolean? = null,
)

/** The governor's verdict: deliver exactly one suggestion, or stay silent with a reason. */
sealed interface ProactiveDecision {
    data class Deliver(val suggestion: ProactiveSuggestion, val newState: ProactiveState) : ProactiveDecision
    data class Skip(val reason: String) : ProactiveDecision
}
