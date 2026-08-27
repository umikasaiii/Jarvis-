package com.simone.jarvismobile.core.weather

/**
 * Turns a forecast probability into the yes/no/unknown the engine's three-valued
 * conditions need (§ Condition.RainToday/RainTomorrow).
 *
 * Kept pure and separate from the Android weather fetch so the threshold — what
 * counts as "it's going to rain" worth mentioning — is a fact about the product,
 * not about whichever HTTP client happens to fetch it, and is unit-tested here
 * rather than only reasoned about inside a network call.
 */
object RainDecision {

    /** Below this the forecast is "probably not", not worth an "avviso pioggia". */
    const val THRESHOLD_PERCENT = 50

    /**
     * Below this a day carries no measurable accumulation — common meteorological
     * convention for "trace" precipitation that a forecast can show a non-zero
     * chance for without it amounting to anything worth mentioning.
     */
    const val MIN_MILLIMETERS = 0.2

    /**
     * [probabilityPercent] is the forecast's own percentage (0..100), or null
     * when the fetch failed or returned nothing usable — which stays unknown
     * here too, never collapsing into "no rain".
     */
    fun fromProbabilityPercent(probabilityPercent: Int?): Boolean? =
        probabilityPercent?.let { it.coerceIn(0, 100) >= THRESHOLD_PERCENT }

    /**
     * The combined, more reliable check: requires both a meaningful *average*
     * chance across the whole day and a measurable expected accumulation to
     * agree. A single-hour probability spike (a `..._max` aggregate) can clear
     * 50% on an otherwise dry day — that was the source of near-nightly false
     * "domani pioverà" warnings; requiring the day's mean probability together
     * with actual millimetres catches that. Either signal missing keeps the
     * whole call unknown, same discipline as [fromProbabilityPercent].
     */
    fun fromForecast(meanProbabilityPercent: Int?, precipitationMillimeters: Double?): Boolean? {
        val probability = meanProbabilityPercent ?: return null
        val millimeters = precipitationMillimeters ?: return null
        return probability.coerceIn(0, 100) >= THRESHOLD_PERCENT && millimeters >= MIN_MILLIMETERS
    }
}
