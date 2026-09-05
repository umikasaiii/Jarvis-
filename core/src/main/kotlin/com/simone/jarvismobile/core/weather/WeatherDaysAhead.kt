package com.simone.jarvismobile.core.weather

/**
 * § FASE 2A.7 RELEASE GATE 3 — "Che tempo farà tra 10 giorni?" used to be
 * silently answered as if it meant "tra 3 giorni": the conversational
 * engine's `weatherCall()` computed the real day offset from
 * `ItalianDateTimeParser`, then unconditionally `coerceIn(0, 3)`-clamped it
 * before ever building the tool call — the model (and the user) never had
 * any way to tell "the source genuinely can't forecast that far" apart from
 * "the source silently substituted a different day than the one asked for".
 * `GetWeatherTool.validate()` already rejects an out-of-range `days_ahead`,
 * but only if it is ever allowed to see the REAL value — which the clamp
 * upstream never let it do. This is the single source of truth for "is this
 * day offset one `get_weather` can genuinely answer", used both by the
 * capability-fast-path (to short-circuit honestly, without spending an LLM
 * round) and by the tool's own `validate()` (as a fail-safe if a call
 * somehow reaches it with an out-of-range value regardless).
 */
object WeatherDaysAhead {

    /** [GetWeatherTool][com.simone.jarvismobile.tools.GetWeatherTool]'s own supported range: today through 3 days ahead. */
    const val MAX_SUPPORTED_DAYS_AHEAD = 3

    sealed interface Resolution {
        /** [daysAhead] is within the source's real range and may be asked for as-is. */
        data class Supported(val daysAhead: Int) : Resolution

        /**
         * [requestedDaysAhead] is what the user actually asked for, preserved
         * verbatim for an honest message/diagnostic — never silently replaced
         * by [MAX_SUPPORTED_DAYS_AHEAD] or any other in-range value.
         */
        data class OutOfRange(val requestedDaysAhead: Int) : Resolution
    }

    fun resolve(requestedDaysAhead: Int): Resolution =
        if (requestedDaysAhead in 0..MAX_SUPPORTED_DAYS_AHEAD) {
            Resolution.Supported(requestedDaysAhead)
        } else {
            Resolution.OutOfRange(requestedDaysAhead)
        }
}
