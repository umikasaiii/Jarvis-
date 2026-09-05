package com.simone.jarvismobile.core.presentation

import kotlin.math.roundToInt

/**
 * § FASE 2A.8 RELEASE GATE I — "deterministic acquisition, deterministic
 * ≠ robotic": [com.simone.jarvismobile.tools.GetWeatherTool] used to build
 * its spoken text with ONE fixed template every time, so the exact same
 * fields always produced the exact same sentence. This is a pure
 * presentation layer over ALREADY-GROUNDED structured data (category/
 * current-temp/max/min/wind — the very same fields `GetWeatherTool` already
 * reads from [com.simone.jarvismobile.weather.DayOutlook]/`WeeklyOutlook`)
 * — it NEVER invents a field: every field is optional and simply omitted
 * from the sentence when null, exactly like the fixed template it
 * replaces. Never routes through the LLM (§ explicit constraint: "mai
 * passare ogni previsione semplice al modello lento") — the variation
 * comes from [templateIndex], a caller-supplied pick (the caller — impure
 * by nature, e.g. `kotlin.random.Random` — decides which of
 * [TEMPLATE_COUNT] templates to use; this function itself stays pure and
 * deterministic for a given index, so it is fully unit-testable).
 */
object WeatherPhrasing {

    /** How many distinct phrasings [render] can produce — callers pick e.g. `Random.nextInt(TEMPLATE_COUNT)`. */
    const val TEMPLATE_COUNT = 4

    /**
     * [dayLabel] is already-resolved Italian ("oggi"/"domani"/"tra 5
     * giorni"). [currentTempC] is used only for a live "oggi" reading;
     * [tempMaxC]/[tempMinC] are used for any day, including a future one
     * with no "current" concept — a template prefers whichever it has,
     * never both stated redundantly as if they were the same number.
     */
    fun render(
        templateIndex: Int,
        dayLabel: String,
        category: String?,
        currentTempC: Double?,
        tempMaxC: Double?,
        tempMinC: Double?,
        windKmh: Double?,
    ): String {
        val day = dayLabel.replaceFirstChar { it.uppercase() }
        val tempPhrase = when {
            currentTempC != null -> "${currentTempC.roundToInt()}°C"
            tempMaxC != null && tempMinC != null -> "tra ${tempMinC.roundToInt()}°C e ${tempMaxC.roundToInt()}°C"
            tempMaxC != null -> "massima ${tempMaxC.roundToInt()}°C"
            tempMinC != null -> "minima ${tempMinC.roundToInt()}°C"
            else -> null
        }
        val windPhrase = windKmh?.let { "vento a ${it.roundToInt()} km/h" }
        val facts = listOfNotNull(category, tempPhrase, windPhrase)
        if (facts.isEmpty()) return "$day: nessun dato meteo disponibile."

        return when (Math.floorMod(templateIndex, TEMPLATE_COUNT)) {
            0 -> "$day: ${facts.joinToString(", ")}."
            1 -> {
                val lowered = listOfNotNull(category?.lowercase(), tempPhrase?.let { "temperatura $it" }, windPhrase)
                "$day, ${lowered.joinToString(", ")}."
            }
            2 -> {
                val rest = listOfNotNull(category?.let { "cielo ${it.lowercase()}" }, tempPhrase, windPhrase)
                "$day: ${rest.joinToString(" e ")}."
            }
            else -> "$day — ${facts.joinToString(" · ")}."
        }
    }
}
