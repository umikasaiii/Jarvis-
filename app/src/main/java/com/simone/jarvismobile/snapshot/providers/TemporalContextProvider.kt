package com.simone.jarvismobile.snapshot.providers

import com.simone.jarvismobile.core.agenda.DayPeriod
import com.simone.jarvismobile.core.snapshot.TemporalContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Live system clock read — no Android dependency needed, but kept behind an interface for consistency and testability (§ convenzione "Interfaces first... Fakes for tests"). */
fun interface TemporalContextProvider {
    suspend fun provide(): TemporalContext?
}

@Singleton
class DefaultTemporalContextProvider @Inject constructor() : TemporalContextProvider {
    override suspend fun provide(): TemporalContext {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        val period = DayPeriod.entries.firstOrNull { it.contains(now.toLocalTime()) }
        return TemporalContext(
            date = now.toLocalDate(),
            time = now.toLocalTime(),
            dayOfWeek = now.dayOfWeek,
            dayPeriod = period?.name,
            timezoneId = zone.id,
            capturedAt = Instant.now(),
        )
    }
}
