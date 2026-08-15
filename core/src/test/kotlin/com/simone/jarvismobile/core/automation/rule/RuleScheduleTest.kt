package com.simone.jarvismobile.core.automation.rule

import java.time.DayOfWeek
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RuleScheduleTest {

    private val now = LocalDateTime.of(2026, 8, 15, 10, 0) // a Saturday

    @Test
    fun `a daily rule later today fires today`() {
        val spec = TriggerSpec(TriggerRegistry.RECURRING_TIME, mapOf("time" to "18:30"))
        assertEquals(LocalDateTime.of(2026, 8, 15, 18, 30), RuleSchedule.nextOccurrence(spec, now))
    }

    @Test
    fun `a daily rule whose time has passed fires tomorrow`() {
        val spec = TriggerSpec(TriggerRegistry.RECURRING_TIME, mapOf("time" to "08:00"))
        assertEquals(LocalDateTime.of(2026, 8, 16, 8, 0), RuleSchedule.nextOccurrence(spec, now))
    }

    @Test
    fun `a rule with selected days skips to the next selected day`() {
        // Monday(1) and Wednesday(3); "now" is Saturday, so next is Monday.
        val spec = TriggerSpec(TriggerRegistry.RECURRING_TIME, mapOf("time" to "07:15", "days" to "1,3"))
        val next = RuleSchedule.nextOccurrence(spec, now)!!
        assertEquals(DayOfWeek.MONDAY, next.dayOfWeek)
        assertEquals(LocalDateTime.of(2026, 8, 17, 7, 15), next)
    }

    @Test
    fun `a precise date-time in the future fires once`() {
        val spec = TriggerSpec(TriggerRegistry.TIME_AT, mapOf("at" to "2026-08-15T11:45"))
        assertEquals(LocalDateTime.of(2026, 8, 15, 11, 45), RuleSchedule.nextOccurrence(spec, now))
    }

    @Test
    fun `a precise date-time in the past does not fire again`() {
        val spec = TriggerSpec(TriggerRegistry.TIME_AT, mapOf("at" to "2026-08-15T09:00"))
        assertNull(RuleSchedule.nextOccurrence(spec, now))
    }

    @Test
    fun `a non-clock trigger is not scheduled here`() {
        val spec = TriggerSpec(TriggerRegistry.PLACE_ENTER, mapOf("placeId" to "casa"))
        assertNull(RuleSchedule.nextOccurrence(spec, now))
        assertEquals(false, RuleSchedule.isScheduled(TriggerRegistry.PLACE_ENTER))
    }

    @Test
    fun `a malformed time is refused rather than guessed`() {
        val spec = TriggerSpec(TriggerRegistry.RECURRING_TIME, mapOf("time" to "25:99"))
        assertNull(RuleSchedule.nextOccurrence(spec, now))
        assertNull(RuleSchedule.parseTime("nonsense"))
        assertEquals(java.time.LocalTime.of(8, 30), RuleSchedule.parseTime("8.30"))
    }

    @Test
    fun `the earliest of several triggers wins`() {
        val rule = AutomationRule(
            id = "r1",
            name = "mattina",
            triggers = listOf(
                TriggerSpec(TriggerRegistry.RECURRING_TIME, mapOf("time" to "23:00")),
                TriggerSpec(TriggerRegistry.TIME_AT, mapOf("at" to "2026-08-15T12:00")),
                TriggerSpec(TriggerRegistry.PLACE_ENTER, mapOf("placeId" to "casa")),
            ),
            actions = listOf(ActionSpec(ActionRegistry.SPEAK, mapOf("message" to "ciao"))),
        )
        val (spec, at) = RuleSchedule.nextForRule(rule, now)!!
        assertEquals(TriggerRegistry.TIME_AT, spec.type)
        assertEquals(LocalDateTime.of(2026, 8, 15, 12, 0), at)
    }

    @Test
    fun `empty days means every day`() {
        assertEquals(emptySet(), RuleSchedule.parseDays(null))
        assertEquals(emptySet(), RuleSchedule.parseDays("  "))
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY), RuleSchedule.parseDays("1,7"))
        // An out-of-range day is ignored, not fatal.
        assertEquals(setOf(DayOfWeek.TUESDAY), RuleSchedule.parseDays("2,9"))
    }
}
