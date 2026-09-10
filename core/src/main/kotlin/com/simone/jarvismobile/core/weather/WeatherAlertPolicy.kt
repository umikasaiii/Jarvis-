package com.simone.jarvismobile.core.weather

import java.time.LocalDate

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 14.2. A bounded hazard
 * decision for the proactive evening-before rain/storm warning — NOT a
 * general weather label, and NOT the same axis as [WeatherCategory] (which
 * only says the day's *dominant* condition, useful for a greeting emoji but
 * not for "is this worth waking someone up about"). Ordered by severity so
 * a later, more severe forecast can be recognised as an escalation over an
 * earlier one, never the reverse — see [WeatherAlertPolicy.evaluate].
 */
enum class WeatherHazard {
    NO_ALERT,
    RAIN_EXPECTED,
    HEAVY_RAIN,
    THUNDERSTORM,
}

/**
 * The result of evaluating tomorrow's structured forecast — either a
 * [Decided] [WeatherHazard] from real data, or [Unknown] when the
 * underlying forecast itself could not be trusted (missing category, or
 * missing accumulation for a RAIN day). Deliberately NEVER collapsed into
 * [WeatherHazard.NO_ALERT] — "we don't know" and "we know it will stay dry"
 * must never be confused (§ "do not invent 'tomorrow it will rain'" applies
 * symmetrically to inventing "it will NOT").
 */
sealed interface WeatherAlertEvaluation {
    data class Decided(val hazard: WeatherHazard) : WeatherAlertEvaluation
    data class Unknown(val reason: String) : WeatherAlertEvaluation
}

/**
 * Turns tomorrow's [WeatherCategory] plus its expected accumulation into a
 * bounded, versioned alert decision — structured facts only, exactly the
 * pair [RainDecision.isRainDay] already consumes for the plain-boolean
 * automation/morning-greeting path (never natural-language text, never an
 * LLM judgement: this function takes no `String` describing the forecast,
 * so a change to how the forecast is *phrased* elsewhere in the app can
 * never move this decision).
 *
 * Thresholds are centralized here, not scattered: [MIN_MEANINGFUL_MILLIMETERS]
 * reuses the exact same "trace precipitation, not worth mentioning" cutoff
 * [RainDecision] already established (and already once had to walk back a
 * false-positive-prone alternative for, § [RainDecision]'s own doc comment)
 * rather than a second, possibly-drifting copy of the same number.
 * [HEAVY_RAIN_MILLIMETERS] is new: a widely used meteorological
 * rule-of-thumb for a "heavy rain day" daily total (WMO/many national
 * services classify roughly 10mm/24h upward as moderate-to-heavy) — not
 * independently verified against a live provider payload in this
 * environment (network-blocked, the same limit already documented
 * elsewhere in this project for Open-Meteo/TomTom/Health Connect), a
 * deliberately conservative, documented estimate rather than an invented
 * certainty.
 */
object WeatherAlertPolicy {
    const val POLICY_VERSION = 1

    val MIN_MEANINGFUL_MILLIMETERS: Double = RainDecision.MIN_MILLIMETERS
    const val HEAVY_RAIN_MILLIMETERS: Double = 10.0

    /**
     * [category] null (fetch failed, stale, or a code outside the documented
     * table) always returns [WeatherAlertEvaluation.Unknown] — never a
     * guessed hazard. [WeatherCategory.THUNDERSTORM] is decided from the
     * category ALONE, independent of [millimeters] — the model does not
     * assign that code lightly (§ [RainDecision]'s own reasoning, reused
     * here), so a thunderstorm is never missed merely because rain quantity
     * happens to be encoded differently or is altogether absent for that
     * code. A [WeatherCategory.RAIN] day additionally needs [millimeters]
     * to classify a tier — missing millimetres for a RAIN day stays
     * [WeatherAlertEvaluation.Unknown] rather than being read as "not
     * enough to matter".
     */
    fun evaluate(category: WeatherCategory?, millimeters: Double?): WeatherAlertEvaluation = when (category) {
        null -> WeatherAlertEvaluation.Unknown("no_category")
        WeatherCategory.THUNDERSTORM -> WeatherAlertEvaluation.Decided(WeatherHazard.THUNDERSTORM)
        WeatherCategory.RAIN -> when {
            millimeters == null -> WeatherAlertEvaluation.Unknown("no_millimeters")
            millimeters < MIN_MEANINGFUL_MILLIMETERS -> WeatherAlertEvaluation.Decided(WeatherHazard.NO_ALERT)
            millimeters >= HEAVY_RAIN_MILLIMETERS -> WeatherAlertEvaluation.Decided(WeatherHazard.HEAVY_RAIN)
            else -> WeatherAlertEvaluation.Decided(WeatherHazard.RAIN_EXPECTED)
        }
        WeatherCategory.CLEAR, WeatherCategory.PARTLY_CLOUDY, WeatherCategory.CLOUDY ->
            WeatherAlertEvaluation.Decided(WeatherHazard.NO_ALERT)
    }

    /** The local calendar date an evening evaluation on [evaluationDate] is warning about — always the very next local day, never computed from UTC/an `Instant`. */
    fun targetDateFor(evaluationDate: LocalDate): LocalDate = evaluationDate.plusDays(1)
}
