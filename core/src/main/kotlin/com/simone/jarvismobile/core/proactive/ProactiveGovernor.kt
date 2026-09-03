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
        val quiet = inQuietHours(now.toLocalTime(), settings.quietStart, settings.quietEnd)
        if (st.deliveredCount >= settings.maxPerDay) return ProactiveDecision.Skip("budget")

        // Bug reale segnalato dall'utente: "non è arrivato briefing" — con le
        // impostazioni predefinite (ore silenziose 22:00→08:00) l'intera
        // finestra in cui il digest mattutino viene offerto (MORNING_EARLIEST_HOUR
        // = 5, § ProactiveManager) cade DENTRO le ore silenziose di default,
        // quindi chiunque si svegli prima delle 8 — la fascia oraria più comune
        // in assoluto — non riceveva mai il briefing pur avendo tutto
        // correttamente abilitato. I digest non sono un consiglio facoltativo
        // ma contenuto che l'utente ha esplicitamente scelto di ricevere
        // (§ già distinto altrove per il canale di notifica, fase 6f) — le
        // ore silenziose restano piene per un vero suggerimento facoltativo
        // come BATTERY_BEFORE_ALARM, ma non possono mai bloccare un digest.
        val eligible = candidates
            .filter { settings.allows(it.kind) }
            .filter { it.dedupKey !in st.deliveredKeys }
            .filter { !quiet || it.kind in QUIET_HOURS_EXEMPT }
        if (eligible.isEmpty()) return ProactiveDecision.Skip(if (quiet) "quiet_hours" else "no_candidate")

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

    /** Digest kinds the user explicitly opted into — never muted by quiet hours. */
    private val QUIET_HOURS_EXEMPT = setOf(ProactiveKind.MORNING_DIGEST, ProactiveKind.EVENING_DIGEST)
}
