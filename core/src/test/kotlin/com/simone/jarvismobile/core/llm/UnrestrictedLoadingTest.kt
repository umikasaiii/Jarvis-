package com.simone.jarvismobile.core.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class MemStore : LoadJournalStore {
    var data: Map<String, String> = emptyMap(); var writes = 0
    override fun read() = data
    override fun write(values: Map<String, String>) { data = values.toMap(); writes++ }
}

class UnrestrictedLoadingTest {
    private val gib = 1024L * 1024 * 1024
    private val mib = 1024L * 1024

    // The reported case: gemma-4-E4B-it.litertlm 3,490 MB with ~1,410 MiB available.
    private val reported = ModelLoadAdvisor.assess(3490 * mib, 1410 * mib, false)

    @Test fun `the reported case is an insufficient ESTIMATE, a warning and never a refusal`() {
        assertEquals(LoadAdviceLevel.INSUFFICIENT, reported.level)
        assertTrue("non un limite" in reported.message!!)
        // default: asks (the cautious default), but always offers the attempt
        assertTrue(ModelLoadPolicy.decide(reported, experimentalHeavy = false, userConfirmed = false) is LoadDecision.NeedsConfirmation)
        // "Carica comunque" for this attempt
        assertTrue(ModelLoadPolicy.decide(reported, experimentalHeavy = false, userConfirmed = true) is LoadDecision.Proceed)
        // the persistent preference: no repeated question
        val d = ModelLoadPolicy.decide(reported, experimentalHeavy = true, userConfirmed = false)
        assertTrue(d is LoadDecision.Proceed && d.warning != null)
    }

    @Test fun `there is no model size or memory level for which the policy refuses`() {
        for (file in listOf(1L, 300 * mib, 1 * gib, 3490 * mib, 8 * gib, 40 * gib, 400 * gib)) for (avail in listOf(1L, 100 * mib, 1410 * mib, 2 * gib, 12 * gib)) for (low in listOf(false, true)) {
            val adv = ModelLoadAdvisor.assess(file, avail, low)
            for (exp in listOf(false, true)) for (confirmed in listOf(false, true)) {
                val d = ModelLoadPolicy.decide(adv, exp, confirmed)
                assertTrue(d is LoadDecision.Proceed || d is LoadDecision.NeedsConfirmation)
                if (exp || confirmed || adv.level != LoadAdviceLevel.INSUFFICIENT) assertTrue(d is LoadDecision.Proceed, "file=$file avail=$avail exp=$exp confirmed=$confirmed")
            }
        }
    }

    @Test fun `the default stays prudent for plenty-of-memory and tight cases`() {
        assertTrue(ModelLoadPolicy.decide(ModelLoadAdvisor.assess(3 * gib, 6 * gib, false), false, false) is LoadDecision.Proceed)
        val tight = ModelLoadPolicy.decide(ModelLoadAdvisor.assess(3 * gib, 3 * gib, false), false, false)
        assertTrue(tight is LoadDecision.Proceed && tight.warning != null)
    }

    @Test fun `automatic loading at start is stricter than a manual attempt`() {
        assertFalse(ModelLoadPolicy.autoLoadAllowed(reported, experimentalHeavy = false, interruptedBefore = false))
        assertTrue(ModelLoadPolicy.autoLoadAllowed(reported, experimentalHeavy = true, interruptedBefore = false))
        assertFalse(ModelLoadPolicy.autoLoadAllowed(reported, experimentalHeavy = true, interruptedBefore = true), "never reload what killed the process")
        assertTrue(ModelLoadPolicy.autoLoadAllowed(ModelLoadAdvisor.assess(1 * gib, 6 * gib, false), false, false))
    }

    // ---- crash-loop guard -------------------------------------------------------------------------------------

    private fun attempt(path: String = "/m/gemma-4-E4B-it.litertlm") = LoadGuard.Attempt("fast", path, "Gemma 4 E4B", 3490 * mib, 1000, "INSUFFICIENT")

    @Test fun `a load that finishes leaves no trace`() {
        val s = MemStore(); val g = LoadGuard(s)
        g.begin(attempt()); assertTrue(s.data.containsKey("pending"))
        g.finish("/m/gemma-4-E4B-it.litertlm", success = true)
        assertNull(g.recoverOnStart()); assertTrue(g.autoLoadAllowed("/m/gemma-4-E4B-it.litertlm")); assertTrue(g.interrupted().isEmpty())
    }

    @Test fun `a process killed during the load is reported at the next start and the model is not reloaded by itself`() {
        val s = MemStore()
        LoadGuard(s).begin(attempt()) // ...and the process dies here: no finish()
        val next = LoadGuard(s) // new process, same durable store
        val rec = next.recoverOnStart()
        assertNotNull(rec); assertEquals("/m/gemma-4-E4B-it.litertlm", rec.path); assertEquals(1, rec.count)
        assertFalse(next.autoLoadAllowed("/m/gemma-4-E4B-it.litertlm"))
        assertTrue(next.autoLoadAllowed("/m/gemma-3-1b.litertlm"), "other models are unaffected")
        assertNull(next.recoverOnStart(), "reported once")
        assertEquals(1, next.interrupted().size)
    }

