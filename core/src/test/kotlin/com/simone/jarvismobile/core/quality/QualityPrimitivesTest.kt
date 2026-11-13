package com.simone.jarvismobile.core.quality

import com.simone.jarvismobile.core.llm.ChatSeed
import com.simone.jarvismobile.core.llm.SeedTurn
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QualityPrimitivesTest {
    // ---- seed normalisation ---------------------------------------------------------------------------
    @Test fun `seed turns become a valid alternating sequence that ends with a model turn`() {
        val seed = ChatSeed(
            listOf(
                SeedTurn(false, "assistente iniziale"), SeedTurn(true, "uno"), SeedTurn(true, "due"), SeedTurn(false, "risposta"),
                SeedTurn(false, "  "), SeedTurn(true, "senza risposta"),
            ),
        )
        assertEquals(listOf(SeedTurn(true, "uno\ndue"), SeedTurn(false, "risposta")), seed.normalized())
        assertEquals(emptyList(), ChatSeed.NONE.normalized())
    }

    // ---- budget ---------------------------------------------------------------------------------------
    @Test fun `the user message is never truncated and history yields first`() {
        val big = "x".repeat(12_000)
        val plan = PromptBudgeter.plan("sistema", emptyList(), listOf(SeedTurn(true, "a"), SeedTurn(false, "b")), big)
        assertTrue(plan.userExceedsBudget)
        assertEquals(emptyList(), plan.history)
        assertEquals(TokenEstimator.message(big), plan.estUser)
    }

    @Test fun `newest history is kept whole and old turns are dropped`() {
        val h = (1..40).flatMap { listOf(SeedTurn(true, "domanda $it"), SeedTurn(false, "risposta numero $it. " + "parole ".repeat(60))) }
        val plan = PromptBudgeter.plan("sistema breve", emptyList(), h, "ultima domanda")
        assertTrue(plan.history.isNotEmpty() && plan.droppedHistoryTurns > 0)
        assertTrue(plan.history.last().text.startsWith("risposta numero 40"))
        assertTrue(plan.estHistory <= ContextBudget().historyTokens)
    }

    @Test fun `a long assistant turn is cut at a sentence boundary never mid word`() {
        val t = "Prima frase completa. Seconda frase completa. " + "Terza frase molto lunga ".repeat(40)
        val s = PromptBudgeter.shorten(t, 60)
        assertTrue(s.endsWith("."), s)
        assertEquals("Prima frase completa. Seconda frase completa.", s)
    }

    @Test fun `ledger asks for rotation before the reply reserve is eaten`() {
        val l = ConversationTokenLedger(ContextBudget(windowTokens = 1000, replyReserveTokens = 300))
        l.start(200)
        assertFalse(l.needsRotation(100))
        l.record(100, 300)
        assertTrue(l.needsRotation(200))
        l.reset(); assertFalse(l.needsRotation(5000), "an empty ledger never rotates")
    }

    // ---- composer ---------------------------------------------------------------------------------------
    @Test fun `composer keeps identity persona date notes and no recap`() {
        val s = ClassicPromptComposer.compose("BASE", "stile asciutto", "JARVIS", LocalDateTime.of(2026, 11, 13, 10, 5), listOf("nota uno"), "ricordo breve")
        assertTrue(s.startsWith("BASE"))
        assertTrue("stile asciutto" in s && "Ti chiami JARVIS." in s && "nota uno" in s && "ricordo breve" in s)
        assertFalse("Simone:" in s || "Tu:" in s, "no transcript is pasted into the system block any more")
    }

    @Test fun `composer caps the notes by token budget`() {
        val notes = (1..200).map { "nota lunga numero $it " + "testo ".repeat(30) }
        val s = ClassicPromptComposer.compose("B", "", "J", LocalDateTime.of(2026, 1, 1, 0, 0), notes, "")
        assertTrue(TokenEstimator.estimate(s) < ContextBudget().notesTokens + 200)
    }

    // ---- trace & probe ------------------------------------------------------------------------------------
    @Test fun `a trace is metadata only and marks what the runtime does not expose`() {
        val t = GenerationTrace(
            1, 3, "classic", "chat_general", "fast", 2, 1200, 0, 800, 0, 40, 700, 4096, 1024, 4, 0, 0, false,
            5200, 180, 175, "-", false, Termination.COMPLETED, "MODEL_GENERAL", false,
        )
        val r = t.render()
        assertTrue("outputTokens=UNAVAILABLE" in r && "finishReason=UNAVAILABLE" in r && "(estimate)" in r)
        assertFalse(GenerationTrace::class.java.declaredFields.any { it.type == String::class.java && it.name in setOf("prompt", "question", "answer", "text", "reply") })
    }

    @Test fun `the debug text probe is off by default expires and never outlives disable`() {
        DebugTextProbe.disable()
        val c = DebugTextProbe.Capture(1, "sys", "user", "raw", "clean")
        DebugTextProbe.capture(0, c)
        assertEquals(emptyList(), DebugTextProbe.snapshot(0), "off by default: nothing is held")
        DebugTextProbe.enable(1_000, windowMs = 500)
        DebugTextProbe.capture(1_100, c)
        assertEquals(1, DebugTextProbe.snapshot(1_200).size)
        assertEquals(emptyList(), DebugTextProbe.snapshot(1_600), "expired")
        DebugTextProbe.enable(2_000, 1_000); DebugTextProbe.capture(2_100, c); DebugTextProbe.disable()
        assertEquals(emptyList(), DebugTextProbe.snapshot(2_200))
    }

    @Test fun `trace log keeps a bounded window and feeds the persistence sink with metadata lines`() {
        val lines = ArrayList<String>()
        val log = GenerationTraceLog(capacity = 3) { lines += it }
        repeat(5) { i ->
            log.record(GenerationTrace(i.toLong(), i.toLong(), "classic", "chat_general", "fast", 0, 1, 0, 0, 0, 1, 1, 4096, 1024, 0, 0, 0, false, 1, 1, 1, "-", false, Termination.COMPLETED, "MODEL_GENERAL", false))
        }
        assertEquals(3, log.snapshot().size)
        assertEquals(5, lines.size)
        assertTrue(lines.all { it.startsWith("GEN ") })
    }
}
