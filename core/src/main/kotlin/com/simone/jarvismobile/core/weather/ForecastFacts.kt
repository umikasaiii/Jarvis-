package com.simone.jarvismobile.core.weather

import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate

/**
 * § JARVIS Implementation Master Plan — PROACTIVITY RELIABILITY CLOSURE WORK
 * PACKAGE D §8 — the normalized, dated forecast fact set the evening
 * proactive weather alert is decided from. Every field is populated by
 * EXPLICIT date matching ([ForecastDateMatcher]), never by array position
 * (§7's exact defect: `codes?.getOrNull(1)` assumed to mean "tomorrow"). A
 * `null` field means the provider genuinely did not supply that value for
 * [targetDate] — a real `0.0` is never collapsed into `null`, and `null` is
 * never collapsed into `0.0` (§8's explicit invariant).
 *
 * [rawWeatherCode] is preserved alongside [category] (the pre-existing,
 * lossy [WeatherCategory] presentation projection — see [WeatherCategory
 * .fromWmoCode]'s own doc comment, which openly folds snow into RAIN) so a
 * hazard policy can classify precipitation kind honestly via
 * [WmoPrecipitationKind] instead of inheriting that collapse (§9).
 */
data class ForecastFacts(
    val targetDate: LocalDate,
    /** Open-Meteo's own `timezone` response field for this request, when captured — never assumed. */
    val providerTimezone: String?,
    /** Opaque location identity (see [WeatherLocationKey.asCacheTag]) — never a raw coordinate (§11/§25). */
    val locationRevision: String,
    /** When this fact set was actually fetched — the basis for [WeatherAlertFreshnessPolicyV2]'s age check. */
    val fetchedAt: Instant,
    /** The provider's raw WMO daily weather code for [targetDate] — never discarded before policy evaluation (§9). */
    val rawWeatherCode: Int?,
    /** The lossy five-bucket presentation projection, kept for UI/emoji continuity — not the hazard-policy input. */
    val category: WeatherCategory?,
    val precipitationSumMm: Double?,
    val rainSumMm: Double?,
    val showersSumMm: Double?,
    val snowfallSumCm: Double?,
    val precipitationProbabilityMaxPercent: Double?,
    val precipitationHours: Double?,
)

/** One hour's aligned precipitation evidence (§17/§6 hourly fields) — used only to confirm/deny a daily storm code. */
data class HourlyPrecipitationEvidence(
    val date: LocalDate,
    /** 0-23, provider-local hour. */
    val hour: Int,
    val rawWeatherCode: Int?,
    val precipitationProbabilityPercent: Double?,
    val rainMm: Double?,
    val showersMm: Double?,
    val precipitationMm: Double?,
)

/**
 * § §9 — precipitation KIND is a different axis from [WeatherCategory]'s
 * presentation bucket. Distinguishes SNOW (which [WeatherCategory] folds
 * into RAIN) and MIXED/icy precipitation from genuine liquid RAIN, so the
 * hazard policy can honestly refuse to say "Domani è prevista pioggia" for
 * a snow-only or mixed forecast.
 */
enum class PrecipitationKind { NONE, RAIN, SNOW, MIXED, UNKNOWN }

/**
 * Classifies a raw Open-Meteo WMO daily code (https://open-meteo.com/en/docs,
 * table "WMO Weather interpretation codes") into a [PrecipitationKind].
 * Deliberately conservative: freezing drizzle/rain (56/57/66/67) and
 * thunderstorm-with-hail (96/99) are MIXED rather than RAIN — §9 requires
 * mixed precipitation to be "represented honestly / conservatively", so this
 * policy version never renders a plain rain claim for them (see
 * [WeatherAlertPolicyV2]). Not independently verified against a live
 * payload in this environment (network-blocked, same limit already
 * documented elsewhere in this project) — the code table itself is
 * long-standing public documentation.
 */
object WmoPrecipitationKind {
    private val NONE_CODES = setOf(0, 1, 2, 3, 45, 48)
    private val RAIN_CODES = setOf(51, 53, 55, 61, 63, 65, 80, 81, 82, 95)
    private val SNOW_CODES = setOf(71, 73, 75, 77, 85, 86)
    private val MIXED_CODES = setOf(56, 57, 66, 67, 96, 99)

