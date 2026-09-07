package com.simone.jarvismobile.weather

import com.simone.jarvismobile.core.weather.WeatherCategory
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * JSON round-trip for [WeeklyOutlook] (§ tema Atena: "il meteo deve essere
 * aggiornato ogni tanto, e salvato temporaneamente in locale, e poi
 * sovrascritto con quelli nuovi"). Deliberately its own small DTO instead of
 * annotating [WeeklyOutlook]/[DayOutlook] with `@Serializable` directly: those
 * are the live domain model `WeatherManager`/`AresViewModel` already pass
 * around, and [WeatherCategory] lives in `:core` — this stays entirely inside
 * `app/weather`, storing the category by name (a plain string, stable across
 * enum reorderings) instead of reaching into `:core` just to persist one field.
 * A cache entry decode failure or an unrecognised category name never crashes
 * — it just means "nothing cached", the same honest fallback as a fetch
 * failure.
 *
 * § JARVIS Implementation Master Plan — PASSAGGIO 7 §1/§3 (JARVIS-06). Real
 * bug found in the audit: this cache carried a forecast and a timestamp but
 * NO identifier of which location the forecast was actually for — since the
 * user's chosen weather place (or the GPS/network fallback fix) can change
 * between two calls, a cached forecast for location A could be silently read
 * back and presented as if it were for location B. [CachedOutlookEntry.locationTag]
 * (a [com.simone.jarvismobile.core.weather.WeatherLocationKey.asCacheTag],
 * never an exact coordinate) closes that gap — [outlookFromCacheJson] now
 * returns it alongside the outlook so the caller ([WeatherManager.cachedOutlook])
 * can refuse to use a cache entry whose location doesn't match the one
 * currently resolved. A cache entry written before this migration has no
 * location tag (`null`) — treated as "unknown location", never assumed to
 * match the current one (§1: "do not silently use an unrelated old location").
 */
private val json = Json { ignoreUnknownKeys = true }

@Serializable
private data class CachedDay(
    val category: String? = null,
    val tempMaxC: Double? = null,
    val tempMinC: Double? = null,
    val windKmh: Double? = null,
    val windDirectionDeg: Double? = null,
)

@Serializable
private data class CachedOutlook(
    val currentTempC: Double? = null,
    val currentCategory: String? = null,
    val currentWindKmh: Double? = null,
    val currentWindDirectionDeg: Double? = null,
    val currentIsDay: Boolean? = null,
    val upcoming: List<CachedDay> = emptyList(),
    @SerialName("cachedAtEpochMs")
    val cachedAtEpochMs: Long = 0L,
    /** § PASSAGGIO 7 §1 — absent (`null`) for any cache entry written before this migration. */
    val locationTag: String? = null,
)

/** A decoded cache entry together with the metadata [WeatherManager] needs to decide whether it may actually be used (§ PASSAGGIO 7 §1/§3). */
data class CachedOutlookEntry(val outlook: WeeklyOutlook, val locationTag: String?, val cachedAtEpochMs: Long)

private fun WeatherCategory?.toName(): String? = this?.name

private fun String?.toCategory(): WeatherCategory? =
    this?.let { name -> runCatching { WeatherCategory.valueOf(name) }.getOrNull() }

fun WeeklyOutlook.toCacheJson(cachedAtEpochMs: Long, locationTag: String?): String = json.encodeToString(
    CachedOutlook.serializer(),
    CachedOutlook(
        currentTempC = currentTempC,
        currentCategory = currentCategory.toName(),
        currentWindKmh = currentWindKmh,
        currentWindDirectionDeg = currentWindDirectionDeg,
        currentIsDay = currentIsDay,
        upcoming = upcoming.map {
            CachedDay(
                category = it.category.toName(),
                tempMaxC = it.tempMaxC,
                tempMinC = it.tempMinC,
                windKmh = it.windKmh,
                windDirectionDeg = it.windDirectionDeg,
            )
        },
        cachedAtEpochMs = cachedAtEpochMs,
        locationTag = locationTag,
    ),
)

/** Null when the string is blank or fails to decode — never a guessed outlook. */
fun outlookFromCacheJson(raw: String): CachedOutlookEntry? {
    if (raw.isBlank()) return null
    val cached = runCatching { json.decodeFromString(CachedOutlook.serializer(), raw) }.getOrNull() ?: return null
    val outlook = WeeklyOutlook(
        currentTempC = cached.currentTempC,
        currentCategory = cached.currentCategory.toCategory(),
        currentWindKmh = cached.currentWindKmh,
        currentWindDirectionDeg = cached.currentWindDirectionDeg,
        currentIsDay = cached.currentIsDay,
        upcoming = cached.upcoming.map {
            DayOutlook(
                category = it.category.toCategory(),
                tempMaxC = it.tempMaxC,
                tempMinC = it.tempMinC,
                windKmh = it.windKmh,
                windDirectionDeg = it.windDirectionDeg,
            )
        },
    )
    return CachedOutlookEntry(outlook, cached.locationTag, cached.cachedAtEpochMs)
}
