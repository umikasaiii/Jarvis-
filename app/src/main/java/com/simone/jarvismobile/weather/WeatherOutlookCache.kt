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
)

private fun WeatherCategory?.toName(): String? = this?.name

private fun String?.toCategory(): WeatherCategory? =
    this?.let { name -> runCatching { WeatherCategory.valueOf(name) }.getOrNull() }

fun WeeklyOutlook.toCacheJson(cachedAtEpochMs: Long): String = json.encodeToString(
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
    ),
)

/** Null when the string is blank or fails to decode — never a guessed outlook. */
fun outlookFromCacheJson(raw: String): WeeklyOutlook? {
    if (raw.isBlank()) return null
    val cached = runCatching { json.decodeFromString(CachedOutlook.serializer(), raw) }.getOrNull() ?: return null
    return WeeklyOutlook(
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
}
