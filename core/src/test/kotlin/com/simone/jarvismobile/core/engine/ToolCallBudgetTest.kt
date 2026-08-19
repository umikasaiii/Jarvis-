package com.simone.jarvismobile.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolCallBudgetTest {

    @Test
    fun `allows exactly cap calls then refuses`() {
        val budget = ToolCallBudget(3)
        assertTrue(budget.tryConsume())
        assertTrue(budget.tryConsume())
        assertTrue(budget.tryConsume())
        assertFalse(budget.tryConsume())
        assertEquals(3, budget.usedCount)
        assertTrue(budget.exhausted)
    }

    @Test
    fun `a cap of one allows a single call`() {
        val budget = ToolCallBudget(1)
        assertTrue(budget.tryConsume())
        assertFalse(budget.tryConsume())
    }

    @Test
    fun `rejects a non-positive cap rather than looping forever`() {
        assertFailsWithIllegalArgument { ToolCallBudget(0) }
    }

    private fun assertFailsWithIllegalArgument(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
