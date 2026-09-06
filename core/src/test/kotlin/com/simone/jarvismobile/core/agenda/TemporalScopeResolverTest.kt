package com.simone.jarvismobile.core.agenda

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class TemporalScopeResolverTest {

    // Thursday 2026-08-06.
    private val now = LocalDateTime.of(2026, 8, 6, 10, 0)

    @Test
    fun `oggi resolves to a single date`() {
        assertEquals(TemporalScope.Date(LocalDate.of(2026, 8, 6)), TemporalScopeResolver.resolve("Che impegni ho oggi?", now))
    }

    @Test
    fun `domani resolves to a single date`() {
        assertEquals(TemporalScope.Date(LocalDate.of(2026, 8, 7)), TemporalScopeResolver.resolve("Che impegni ho domani?", now))
    }

    @Test
    fun `dopodomani resolves to a single date`() {
        assertEquals(TemporalScope.Date(LocalDate.of(2026, 8, 8)), TemporalScopeResolver.resolve("E dopodomani?", now))
    }

    @Test
    fun `tra 3 giorni resolves to a single date`() {
        assertEquals(TemporalScope.Date(LocalDate.of(2026, 8, 9)), TemporalScopeResolver.resolve("E tra tre giorni?", now))
    }

    @Test
    fun `a weekday name resolves to its next occurrence`() {
        assertEquals(TemporalScope.Date(LocalDate.of(2026, 8, 7)), TemporalScopeResolver.resolve("Che tempo farà venerdì?", now))
    }

    @Test
    fun `questo weekend resolves to the current week Saturday-Sunday`() {
        assertEquals(
            TemporalScope.Range(LocalDate.of(2026, 8, 8), LocalDate.of(2026, 8, 9)),
            TemporalScopeResolver.resolve("Che impegni ho questo weekend?", now),
        )
    }

    @Test
    fun `questa settimana resolves to the current week range`() {
        assertEquals(
            TemporalScope.Range(LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 9)),
            TemporalScopeResolver.resolve("Cosa ho questa settimana?", now),
        )
    }

    @Test
    fun `settimana prossima resolves to next week range`() {
        assertEquals(
            TemporalScope.Range(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 16)),
            TemporalScopeResolver.resolve("E durante tutta la settimana prossima?", now),
        )
    }

    @Test
    fun `tra oggi e venerdi resolves to an explicit interval`() {
        assertEquals(
            TemporalScope.Range(LocalDate.of(2026, 8, 6), LocalDate.of(2026, 8, 7)),
            TemporalScopeResolver.resolve("Che impegni ho tra oggi e venerdì?", now),
        )
    }

    @Test
    fun `da lunedi a giovedi resolves to an explicit interval`() {
        // Monday of the following week (lunedì hasn't happened yet this week
        // relative to Thursday) through the Thursday after it.
        assertEquals(
            TemporalScope.Range(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 13)),
            TemporalScopeResolver.resolve("Da lunedì a giovedì?", now),
        )
    }

    @Test
    fun `a reversed interval is normalized start before end`() {
        // "da venerdì a oggi" would otherwise put a later date first.
        val scope = TemporalScopeResolver.resolve("Da venerdì a oggi?", now) as TemporalScope.Range
        assert(!scope.end.isBefore(scope.start))
    }

    @Test
    fun `plain text with no temporal words resolves to None`() {
        assertEquals(TemporalScope.None, TemporalScopeResolver.resolve("Che differenza c'è tra RAM e VRAM?", now))
    }
}
