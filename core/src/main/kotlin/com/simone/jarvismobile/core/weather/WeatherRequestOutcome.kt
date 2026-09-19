package com.simone.jarvismobile.core.weather

/**
 * § JARVIS Implementation Master Plan — PROACTIVITY RELIABILITY CLOSURE WORK
 * PACKAGE D §10 — a request-scoped typed outcome, replacing the pattern
 * (still used, deliberately unchanged, by
 * [com.simone.jarvismobile.weather.OpenMeteoWeatherSource]'s four EXISTING
 * fetch methods — see that class's own doc comment) of a nullable result
 * plus a single shared `@Volatile lastErrorType` field: a concurrent
 * outlook/hourly/dated-forecast request there can overwrite another
 * request's error identity before its own caller reads it — an accepted,
 * disclosed approximation for the pre-existing single-caller-at-a-time
 * paths, but not acceptable for the new dated-forecast path Work Package D
 * adds, which two evaluations (e.g. a real evening tick and a debug/shadow
 * evaluation, §32) could genuinely run concurrently against.
 */
sealed interface WeatherRequestOutcome<out T> {
    data class Success<T>(val value: T) : WeatherRequestOutcome<T>
    data class Failure(val reason: WeatherFailureReason, val detail: String? = null) : WeatherRequestOutcome<Nothing>
}

/** § §10 — the bounded failure taxonomy for a single weather request. */
enum class WeatherFailureReason {
    NETWORK,
    INVALID_RESPONSE,
    NO_LOCATION,
    PERMISSION_UNAVAILABLE,
    DISABLED,
    DATE_MISMATCH,
    LOCATION_MISMATCH,
    INVALID_UNITS_OR_DATA,
}

inline fun <T, R> WeatherRequestOutcome<T>.map(transform: (T) -> R): WeatherRequestOutcome<R> = when (this) {
    is WeatherRequestOutcome.Success -> WeatherRequestOutcome.Success(transform(value))
    is WeatherRequestOutcome.Failure -> this
}

fun <T> WeatherRequestOutcome<T>.valueOrNull(): T? = (this as? WeatherRequestOutcome.Success)?.value
