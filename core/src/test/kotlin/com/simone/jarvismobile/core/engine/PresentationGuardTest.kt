package com.simone.jarvismobile.core.engine

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 2, §3 "internal vs
 * user-facing channel". [PresentationGuard.isInternalSchemaLeak] is a
 * STRUCTURAL parse-based check (never a word/character blacklist) — these
 * tests pin both that it catches the real leak shape and that it never
 * false-positives on ordinary prose that merely contains a brace or the
 * word "JSON".
 */
class PresentationGuardTest {

    @Test
    fun `ordinary Italian prose is never flagged`() {
        assertFalse(PresentationGuard.isInternalSchemaLeak("Domani sarà sereno, circa 20 gradi."))
    }

    @Test
    fun `a nested tool_calls object inside assistant_text is a structural leak`() {
        val leaked = """{"tool_calls": [{"id": "1", "name": "get_weather", "arguments": {}}]}"""
        assertTrue(PresentationGuard.isInternalSchemaLeak(leaked))
    }

    @Test
    fun `a nested assistant_text-only object is also a structural leak`() {
        val leaked = """{"assistant_text": "ciao"}"""
        assertTrue(PresentationGuard.isInternalSchemaLeak(leaked))
    }

    @Test
    fun `prose that merely mentions the word JSON or contains a brace is never flagged - no blacklist`() {
        assertFalse(PresentationGuard.isInternalSchemaLeak("Il file è in formato JSON, {a proposito} te lo mando."))
        assertFalse(PresentationGuard.isInternalSchemaLeak("{non è nemmeno JSON valido"))
    }

    @Test
    fun `a JSON object with unrelated keys is never flagged - only OUR protocol keys count`() {
        assertFalse(PresentationGuard.isInternalSchemaLeak("""{"temperature": 20, "condition": "sereno"}"""))
    }

    @Test
    fun `blank and empty text are never flagged`() {
        assertFalse(PresentationGuard.isInternalSchemaLeak(""))
        assertFalse(PresentationGuard.isInternalSchemaLeak("   "))
    }

    // § test 11 — deterministic failure/empty presentation contains no internal schema.
    @Test
    fun `deterministic controlled messages never trip the leak guard`() {
        val deterministicMessages = listOf(
            "Non ho ancora i dati di salute: non posso rispondere con certezza.",
            "Non ho registrato dati di sonno per nessun giorno di questa settimana.",
            "Non riesco ad accedere ai dati meteo in questo momento.",
        )
        deterministicMessages.forEach { assertFalse(PresentationGuard.isInternalSchemaLeak(it)) }
    }
}
