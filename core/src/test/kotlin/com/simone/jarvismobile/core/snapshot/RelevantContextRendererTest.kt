package com.simone.jarvismobile.core.snapshot

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RelevantContextRendererTest {

    private val now = Instant.parse("2026-01-01T10:00:00Z")

    @Test
    fun `empty context renders to empty text`() {
        assertEquals("", RelevantContextRenderer.render(RelevantPersonalContext()))
    }

    @Test
    fun `temporal renders a readable line`() {
        val ctx = RelevantPersonalContext(temporal = TemporalContext(LocalDate.of(2026, 1, 1), LocalTime.of(10, 30), DayOfWeek.THURSDAY, "MATTINA", "Europe/Rome", now))
        val text = RelevantContextRenderer.render(ctx)
        assertTrue(text.contains("10:30") || text.contains("10"))
        assertTrue(text.contains("Ora:"))
    }

    @Test
    fun `driving renders eta only when actually driving`() {
        val notDriving = RelevantPersonalContext(driving = DrivingContext(isDriving = false, capturedAt = now))
        assertEquals("", RelevantContextRenderer.render(notDriving))

        val driving = RelevantPersonalContext(driving = DrivingContext(isDriving = true, destination = "Ufficio", etaMinutes = 10, capturedAt = now))
        val text = RelevantContextRenderer.render(driving)
        assertTrue(text.contains("Ufficio"))
        assertTrue(text.contains("10"))
    }

    @Test
    fun `renderForCore never includes raw place names, only the semantic label`() {
        val ctx = RelevantPersonalContext(location = LocationContext(PlaceLabel.HOME, "Via Roma 1", null, MovementState.STATIONARY, now))
        val map = RelevantContextRenderer.renderForCore(ctx)
        assertEquals("HOME", map["current_place"])
        assertFalse(map.values.any { it.contains("Via Roma") })
    }

    @Test
    fun `renderForCore sends minutes not the full agenda detail when minimized`() {
        val ctx = RelevantPersonalContext(agenda = AgendaContext(nextEvent = null, minutesToNextEvent = 35, capturedAt = now))
        val map = RelevantContextRenderer.renderForCore(ctx)
        assertEquals("35", map["next_event_minutes"])
        assertFalse(map.containsKey("next_event_title"))
    }

    @Test
    fun `memory items appear in the local render but never in the core map`() {
        val ctx = RelevantPersonalContext(memory = MemoryContext(items = listOf(MemoryContextItem("preferisce il tè")), capturedAt = now))
        assertTrue(RelevantContextRenderer.render(ctx).contains("preferisce il tè"))
        assertTrue(RelevantContextRenderer.renderForCore(ctx).values.none { it.contains("tè") })
    }
}
