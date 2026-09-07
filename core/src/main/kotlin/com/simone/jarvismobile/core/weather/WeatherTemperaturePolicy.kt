package com.simone.jarvismobile.core.weather

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 7 §5 (JARVIS-15, qualitative
 * weather policy). Audit finding: no hot/cold classification existed
 * anywhere in this codebase — "farà caldo domani?" reached the model with
 * only the raw temperature numbers in the tool's spoken text, and whatever
 * yes/no judgement the model produced was entirely its own, with no
 * deterministic, app-owned policy backing it and no structural guarantee the
 * real number even survived into the final answer.
 *
 * This is a small, EXPLICIT, VERSIONED policy boundary — never a single
 * invented cutover like "temperature >= X → hot" (the exact anti-pattern the
 * passage warns against). [ThermalComfortBand] uses widely-cited general
 * daytime-comfort bands for the Italian public (the same kind of "common
 * convention, not a per-user calibration" reasoning already used by
 * [RainDecision.MIN_MILLIMETERS]) — an approximation for ordinary
 * conversation, never a scientific/medical claim, and [POLICY_VERSION]
 * exists so a future, better-justified revision changes behavior
 * traceably instead of silently.
 *
 * Critical invariant this whole type exists to enforce: a qualitative
 * classification is NEVER returned on its own — [classify] always pairs the
 * [ThermalComfortBand] with the exact [WeatherQualitativeResult.supportingTempC]
 * that produced it, so a caller can never present "sì, farà caldo" without
 * the real number right there to check it against.
 */
enum class ThermalComfortBand { COLD, COOL, MILD, WARM, HOT }

data class WeatherQualitativeResult(
    val band: ThermalComfortBand,
    val supportingTempC: Double,
    val policyVersion: Int,
)

object WeatherTemperaturePolicy {
    const val POLICY_VERSION = 1

    /**
     * [referenceTempC] must already be the single most representative value
     * for the question being answered — this function does not choose
     * between current/max/min itself (§ caller's job, e.g. a day's max for
     * "farà caldo domani?", never the overnight low, since a comfort
     * question is almost always about the daytime peak). Bands (Celsius):
     * COLD < 5, COOL < 15, MILD < 22, WARM < 30, HOT >= 30.
     */
    fun classify(referenceTempC: Double): WeatherQualitativeResult {
        val band = when {
            referenceTempC < 5.0 -> ThermalComfortBand.COLD
            referenceTempC < 15.0 -> ThermalComfortBand.COOL
            referenceTempC < 22.0 -> ThermalComfortBand.MILD
            referenceTempC < 30.0 -> ThermalComfortBand.WARM
            else -> ThermalComfortBand.HOT
        }
        return WeatherQualitativeResult(band, referenceTempC, POLICY_VERSION)
    }
}

/** Short, spoken-friendly Italian label — same style/place as [WeatherCategory.italianLabel]. */
val ThermalComfortBand.italianLabel: String
    get() = when (this) {
        ThermalComfortBand.COLD -> "freddo"
        ThermalComfortBand.COOL -> "fresco"
        ThermalComfortBand.MILD -> "mite"
        ThermalComfortBand.WARM -> "caldo"
        ThermalComfortBand.HOT -> "molto caldo"
    }
