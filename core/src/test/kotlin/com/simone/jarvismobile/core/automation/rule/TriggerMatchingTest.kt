package com.simone.jarvismobile.core.automation.rule

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TriggerMatchingTest {

    private val now = LocalDateTime.of(2026, 8, 15, 10, 0)

    private fun placeRule(placeId: String?) = AutomationRule(
        id = "r1",
        name = "casa",
        triggers = listOf(
            TriggerSpec(TriggerRegistry.PLACE_ENTER, if (placeId == null) emptyMap() else mapOf("placeId" to placeId)),
        ),
        actions = listOf(ActionSpec(ActionRegistry.SPEAK, mapOf("message" to "ciao"))),
    )

    @Test
    fun `a place rule fires only for its own place`() {
        val rule = placeRule("casa")
        assertTrue(TriggerMatching.ruleListens(rule, TriggerEvent(TriggerRegistry.PLACE_ENTER, now, dedupKey = "casa")))
        assertFalse(TriggerMatching.ruleListens(rule, TriggerEvent(TriggerRegistry.PLACE_ENTER, now, dedupKey = "ufficio")))
    }

    @Test
    fun `entering is not the same event as exiting`() {
        val rule = placeRule("casa")
        assertFalse(TriggerMatching.ruleListens(rule, TriggerEvent(TriggerRegistry.PLACE_EXIT, now, dedupKey = "casa")))
    }

    @Test
    fun `a place trigger with no place matches any place`() {
        val rule = placeRule(null)
        assertTrue(TriggerMatching.ruleListens(rule, TriggerEvent(TriggerRegistry.PLACE_ENTER, now, dedupKey = "ovunque")))
    }

    @Test
    fun `a mode rule fires only for its own mode, case-insensitively`() {
        val rule = AutomationRule(
            id = "m1",
            name = "notte",
            triggers = listOf(TriggerSpec(TriggerRegistry.JARVIS_MODE_ENTER, mapOf("mode" to "SLEEP"))),
            actions = listOf(ActionSpec(ActionRegistry.SPEAK, mapOf("message" to "buonanotte"))),
        )
        assertTrue(TriggerMatching.ruleListens(rule, TriggerEvent(TriggerRegistry.JARVIS_MODE_ENTER, now, dedupKey = "sleep")))
        assertFalse(TriggerMatching.ruleListens(rule, TriggerEvent(TriggerRegistry.JARVIS_MODE_ENTER, now, dedupKey = "WORK")))
    }

    @Test
    fun `a non-place trigger matches on type alone`() {
        val rule = AutomationRule(
            id = "r2",
            name = "carica",
            triggers = listOf(TriggerSpec(TriggerRegistry.DEVICE_CHARGING)),
            actions = listOf(ActionSpec(ActionRegistry.SPEAK, mapOf("message" to "ciao"))),
        )
        assertTrue(TriggerMatching.ruleListens(rule, TriggerEvent(TriggerRegistry.DEVICE_CHARGING, now)))
        assertFalse(TriggerMatching.ruleListens(rule, TriggerEvent(TriggerRegistry.DEVICE_UNPLUGGED, now)))
    }
}
