package com.simone.jarvismobile.core.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Hotfix post-M4 / H4: a process that died WHILE GENERATING is recognised on the next start, distinguished from a load interruption, and the model is not reloaded by itself. */
class HotfixGenerationGuardTest {
    private class Mem : LoadJournalStore {
        var data: Map<String, String> = emptyMap()
        override fun read() = data
        override fun write(values: Map<String, String>) { data = values.toMap() }
    }
    private fun attempt(path: String = "/m/e4b.litertlm") = LoadGuard.Attempt("FAST", path, "E4B", 3_000_000_000, 10L, "gen")

    @Test fun aGenerationThatFinishedLeavesNothingBehind() {
        val mem = Mem(); val g = LoadGuard(mem)
        g.beginGeneration(attempt()); g.finishGeneration()
        assertNull(LoadGuard(mem).recoverOnStart()); assertTrue(g.autoLoadAllowed("/m/e4b.litertlm"))
    }

    @Test fun aProcessKilledDuringGenerationIsReportedAsAGenerationInterruptionAndTheModelIsNotAutoReloaded() {
        val mem = Mem()
        LoadGuard(mem).beginGeneration(attempt())              // ... the process dies here: finishGeneration() never runs
        val next = LoadGuard(mem)                               // next process
        val rec = next.recoverOnStart()!!
        assertEquals("generation", rec.phase); assertEquals(1, rec.count); assertEquals("/m/e4b.litertlm", rec.path)
        assertFalse(next.autoLoadAllowed("/m/e4b.litertlm"), "no automatic retry of a model whose generation was interrupted")
        assertTrue(next.autoLoadAllowed("/m/gemma1b.litertlm"), "a smaller model is still allowed")
        assertEquals(listOf("generation"), next.interrupted().map { it.phase })
        // the user can still dismiss it; nothing else is touched
        next.dismiss("/m/e4b.litertlm"); assertTrue(next.autoLoadAllowed("/m/e4b.litertlm"))
    }

    @Test fun loadAndGenerationInterruptionsAreDistinguished() {
        val mem = Mem(); val a = LoadGuard(mem)
        a.begin(attempt("/m/a.litertlm")); a.beginGeneration(attempt("/m/b.litertlm"))
        val b = LoadGuard(mem); b.recoverOnStart()
        assertEquals(setOf("load" to "/m/a.litertlm", "generation" to "/m/b.litertlm"), b.interrupted().map { it.phase to it.path }.toSet())
    }

    @Test fun anOldJournalWithoutAPhaseStillReadsAsALoadInterruption() {
        val mem = Mem()
        mem.data = mapOf("interrupted" to listOf("FAST", "/m/x", "X", "5", "1", "2").joinToString("\u001F"))
        assertEquals("load", LoadGuard(mem).interrupted().single().phase)
    }
}
