package com.simone.jarvismobile.core.proactive

import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Decides whether — and what — to say, given the candidate suggestions and the
 * user's controls. This is the "giudizio" that separates proactivity from a plain
 * automation: it can choose to stay silent. Pure and deterministic so the rules
 * (budget, quiet hours, mutes, once-a-day) are unit-tested rather than trusted.
 *
 * It never picks more than one suggestion per call: at most the single most
 * important eligible one, so the user is never flooded.
 */
object ProactiveGovernor {

    fun decide(
        candidates: List<ProactiveSuggestion>,
        settings: ProactiveSettings,
        state: ProactiveState,
        now: LocalDateTime,
    ): ProactiveDecision {
        if (!settings.enabled) return ProactiveDecision.Skip("disabled")
        val st = state.rolledTo(now.toLocalDate())
        if (inQuietHours(now.toLocalTime(), settings.quietStart, settings.quietEnd)) {
            return ProactiveDecision.Skip("quiet_hours")
        }
        if (st.deliveredCount >= settings.maxPerDay) return ProactiveDecision.Skip("budget")

        val eligible = candidates
            .filter { settings.allows(it.kind) }
            .filter { it.dedupKey !in st.deliveredKeys }
        if (eligible.isEmpty()) return ProactiveDecision.Skip("no_candidate")

        // Deterministic pick: highest priority, ties broken by kind name.
        val best = eligible.sortedWith(
            compareByDescending<ProactiveSuggestion> { it.priority }.thenBy { it.kind.name },
        ).first()

        val newState = st.copy(
            deliveredCount = st.deliveredCount + 1,
            deliveredKeys = st.deliveredKeys + best.dedupKey,
        )
        return ProactiveDecision.Deliver(best, newState)
    }

    /**
     * Quiet if [time] falls in the [start, end) window. Handles the usual
     * overnight case where the window wraps past midnight (e.g. 22:00 → 08:00).
     */
    fun inQuietHours(time: LocalTime, start: LocalTime, end: LocalTime): Boolean {
        if (start == end) return false // no quiet window
        return if (start < end) {
            time >= start && time < end
        } else {
            time >= start || time < end
        }
    }
}
