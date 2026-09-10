package com.simone.jarvismobile.core.weather

import com.simone.jarvismobile.core.snapshot.SnapshotFreshnessPolicy
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 14.2. The one staleness
 * threshold every weather-derived fact in this app shares — today/tomorrow
 * rain, the weather category, and now the evening rain/storm alert —
 * centralized here instead of a private constant duplicated inside
 * [com.simone.jarvismobile.context.ContextEngine] (previously
 * `WEATHER_STALE_HOURS`, reimplemented inline) and re-invented again for
 * the new evening alert. Reuses [SnapshotFreshnessPolicy]'s own pure age
 * arithmetic instead of a second implementation — this object only adds
 * the ONE number ([STALE_AFTER_HOURS]) specific to weather.
 *
 * Deliberately operates on [LocalDateTime] (the same local wall-clock type
 * [com.simone.jarvismobile.context.ContextEngine] already stores
 * `weatherUpdatedAt` as) rather than [java.time.Instant] — both timestamps
 * being compared are always produced by the SAME device clock
 * (`LocalDateTime.now()`), so converting through a zone for the comparison
 * itself introduces no correctness difference, only an extra step; the
 * conversion here exists solely to reuse [SnapshotFreshnessPolicy]'s
 * `Instant`-based signature without a second staleness algorithm.
 */
object WeatherFreshnessPolicy {
    const val STALE_AFTER_HOURS = 6L
    private const val STALE_AFTER_MS = STALE_AFTER_HOURS * 3_600_000L

    /** [updatedAt] null (never fetched) is always stale — there is nothing to be fresh about. */
    fun isStale(updatedAt: LocalDateTime?, now: LocalDateTime): Boolean {
        if (updatedAt == null) return true
        val zone = ZoneId.systemDefault()
        return SnapshotFreshnessPolicy.isStale(
            updatedAt.atZone(zone).toInstant(),
            STALE_AFTER_MS,
            now.atZone(zone).toInstant(),
        )
    }
}
