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
     * [probabilityPercent] is the forecast's own percentage (0..100), or null
     * when the fetch failed or returned nothing usable — which stays unknown
     * here too, never collapsing into "no rain".
     */
    fun fromProbabilityPercent(probabilityPercent: Int?): Boolean? =
        probabilityPercent?.let { it.coerceIn(0, 100) >= THRESHOLD_PERCENT }
}
