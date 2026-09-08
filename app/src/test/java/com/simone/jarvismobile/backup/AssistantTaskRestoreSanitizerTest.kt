package com.simone.jarvismobile.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * § JARVIS Implementation Master Plan PASSAGGIO 10 §J — a restored
 * `assistant_tasks` row must never resurrect a pending/in-flight side effect.
 * These are the string constants [AssistantTaskRestoreSanitizer] hands to
 * Room's `onOpen()`; no Android/SQLite runtime is exercised here (that part
 * is Robolectric-shaped work this project deliberately does not add — see
 * CLAUDE.md), only that the logic is internally consistent.
 */
class AssistantTaskRestoreSanitizerTest {

    @Test fun onlyTheThreeRealTerminalStatusesAreExempt() {
        assertEquals(setOf("COMPLETED", "FAILED", "CANCELLED"), AssistantTaskRestoreSanitizer.TERMINAL_STATUSES.toSet())
    }

    @Test fun everyNonTerminalStatusIsExcludedFromTheExemptSet() {
        val nonTerminal = listOf("QUEUED", "LOADING_MODEL", "UNDERSTANDING", "RETRIEVING_MEMORY", "GENERATING")
        nonTerminal.forEach { status ->
            assertTrue("$status must not be terminal", status !in AssistantTaskRestoreSanitizer.TERMINAL_STATUSES)
        }
    }

    @Test fun sanitizeSqlTargetsTheRightTableAndNeverDeletesHistory() {
        val sql = AssistantTaskRestoreSanitizer.SANITIZE_SQL
        assertTrue(sql, sql.startsWith("UPDATE assistant_tasks SET status = 'CANCELLED'"))
        assertTrue(sql, sql.contains("progress = 0"))
        assertTrue(sql, "DELETE" !in sql.uppercase())
    }

    @Test fun sanitizeSqlExcludesExactlyTheTerminalStatuses() {
        val sql = AssistantTaskRestoreSanitizer.SANITIZE_SQL
        AssistantTaskRestoreSanitizer.TERMINAL_STATUSES.forEach { status ->
            assertTrue("SQL must mention '$status': $sql", sql.contains("'$status'"))
        }
        assertTrue(sql, sql.contains("WHERE status NOT IN ("))
    }
}
