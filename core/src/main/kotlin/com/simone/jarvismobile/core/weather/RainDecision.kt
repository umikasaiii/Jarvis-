package com.simone.jarvismobile.core.weather

/**
 * Turns a day's [WeatherCategory] plus its expected accumulation into the
 * yes/no/unknown the engine's three-valued conditions need (§
 * Condition.RainToday/RainTomorrow) and the proactive digest reads.
 *
 * Kept pure and separate from the Android weather fetch so the rule — what
 * counts as "it's going to rain" worth mentioning — is a fact about the
 * product, not about whichever HTTP client happens to fetch it, and is
 * unit-tested here rather than only reasoned about inside a network call.
 *
 * This used to threshold a raw daily *mean* rain probability against expected
 * millimetres. That produced near-nightly false "domani pioverà" warnings on
 * otherwise dry days — worse, a separate bug meant the rule-builder's "Se
 * domani piove" toggle never actually reached the saved rule at all (its
 * value was dropped building the draft), so the condition silently evaluated
 * as "always true" regardless of any threshold. Both are fixed now: the
 * toggle is wired through, and this object trusts Open-Meteo's own daily
 * weather-code classification ([WeatherCategory]) — the model's single best
 * summary of the day — instead of an aggregate this app invented and then had
 * to keep re-tuning thresholds for.
 */
object RainDecision {

    /**
     * Below this a [WeatherCategory.RAIN] day carries no measurable
     * accumulation — common meteorological convention for "trace"
     * precipitation not worth an "avviso pioggia". Not applied to
     * [WeatherCategory.THUNDERSTORM]: the model does not assign that code
     * lightly, so a forecast storm is worth a warning regardless of total mm.
     */
    const val MIN_MILLIMETERS = 0.2

    /**
     * [category] null (fetch failed, or a code outside the documented table)
     * keeps the whole call unknown, never collapsing into "no rain". A
     * [WeatherCategory.RAIN] day additionally needs [millimeters] to clear
     * [MIN_MILLIMETERS] — missing millimetres for a RAIN day also stays
     * unknown rather than being read as "not enough to matter".
     */
    fun isRainDay(category: WeatherCategory?, millimeters: Double?): Boolean? = when (category) {
        null -> null
        WeatherCategory.THUNDERSTORM -> true
        WeatherCategory.RAIN -> millimeters?.let { it >= MIN_MILLIMETERS }
        WeatherCategory.CLEAR, WeatherCategory.PARTLY_CLOUDY, WeatherCategory.CLOUDY -> false
    }
}
