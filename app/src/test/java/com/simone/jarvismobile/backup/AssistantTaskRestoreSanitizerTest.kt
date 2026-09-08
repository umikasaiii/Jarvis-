package com.simone.jarvismobile.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
            assertTrue(status !in AssistantTaskRestoreSanitizer.TERMINAL_STATUSES, "$status must not be terminal")
        }
    }

    @Test fun sanitizeSqlTargetsTheRightTableAndNeverDeletesHistory() {
        val sql = AssistantTaskRestoreSanitizer.SANITIZE_SQL
        assertTrue(sql.startsWith("UPDATE assistant_tasks SET status = 'CANCELLED'"), sql)
        assertTrue(sql.contains("progress = 0"), sql)
        assertTrue("DELETE" !in sql.uppercase(), sql)
    }

    @Test fun sanitizeSqlExcludesExactlyTheTerminalStatuses() {
        val sql = AssistantTaskRestoreSanitizer.SANITIZE_SQL
        AssistantTaskRestoreSanitizer.TERMINAL_STATUSES.forEach { status ->
            assertTrue(sql.contains("'$status'"), "SQL must mention '$status': $sql")
        }
        assertTrue(sql.contains("WHERE status NOT IN ("), sql)
    }
}