    /** Daily storm codes (§17) — 95/96/99, regardless of [PrecipitationKind]. */
    val STORM_CODES = setOf(95, 96, 99)

    fun classify(code: Int?): PrecipitationKind = when {
        code == null -> PrecipitationKind.UNKNOWN
        code in NONE_CODES -> PrecipitationKind.NONE
        code in RAIN_CODES -> PrecipitationKind.RAIN
        code in SNOW_CODES -> PrecipitationKind.SNOW
        code in MIXED_CODES -> PrecipitationKind.MIXED
        else -> PrecipitationKind.UNKNOWN
    }
}

/**
 * § §7 — the ONE place a provider date string is matched against a target
 * [LocalDate]. Never `array[targetOffset]`: [providerDates] may be
 * reordered, shorter than expected, or contain nulls/malformed entries —
 * all handled by falling through to "not found" rather than throwing or
 * guessing a position.
 */
object ForecastDateMatcher {
    /** Open-Meteo's `daily.time`/`hourly.time` dates are ISO `yyyy-MM-dd` (hourly: `yyyy-MM-ddTHH:mm`). */
    fun indexOfDate(providerDates: List<String?>?, targetDate: LocalDate): Int? {
        if (providerDates.isNullOrEmpty()) return null
        val targetStr = targetDate.toString()
        val idx = providerDates.indexOfFirst { it != null && it.take(10) == targetStr }
        return idx.takeIf { it >= 0 }
    }
}

/**
 * Builds a [ForecastFacts] for [targetDate] out of Open-Meteo's parallel
 * daily arrays, using [ForecastDateMatcher] instead of a positional
 * assumption. Returns `null` only when [targetDate] genuinely is not present
 * in [times] — never a partially-filled guess.
 */
object ForecastFactsBuilder {
    fun fromDaily(
        targetDate: LocalDate,
        providerTimezone: String?,
        locationRevision: String,
        fetchedAt: Instant,
        times: List<String?>?,
        weatherCodes: List<Int?>?,
        precipitationSum: List<Double?>?,
        rainSum: List<Double?>?,
        showersSum: List<Double?>?,
        snowfallSum: List<Double?>?,
        precipitationProbabilityMax: List<Double?>?,
        precipitationHours: List<Double?>?,
    ): ForecastFacts? {
        val idx = ForecastDateMatcher.indexOfDate(times, targetDate) ?: return null
        fun <T> List<T?>?.at(i: Int): T? = this?.getOrNull(i)
        val rawCode = weatherCodes.at(idx)
        return ForecastFacts(
            targetDate = targetDate,
            providerTimezone = providerTimezone,
            locationRevision = locationRevision,
            fetchedAt = fetchedAt,
            rawWeatherCode = rawCode,
            category = WeatherCategory.fromWmoCode(rawCode),
            precipitationSumMm = precipitationSum.at(idx),
            rainSumMm = rainSum.at(idx),
            showersSumMm = showersSum.at(idx),
            snowfallSumCm = snowfallSum.at(idx),
            precipitationProbabilityMaxPercent = precipitationProbabilityMax.at(idx),
            precipitationHours = precipitationHours.at(idx),
        )
    }
}

/**
 * § §11/§23/§35 — a stable digest of the normalized facts actually used for
 * a decision, persisted in [com.simone.jarvismobile.weather.receipt.ForecastDecisionReceiptEntity]
 * (§23's "normalized input hash") and reproduced by replay (§35's "stable
 * facts hash") — never the raw provider JSON, never coordinates.
 */
object ForecastFactsHash {
    fun of(facts: ForecastFacts): String {
        val raw = listOf(
            facts.targetDate.toString(),
            facts.locationRevision,
            facts.rawWeatherCode?.toString() ?: "null",
            facts.precipitationSumMm?.toString() ?: "null",
            facts.rainSumMm?.toString() ?: "null",
            facts.showersSumMm?.toString() ?: "null",
            facts.snowfallSumCm?.toString() ?: "null",
            facts.precipitationProbabilityMaxPercent?.toString() ?: "null",
            facts.precipitationHours?.toString() ?: "null",
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