    @Test fun `no infinite loop - the block survives restarts until a success or the user dismisses it`() {
        val s = MemStore()
        LoadGuard(s).begin(attempt())
        var g = LoadGuard(s); g.recoverOnStart()
        // a manual retry that kills the process again: count grows, auto-load stays blocked
        g.begin(attempt()); g = LoadGuard(s); assertEquals(2, g.recoverOnStart()!!.count); assertFalse(g.autoLoadAllowed("/m/gemma-4-E4B-it.litertlm"))
        // a caught failure does not clear the block...
        g.begin(attempt()); g.finish("/m/gemma-4-E4B-it.litertlm", success = false); assertFalse(g.autoLoadAllowed("/m/gemma-4-E4B-it.litertlm"))
        // ...a success does
        g.begin(attempt()); g.finish("/m/gemma-4-E4B-it.litertlm", success = true); assertTrue(g.autoLoadAllowed("/m/gemma-4-E4B-it.litertlm"))
        // or the user's explicit dismissal
        g.begin(attempt()); val g2 = LoadGuard(s); g2.recoverOnStart(); g2.dismiss("/m/gemma-4-E4B-it.litertlm"); assertTrue(g2.autoLoadAllowed("/m/gemma-4-E4B-it.litertlm"))
    }

    @Test fun `the journal holds nothing but a few lines of text - nothing user-owned can be touched`() {
        val s = MemStore(); val g = LoadGuard(s)
        g.begin(attempt()); g.recoverOnStart()
        assertTrue(s.data.keys.all { it == "pending" || it == "interrupted" })
        assertTrue(s.data.values.sumOf { it.length } < 1000)
    }

    @Test fun `corrupt journal text never throws`() {
        val s = MemStore(); s.data = mapOf("pending" to "garbage", "interrupted" to "x\u001Ey")
        val g = LoadGuard(s)
        assertNull(g.recoverOnStart()); assertTrue(g.interrupted().isEmpty())
    }

    // ---- residency ----------------------------------------------------------------------------------------------

    @Test fun `heavy load parks the classifier first, then the advanced model, never the fast one`() {
        val others = listOf(ResidentModel(SlotId.FAST, 1 * gib), ResidentModel(SlotId.ADVANCED, 3 * gib), ResidentModel(SlotId.CLASSIFIER, 800 * mib))
        assertEquals(SlotId.CLASSIFIER, ModelResidencyPolicy.nextToPark(SlotId.FAST, LoadAdviceLevel.INSUFFICIENT, others))
        assertEquals(SlotId.ADVANCED, ModelResidencyPolicy.nextToPark(SlotId.FAST, LoadAdviceLevel.INSUFFICIENT, others.filter { it.slot != SlotId.CLASSIFIER }))
        assertNull(ModelResidencyPolicy.nextToPark(SlotId.FAST, LoadAdviceLevel.INSUFFICIENT, others.filter { it.slot == SlotId.FAST }))
        assertEquals(SlotId.CLASSIFIER, ModelResidencyPolicy.nextToPark(SlotId.ADVANCED, LoadAdviceLevel.INSUFFICIENT, others))
        assertNull(ModelResidencyPolicy.nextToPark(SlotId.ADVANCED, LoadAdviceLevel.INSUFFICIENT, others.filter { it.slot != SlotId.CLASSIFIER }), "never park fast to make room for advanced")
    }

    @Test fun `nothing is parked when the estimate is not insufficient`() {
        val others = listOf(ResidentModel(SlotId.ADVANCED, 3 * gib))
        assertNull(ModelResidencyPolicy.nextToPark(SlotId.FAST, LoadAdviceLevel.OK, others))
        assertNull(ModelResidencyPolicy.nextToPark(SlotId.FAST, LoadAdviceLevel.TIGHT, others))
        assertNull(ModelResidencyPolicy.nextToPark(SlotId.FAST, LoadAdviceLevel.INSUFFICIENT, listOf(ResidentModel(SlotId.CLASSIFIER, 10 * mib))), "a tiny resident is not worth a reload")
    }

    // ---- diagnostics --------------------------------------------------------------------------------------------

    @Test fun `the report states what each figure is and never presents the estimate as a hardware limit`() {
        val r = LoadReport("fast", "gemma-4-E4B-it", "CPU", 3490 * mib, 4013 * mib, 8000 * mib, 1410 * mib, true, 900 * mib, 150 * mib, 3300 * mib, "INSUFFICIENT", 42_000, LoadReport.Outcome.FAILED, "OutOfMemoryError: ...", true)
        val text = r.describe().joinToString("\n")
        assertTrue("1410 MiB" in text && "4013 MiB" in text && "8000 MiB" in text && "memoria bassa" in text)
        assertTrue("differenza dalla stima: -" in text, "measured growth 3150 MiB vs estimate 4013 MiB")
        assertTrue("Errore del runtime: OutOfMemoryError" in text && "NON caricato" in text && "non la quantità allocabile dal processo" in text)
        assertTrue(r.describe().none { "limite hardware" in it && "non" !in it })
        val none = LoadReport("fast", "m", "CPU", -1, -1, -1, -1, false, -1, -1, -1, "OK", 10, LoadReport.Outcome.LOADED, null, false).describe().joinToString("\n")
        assertTrue("n/d" in none && "caricato" in none)
    }
}
