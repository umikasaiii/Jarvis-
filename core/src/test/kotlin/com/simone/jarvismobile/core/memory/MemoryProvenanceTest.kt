package com.simone.jarvismobile.core.memory

import com.simone.jarvismobile.core.quality.ContextFit
import com.simone.jarvismobile.core.reliability.EvidenceKind
import com.simone.jarvismobile.core.reliability.EvidencePackage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MemoryProvenanceTest {
    private fun rec(id: String, text: String, source: MemorySource = MemorySource.USER_STATEMENT, at: Long = 1L) =
        MemoryRecord(id = id, text = text, createdAt = at, source = source, verification = MemoryAdmission.verificationFor(source))

    @Test fun userStatementIsStoredWithProvenance() {
        val r = MemoryLedger.apply(emptyList(), rec("a", "Il mio peso è 78 kg"))
        assertEquals(MemoryLedger.Outcome.ADDED, r.outcome)
        val s = r.stored!!
        assertEquals(MemorySource.USER_STATEMENT, s.source); assertEquals(Verification.USER_ASSERTED, s.verification)
        assertEquals("peso", s.subjectKey); assertEquals(RecordStatus.ACTIVE, s.status)
    }

    @Test fun aCorrectionSupersedesButKeepsTheHistory() {
        val first = MemoryLedger.apply(emptyList(), rec("a", "Il mio peso è 78 kg")).records
        val r = MemoryLedger.apply(first, rec("b", "Correggo: ora peso 80 kg", at = 2L))
        assertEquals(MemoryLedger.Outcome.SUPERSEDED_OLD, r.outcome)
        val old = r.records.first { it.id == "a" }; val now = r.records.first { it.id == "b" }
        assertEquals(RecordStatus.SUPERSEDED, old.status); assertEquals("b", old.supersededBy)
        assertEquals(RecordStatus.ACTIVE, now.status); assertEquals("a", now.supersedes)
        assertEquals(2, r.records.size, "history is kept for audit")
        assertEquals(listOf("b"), MemoryLedger.retrievable(r.records).map { it.id }, "only the current value is offered")
    }

    @Test fun twoIncompatibleValuesWithoutACorrectionStayMarkedConflict() {
        val first = MemoryLedger.apply(emptyList(), rec("a", "Il mio peso è 78 kg")).records
        val r = MemoryLedger.apply(first, rec("b", "Il mio peso è 85 kg", at = 2L))
        assertEquals(MemoryLedger.Outcome.CONFLICT, r.outcome)
        assertTrue(r.records.all { it.status == RecordStatus.CONFLICT })
        assertTrue(MemoryLedger.retrievable(r.records).all { "conflitto" in MemoryLedger.promptText(it) })
        val resolved = MemoryLedger.resolve(r.records, "b")
        assertEquals(listOf("b"), MemoryLedger.retrievable(resolved).map { it.id })
        assertEquals(RecordStatus.SUPERSEDED, resolved.first { it.id == "a" }.status)
    }

    @Test fun theSameFactTwiceIsNotStoredTwice() {
        val first = MemoryLedger.apply(emptyList(), rec("a", "Il mio peso è 78 kg")).records
        val r = MemoryLedger.apply(first, rec("b", "Il mio peso è 78 kg", at = 2L))
        assertEquals(MemoryLedger.Outcome.DUPLICATE, r.outcome); assertEquals(1, r.records.size)
    }

    @Test fun textualSubjectsAreLinkedToo() {
        val first = MemoryLedger.apply(emptyList(), rec("a", "Abito a Torino")).records
        val r = MemoryLedger.apply(first, rec("b", "Anzi, ora abito a Milano", at = 2L))
        assertEquals(MemoryLedger.Outcome.SUPERSEDED_OLD, r.outcome)
    }

    @Test fun modelOutputHypothesesAndRejectedDraftsNeverBecomeMemory() {
        for (src in listOf(MemorySource.MODEL_INFERENCE, MemorySource.HYPOTHESIS, MemorySource.UNVERIFIED, MemorySource.REJECTED_DRAFT)) {
            val r = MemoryLedger.apply(emptyList(), rec("x", "Il tuo peso è 90 kg", src))
            assertEquals(MemoryLedger.Outcome.REJECTED, r.outcome, src.name); assertTrue(r.records.isEmpty())
        }
        assertEquals(MemorySource.HYPOTHESIS, MemoryAdmission.sourceForUserText("Forse peso 70 kg"))
        assertEquals(MemorySource.HYPOTHESIS, MemoryAdmission.sourceForUserText("Se pesassi 70 kg andrei meglio"))
    }

    @Test fun aDocumentOrToolItemIsNotAPersonalFact() {
        assertFalse(MemoryAdmission.mayBePersonalFact(MemorySource.DOCUMENT)); assertFalse(MemoryAdmission.mayBePersonalFact(MemorySource.ZIM))
        // referenced, never merged into the user's own facts: a document line cannot supersede what the user said
        val first = MemoryLedger.apply(emptyList(), rec("a", "Il mio peso è 78 kg")).records
        val r = MemoryLedger.apply(first, rec("d", "Il mio peso è 60 kg", MemorySource.DOCUMENT))
        assertEquals(RecordStatus.ACTIVE, r.records.first { it.id == "a" }.status)
    }

    @Test fun recordsSavedBeforeM1StillLoadAndNewFieldsRoundTrip() {
        val old = """<!-- jarvis-memory-v2:{"id":"x","text":"Ho una moto","kind":"PERMANENT","createdAt":5} -->"""
        val loaded = MemoryRecordCodec.parse(old).single()
        assertEquals(MemorySource.USER_STATEMENT, loaded.source); assertEquals(RecordStatus.ACTIVE, loaded.status)
        val rt = MemoryRecordCodec.parse(MemoryRecordCodec.render(listOf(loaded.copy(status = RecordStatus.SUPERSEDED, supersededBy = "y", sourceRef = "doc1")))).single()
        assertEquals(RecordStatus.SUPERSEDED, rt.status); assertEquals("y", rt.supersededBy); assertEquals("doc1", rt.sourceRef)
        // a value written by a FUTURE version is coerced to the default instead of dropping the record
        val future = """<!-- jarvis-memory-v2:{"id":"z","text":"Ho un gatto","kind":"PERMANENT","createdAt":6,"source":"SOMETHING_NEW"} -->"""
        assertNotNull(MemoryRecordCodec.parse(future).singleOrNull())
    }

    // ---- recap (short-term) ----
    private fun turns(vararg t: Pair<Boolean, String>) = t.map { MemoryTurn(it.first, it.second) }
    private val filler = List(12) { MemoryTurn(true, "messaggio recente numero $it di riempimento") }

    @Test fun recapKeepsStatementsButNotQuestionsSuppositionsOrCommands() {
        val older = turns(
            true to "Il mio peso è 78 kg", true to "Quanto pesa una Panda", true to "Se pesassi 70 kg andrei più veloce",
            true to "Forse domani vado a Roma", true to "Traduci questa frase in inglese", true to "Mi chiamo Simone",
        )
        val snap = ShortTermMemorySummarizer.summarize(older + filler)
        assertTrue("Il mio peso è 78 kg" in snap.facts && "Mi chiamo Simone" in snap.facts)
        assertEquals(2, snap.facts.size, snap.facts.toString())
        assertTrue(snap.forPrompt().startsWith("Dichiarazioni dell'utente"))
        assertFalse("Fatti" in snap.forPrompt())
    }

    @Test fun recapReplacesACorrectedValue() {
        val snap = ShortTermMemorySummarizer.summarize(turns(true to "Il mio peso è 78 kg", true to "Correggo: ora peso 80 kg") + filler)
        assertEquals(listOf("Correggo: ora peso 80 kg"), snap.facts)
    }

    // ---- context fit ----
    private fun pkg(vararg r: EvidencePackage.Companion.Raw) = EvidencePackage.of(r.toList(), 5000)
    private fun raw(kind: EvidenceKind, n: Int, text: String) = EvidencePackage.Companion.Raw("T$n", "", text, "k$n", kind)

    @Test fun fitDropsMemoryFirstThenLaterPassagesAndNeverReceipts() {
        val big = "Frase di prova abbastanza lunga da occupare spazio nel contesto. ".repeat(10)
        val p = pkg(raw(EvidenceKind.KNOWLEDGE, 1, big), raw(EvidenceKind.KNOWLEDGE, 2, big), raw(EvidenceKind.MEMORY, 3, big), raw(EvidenceKind.TOOL, 4, "Batteria: 41%."))
        val one = ContextFit.itemTokens(p.items[0])
        val fit = ContextFit.fit(p, fixedTokens = 100, ceiling = 100 + one * 2 + 40)
        assertTrue(fit.fits)
        assertEquals("E3", fit.droppedIds.first(), "memory goes first")
        assertTrue(fit.pkg.items.any { it.kind == EvidenceKind.TOOL })
        assertTrue(fit.pkg.omitted.any { it.reason == "budget del contesto" })
        assertTrue("non inclusi" in fit.pkg.render())
    }

    @Test fun fitKeepsEverythingWhenItFits() {
        val p = pkg(raw(EvidenceKind.KNOWLEDGE, 1, "Una frase."), raw(EvidenceKind.MEMORY, 2, "Un ricordo."))
        val fit = ContextFit.fit(p, 100, 4000)
        assertTrue(fit.fits && fit.droppedIds.isEmpty() && fit.pkg.items.size == 2)
    }

    @Test fun fitReportsWhenNothingCanMakeItFit() {
        val p = pkg(raw(EvidenceKind.TOOL, 1, "Batteria: 41%."))
        assertFalse(ContextFit.fit(p, fixedTokens = 5000, ceiling = 3000).fits)
    }
}
