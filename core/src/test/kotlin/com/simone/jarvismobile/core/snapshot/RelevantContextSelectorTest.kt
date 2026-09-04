package com.simone.jarvismobile.core.snapshot

import com.simone.jarvismobile.core.ai.AiRequestType
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelevantContextSelectorTest {

    private val now = Instant.parse("2026-01-01T10:00:00Z")

    private fun fullSnapshot(): PersonalIntelligenceSnapshot = PersonalIntelligenceSnapshot(
        snapshotId = "s1",
        createdAt = now,
        temporal = TemporalContext(LocalDate.of(2026, 1, 1), LocalTime.of(10, 0), DayOfWeek.THURSDAY, "MATTINA", "Europe/Rome", now),
        location = LocationContext(PlaceLabel.HOME, "Casa", null, MovementState.STATIONARY, now),
        agenda = AgendaContext(
            nextEvent = AgendaItemSummary("a1", "Dentista", "oggi alle 16:00", 360),
            imminentEvents = listOf(
                AgendaItemSummary("a1", "Dentista", "oggi alle 16:00", 360),
                AgendaItemSummary("a2", "Palestra", "domani alle 18:00", 1800),
            ),
            openTasksCount = 3,
            imminentReminders = listOf(AgendaItemSummary("r1", "Comprare il pane", "oggi", 120)),
            minutesToNextEvent = 360,
            capturedAt = now,
        ),
        driving = DrivingContext(isDriving = true, destination = "Ufficio", etaMinutes = 12, remainingDistanceMeters = 3000, navigationActive = true, relevantDrivingState = "NAVIGATING", capturedAt = now),
        device = DeviceContext(batteryLevel = 42, isCharging = false, networkType = NetworkType.WIFI, isOnline = true, headphonesConnected = false, carMode = true, capturedAt = now),
        memory = MemoryContext(items = listOf(MemoryContextItem("Preferisce il caffè senza zucchero"), MemoryContextItem("Ha un cane di nome Fido")), capturedAt = now),
        recentEvents = RecentEventsContext(events = listOf(com.simone.jarvismobile.core.snapshot.RecentEventSummary("USER_UNLOCKED", now.toEpochMilli(), "NORMAL")), capturedAt = now),
        task = TaskContext(activeTasks = 5, overdueTasks = 1, upcomingTasks = 4, capturedAt = now),
        capability = CapabilityContext(localAiAvailable = true, coreAvailable = false, navigationAvailable = true, memoryAvailable = true, agendaAvailable = true, networkAvailable = true, capturedAt = now),
    )

    @Test
    fun `driving question selects driving location and temporal`() {
        val result = RelevantContextSelector.select(fullSnapshot(), AiRequestType.CHAT, "Sto guidando, quanto manca all'ufficio?", now)
        assertTrue(SelectionCategory.DRIVING in result.selected)
        assertTrue(SelectionCategory.LOCATION in result.selected)
        assertTrue(SelectionCategory.TEMPORAL in result.selected)
        assertFalse(SelectionCategory.DEVICE in result.selected)
    }

    @Test
    fun `agenda question selects agenda temporal and location with full detail`() {
        val result = RelevantContextSelector.select(fullSnapshot(), AiRequestType.CHAT, "Che appuntamenti ho oggi?", now)
        assertTrue(SelectionCategory.AGENDA in result.selected)
        assertEquals("Dentista", result.agenda?.nextEvent?.title)
        assertEquals(2, result.agenda?.imminentEvents?.size)
    }

    @Test
    fun `battery question selects only device`() {
        val result = RelevantContextSelector.select(fullSnapshot(), AiRequestType.CHAT, "Quanta batteria ho?", now)
        assertTrue(SelectionCategory.DEVICE in result.selected)
        assertFalse(SelectionCategory.DRIVING in result.selected)
        assertFalse(SelectionCategory.AGENDA in result.selected)
    }

    @Test
    fun `generic chat with no memory-shaped signal gets temporal only - FASE 2A5`() {
        // § FASE 2A.5 root cause of stale, unrelated memory content bleeding
        // into a brand new question: this used to unconditionally add MEMORY
        // for any uncategorized message (an earlier, explicit design choice,
        // now reverted by real-device evidence it was wrong) - a plain chat
        // message with no memory-related language must not pull in the
        // snapshot's un-filtered "10 most recent records" section at all.
        val result = RelevantContextSelector.select(fullSnapshot(), AiRequestType.CHAT, "Raccontami una barzelletta", now)
        assertTrue(SelectionCategory.TEMPORAL in result.selected)
        assertFalse(SelectionCategory.MEMORY in result.selected)
        assertFalse(SelectionCategory.AGENDA in result.selected)
        assertFalse(SelectionCategory.DRIVING in result.selected)
    }

    @Test
    fun `a memory-shaped question does select memory - FASE 2A5`() {
        val result = RelevantContextSelector.select(fullSnapshot(), AiRequestType.CHAT, "Cosa ho scritto nei miei appunti?", now)
        assertTrue(SelectionCategory.MEMORY in result.selected)
    }

    @Test
    fun `come stai gets no unsolicited memory dump - FASE 2A5`() {
        // § root cause of "Come stai?" -> a templated greeting: not this
        // selector's fault by itself, but a generic chat message must not
        // drag in the snapshot's un-filtered "10 most recent memories"
        // section either, which was the earlier (now reverted) behavior.
        val result = RelevantContextSelector.select(fullSnapshot(), AiRequestType.CHAT, "Come stai?", now)
        assertFalse(SelectionCategory.MEMORY in result.selected)
    }

    @Test
    fun `sequential turns - a debug chat message then an unrelated real question - memory never bleeds through - FASE 2A5`() {
        // § root cause of "Settimana prossima devo comprare qualcosa?"
        // answering with a previous turn's "TEST CORE"/lights content: this
        // object is a stateless singleton (no field carries anything between
        // select() calls), so proving turn 2 is unaffected by turn 1 pins the
        // guarantee explicitly rather than only by code inspection - same
        // pattern as FASE 2A.4's RelevantToolSelector sequential tests.
        val turn1 = RelevantContextSelector.select(fullSnapshot(), AiRequestType.CHAT, "Rispondi solo: TEST CORE", now)
        val turn2 = RelevantContextSelector.select(fullSnapshot(), AiRequestType.CHAT, "Settimana prossima devo comprare qualcosa?", now)
        assertFalse(SelectionCategory.MEMORY in turn1.selected)
        assertFalse(SelectionCategory.MEMORY in turn2.selected)
    }

    @Test
    fun `complex request gets a broader but still capped context`() {
        val budget = ContextBudget(maxContextItems = 20) // isolate the "broad" rule from the item cap for this assertion
        val result = RelevantContextSelector.select(fullSnapshot(), AiRequestType.COMPLEX, "Aiutami a pianificare la giornata", now, budget)
        assertTrue(SelectionCategory.AGENDA in result.selected)
        assertTrue(SelectionCategory.MEMORY in result.selected)
        assertTrue(SelectionCategory.DEVICE in result.selected)
    }

    @Test
    fun `maxContextItems caps the number of selected categories, temporal always survives`() {
        val budget = ContextBudget(maxContextItems = 2)
        val result = RelevantContextSelector.select(fullSnapshot(), AiRequestType.COMPLEX, "Aiutami a pianificare la giornata", now, budget)
        assertTrue(result.selected.size <= 2)
        assertTrue(SelectionCategory.TEMPORAL in result.selected)
    }

    @Test
    fun `maxMemoryItems and maxAgendaItems trim list sizes`() {
        val budget = ContextBudget(maxMemoryItems = 1, maxAgendaItems = 1)
        val result = RelevantContextSelector.select(fullSnapshot(), AiRequestType.CHAT, "Che impegni ho?", now, budget)
        assertEquals(1, result.agenda?.imminentEvents?.size)
    }

    @Test
    fun `tiny character budget trims list items without truncating any string`() {
        val budget = ContextBudget(maxSerializedCharacters = 5)
        val result = RelevantContextSelector.select(fullSnapshot(), AiRequestType.COMPLEX, "Aiutami con tutto", now, budget)
        // Every remaining string field must be intact (never a partial cut mid-word).
        result.agenda?.nextEvent?.let { assertTrue(it.title == "Dentista" || it.title.isEmpty()) }
        result.memory?.items?.forEach { assertTrue(it.summary.isNotBlank()) }
    }

    @Test
    fun `privacy minimization drops agenda item detail when agenda is only incidentally included`() {
        val result = RelevantContextSelector.select(fullSnapshot(), AiRequestType.COMPLEX, "Aiutami a organizzare il pomeriggio", now)
        // Agenda selected via the "broad" bucket, not a direct agenda question — minutesToNextEvent survives, full titles do not.
        assertEquals(360L, result.agenda?.minutesToNextEvent)
        assertNull(result.agenda?.nextEvent)
        assertTrue(result.agenda?.imminentEvents?.isEmpty() != false)
    }

    @Test
    fun `location never carries raw coordinates - the model itself has no such field`() {
        val result = RelevantContextSelector.select(fullSnapshot(), AiRequestType.CHAT, "Dove sono?", now)
        // Structural guarantee: LocationContext has currentPlaceLabel/currentPlaceName/movementState only.
        assertTrue(result.location == null || result.location!!.currentPlaceLabel == PlaceLabel.HOME)
    }

    @Test
    fun `stale location is excluded rather than treated as current`() {
        val stale = fullSnapshot().copy(location = LocationContext(PlaceLabel.HOME, "Casa", null, MovementState.STATIONARY, now.minusSeconds(20 * 60)))
        val result = RelevantContextSelector.select(stale, AiRequestType.CHAT, "Sto guidando verso casa", now)
        assertNull(result.location)
    }

    @Test
    fun `missing sections never crash selection, they are simply absent`() {
        val sparse = PersonalIntelligenceSnapshot(snapshotId = "s2", createdAt = now, temporal = TemporalContext(LocalDate.of(2026, 1, 1), LocalTime.of(10, 0), DayOfWeek.THURSDAY, null, null, now))
        val result = RelevantContextSelector.select(sparse, AiRequestType.CHAT, "Che ore sono?", now)
        assertTrue(SelectionCategory.TEMPORAL in result.selected)
        assertNull(result.agenda)
        assertNull(result.driving)
    }
}
