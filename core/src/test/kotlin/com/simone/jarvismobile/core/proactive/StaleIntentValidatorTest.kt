package com.simone.jarvismobile.core.proactive

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

class StaleIntentValidatorTest {

    @Test
    fun `matching revision and date - valid`() {
        assertTrue(StaleIntentValidator.isValid(3L, "2026-09-19", 3L, "2026-09-19"))
    }

    @Test
    fun `stale revision - invalid, a rescheduled plan must reject the old intent`() {
        assertFalse(StaleIntentValidator.isValid(2L, "2026-09-19", 3L, "2026-09-19"))
    }

    @Test
    fun `mismatched logical date - invalid even with matching revision`() {
        assertFalse(StaleIntentValidator.isValid(3L, "2026-09-18", 3L, "2026-09-19"))
    }

    @Test
    fun `no current plan known at all (never scheduled, or plan cleared) - invalid, never trust an orphan intent`() {
        assertFalse(StaleIntentValidator.isValid(1L, "2026-09-19", null, null))
    }
}
