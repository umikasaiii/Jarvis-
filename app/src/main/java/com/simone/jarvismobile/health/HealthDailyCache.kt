package com.simone.jarvismobile.health

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.LocalDate

/**
 * JSON round-trip for [HealthConnectManager.HealthSnapshot] — same pattern as
 * `com.simone.jarvismobile.weather.WeatherOutlookCache` (§ richiesta esplicita
 * dell'utente: "questi risultati devono aggiornarsi ogni mattina poco dopo il
 * briefing mattutino"): letto una volta all'apertura per uno stato istantaneo,
 * sovrascritto per intero a ogni [HealthConnectManager.refresh] riuscito, mai
 * fuso campo per campo. Un errore di decodifica o un JSON vuoto restituiscono
 * semplicemente "niente in cache", lo stesso fallback onesto di una lettura
 * fallita da Health Connect stesso.
 */
private val json = Json { ignoreUnknownKeys = true }

@Serializable
private data class CachedDay(
    val date: String,
    val heartRateBpm: Long? = null,
    val sleepHours: Double? = null,
)

@Serializable
private data class CachedSnapshot(
    val daily: List<CachedDay> = emptyList(),
    val avgHeartRateBpm: Long? = null,
    val avgSleepMinutes: Long? = null,
    val cachedAtEpochMs: Long = 0L,
)

fun HealthConnectManager.HealthSnapshot.toCacheJson(cachedAtEpochMs: Long): String = json.encodeToString(
    CachedSnapshot.serializer(),
    CachedSnapshot(
        daily = daily.map { CachedDay(it.date.toString(), it.heartRateBpm, it.sleepHours) },
        avgHeartRateBpm = averages.avgHeartRateBpm,
        avgSleepMinutes = averages.avgSleepPerNight?.toMinutes(),
        cachedAtEpochMs = cachedAtEpochMs,
    ),
)

/** Null when the string is blank or fails to decode — never a guessed snapshot. */
fun healthSnapshotFromCacheJson(raw: String): HealthConnectManager.HealthSnapshot? {
    if (raw.isBlank()) return null
    val cached = runCatching { json.decodeFromString(CachedSnapshot.serializer(), raw) }.getOrNull() ?: return null
    val daily = cached.daily.mapNotNull { d ->
        val date = runCatching { LocalDate.parse(d.date) }.getOrNull() ?: return@mapNotNull null
        HealthConnectManager.DailyHealthReading(date, d.heartRateBpm, d.sleepHours)
    }
    return HealthConnectManager.HealthSnapshot(
        daily = daily,
        averages = HealthConnectManager.WeeklyHealthAverages(
            avgHeartRateBpm = cached.avgHeartRateBpm,
            avgSleepPerNight = cached.avgSleepMinutes?.let { Duration.ofMinutes(it) },
        ),
    )
}
