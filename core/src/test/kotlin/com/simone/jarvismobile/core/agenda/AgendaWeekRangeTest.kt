package com.simone.jarvismobile.core.agenda

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgendaWeekRangeTest {

    // Thursday 2026-08-06 — Monday of that week is 2026-08-03.
    private val now = LocalDateTime.of(2026, 8, 6, 10, 0)

    @Test
    fun `next week resolves to next Monday through Sunday`() {
        val range = AgendaWeekRange.resolve("E durante tutta la settimana prossima?", now)
        assertEquals(LocalDate.of(2026, 8, 10)..LocalDate.of(2026, 8, 16), range)
    }

    @Test
    fun `la prossima settimana phrasing is also recognized`() {
        val range = AgendaWeekRange.resolve("Cosa ho la prossima settimana?", now)
        assertEquals(LocalDate.of(2026, 8, 10)..LocalDate.of(2026, 8, 16), range)
    }

    @Test
    fun `last week resolves to the prior Monday through Sunday`() {
        val range = AgendaWeekRange.resolve("Che impegni avevo la settimana scorsa?", now)
        assertEquals(LocalDate.of(2026, 7, 27)..LocalDate.of(2026, 8, 2), range)
    }

    @Test
    fun `this week resolves to the current Monday through Sunday`() {
        val range = AgendaWeekRange.resolve("Cosa ho questa settimana?", now)
        assertEquals(LocalDate.of(2026, 8, 3)..LocalDate.of(2026, 8, 9), range)
    }

    @Test
    fun `a bare single-day phrase names no week`() {
        assertNull(AgendaWeekRange.resolve("Che impegni ho domani?", now))
    }
}
