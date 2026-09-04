package com.simone.jarvismobile.core.engine

import com.simone.jarvismobile.core.protocol.AssistantResponse
import com.simone.jarvismobile.core.tools.RelevantToolSelector
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * § FASE 2A.3 — the mandatory scenarios from the request, at the level
 * that's actually testable here: pure prompt composition. Whether the model
 * ITSELF obeys the new priority rule is not something this JVM suite can
 * verify (no device/model access) — see the phase report.
 */
class SystemPromptComposerTest {

    private val richPersona = "Sei JARVIS, l'assistente personale di Simone. Parli italiano.\n\nRegola: mai inventare."
    private val someTools = listOf(
        "add_reminder" to "Aggiunge un promemoria in agenda.",
        "list_agenda" to "Elenca gli impegni.",
    )

    // --- 1. chat semplice -> prompt compatto ------------------------------------------

    @Test
    fun `FAST no-tool prompt is compact, well under the 2000-char cap`() {
        val built = SystemPromptComposer.compose(SystemPromptComposer.Tier.FAST, richPersona, emptyList())
        assertTrue(built.length <= 2000, "expected <=2000 chars, was ${built.length}")
    }

    @Test
    fun `FAST no-tool prompt is dramatically smaller than the FASE 2A2 rich baseline`() {
        val fast = SystemPromptComposer.compose(SystemPromptComposer.Tier.FAST, richPersona, emptyList())
        val rich = SystemPromptComposer.compose(SystemPromptComposer.Tier.RICH, richPersona, emptyList())
        assertTrue(fast.length < rich.length)
    }

    // --- 2. selectedToolCount=0 -> nessun protocollo tool completo --------------------

    @Test
    fun `FAST no-tool prompt omits the full JSON tool-call contract`() {
        val built = SystemPromptComposer.compose(SystemPromptComposer.Tier.FAST, richPersona, emptyList())
        assertFalse("tool_calls" in built)
        assertFalse("memory_proposal" in built)
        assertTrue("senza JSON" in built)
    }

    @Test
    fun `RICH no-tool prompt still omits the tool catalog itself`() {
        val built = SystemPromptComposer.compose(SystemPromptComposer.Tier.RICH, richPersona, emptyList())
        assertFalse("add_reminder" in built)
        assertTrue("Nessuno strumento" in built)
    }

    // --- 3. richiesta tool -> protocollo minimo presente -------------------------------

    @Test
    fun `FAST tool-bearing prompt carries the minimal tool-call shape and the catalog`() {
        val built = SystemPromptComposer.compose(SystemPromptComposer.Tier.FAST, richPersona, someTools)
        assertTrue("tool_calls" in built)
        assertTrue("assistant_text" in built)
        assertTrue("add_reminder" in built)
        assertTrue("list_agenda" in built)
        // Minimal really means minimal: no memory_proposal/follow_up_expected
        // clutter the rich protocol carries for multi-turn clarifying
        // questions - AssistantResponse defaults both fields, so omitting
        // them from the instructions is safe (see the JSON round-trip test).
        assertFalse("memory_proposal" in built)
        assertFalse("follow_up_expected" in built)
    }

    @Test
    fun `FAST tool-bearing prompt is still far smaller than RICH with the same tools`() {
        val fast = SystemPromptComposer.compose(SystemPromptComposer.Tier.FAST, richPersona, someTools)
        val rich = SystemPromptComposer.compose(SystemPromptComposer.Tier.RICH, richPersona, someTools)
        assertTrue(fast.length < rich.length)
    }

    // --- 4. persona JARVIS preservata --------------------------------------------------

    @Test
    fun `both tiers preserve the JARVIS identity`() {
        val fast = SystemPromptComposer.compose(SystemPromptComposer.Tier.FAST, richPersona, emptyList())
        val rich = SystemPromptComposer.compose(SystemPromptComposer.Tier.RICH, richPersona, emptyList())
        assertTrue("JARVIS" in fast)
        assertTrue("JARVIS" in rich)
        assertTrue("Simone" in fast)
        assertTrue("Simone" in rich)
    }

    // --- 5. istruzione esplicita dell'utente non sostituita -----------------------------

    @Test
    fun `FAST persona explicitly prioritises the user's current literal instruction`() {
        // § root cause of "Rispondi solo: TEST CORE" -> "Ciao!": neither the
        // rich persona asset nor the old rich protocol block ever said this -
        // pinning its presence in the new compact persona, not just asserting
        // it "should" be there.
        assertTrue("istruzione più recente" in SystemPromptComposer.FAST_COMPACT_PERSONA)
        assertTrue("alla lettera" in SystemPromptComposer.FAST_COMPACT_PERSONA)
    }

    @Test
    fun `FAST persona forbids preamble-commentary when the user specifies exact output - FASE 2A4`() {
        // § root cause of the wrapper still measured after the 2A.3 fix
        // ("Nessun testo necessario. La risposta diretta è: TEST CORE"): the
        // instruction to obey literally didn't also say "and don't narrate
        // that you're doing it." General wording, no hardcoded phrase.
        assertTrue("nessuna introduzione" in SystemPromptComposer.FAST_COMPACT_PERSONA)
        assertFalse("TEST CORE" in SystemPromptComposer.FAST_COMPACT_PERSONA)
    }

    // --- 6. tool JSON/parsing invariati --------------------------------------------------

    @Test
    fun `RICH tier is byte-for-byte what FASE 2A2 already sent, no-tool case`() {
        val expected = richPersona.trim() +
            "\n\n" + SystemPromptComposer.RICH_PROTOCOL_BLOCK +
            "\n\nNessuno strumento è necessario per questa richiesta: lascia \"tool_calls\" vuoto."
        val built = SystemPromptComposer.compose(SystemPromptComposer.Tier.RICH, richPersona, emptyList())
        assertEquals(expected, built)
    }

    @Test
    fun `RICH tier is byte-for-byte what FASE 2A2 already sent, tool-bearing case`() {
        val expected = richPersona.trim() +
            "\n\n" + SystemPromptComposer.RICH_PROTOCOL_BLOCK +
            "\n\nStrumenti disponibili (usa ESATTAMENTE questi nomi, mai altri):\n" +
            "- add_reminder: Aggiunge un promemoria in agenda.\n" +
            "- list_agenda: Elenca gli impegni."
        val built = SystemPromptComposer.compose(SystemPromptComposer.Tier.RICH, richPersona, someTools)
        assertEquals(expected, built)
    }

    @Test
    fun `a JSON reply shaped exactly like the FAST minimal instructions decodes with the real parser`() {
        // Proves the minimal FAST protocol describes a shape ResponseParser/
        // AssistantResponse actually accept - not just prose that LOOKS
        // compatible. Uses the same lenient Json ResponseParser itself uses.
        val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false; coerceInputValues = true }
        val sample = """{"assistant_text":"Fatto.","tool_calls":[{"id":"1","name":"add_reminder","arguments":{"text":"comprare il latte"}}]}"""
        val decoded = json.decodeFromString<AssistantResponse>(sample)
        assertEquals("Fatto.", decoded.assistantText)
        assertEquals(1, decoded.toolCalls.size)
        assertEquals("add_reminder", decoded.toolCalls[0].name)
        // Omitted fields (memory_proposal/follow_up_expected) fall back to
        // their real defaults, exactly as the minimal protocol relies on.
        assertEquals(null, decoded.memoryProposal)
        assertEquals(false, decoded.followUpExpected)
    }

    // --- 7. budget output FAST applicato correttamente -----------------------------------

    @Test
    fun `FAST no-tool prompt carries the output brevity budget`() {
        val built = SystemPromptComposer.compose(SystemPromptComposer.Tier.FAST, richPersona, emptyList())
        assertTrue(SystemPromptComposer.FAST_BUDGET_LINE in built)
    }

    @Test
    fun `FAST tool-bearing prompt does NOT carry the strict brevity budget`() {
        // § "richieste FAST che richiedono risposta più articolata possono
        // ricevere un budget maggiore" - a tool-bearing turn skips the
        // strict word cap so tool_calls JSON is never squeezed.
        val built = SystemPromptComposer.compose(SystemPromptComposer.Tier.FAST, richPersona, someTools)
        assertFalse(SystemPromptComposer.FAST_BUDGET_LINE in built)
    }

    @Test
    fun `RICH tier never carries the FAST-only output budget, tool count either way`() {
        val richNoTool = SystemPromptComposer.compose(SystemPromptComposer.Tier.RICH, richPersona, emptyList())
        val richWithTools = SystemPromptComposer.compose(SystemPromptComposer.Tier.RICH, richPersona, someTools)
        assertFalse(SystemPromptComposer.FAST_BUDGET_LINE in richNoTool)
        assertFalse(SystemPromptComposer.FAST_BUDGET_LINE in richWithTools)
    }

    // --- 8. BRAIN/local non degradati -----------------------------------------------------

    @Test
    fun `RICH tier keeps every rule the rich protocol block had before this phase`() {
        val built = SystemPromptComposer.compose(SystemPromptComposer.Tier.RICH, richPersona, someTools)
        assertTrue("follow_up_expected" in built)
        assertTrue("memory_proposal" in built)
        assertTrue("MODALITÀ CONVERSAZIONALE" in built)
    }

    // --- 9. prompt sempre <=8000 char ------------------------------------------------------

    @Test
    fun `RICH tier with the full 53-tool catalog stays a realistic size the existing wire truncation can still cap`() {
        val fiftyThreeTools = (1..53).map { "tool_$it" to "Descrizione di esempio per lo strumento numero $it, non troppo corta." }
        val built = SystemPromptComposer.compose(SystemPromptComposer.Tier.RICH, richPersona, fiftyThreeTools)
        // Composer itself never truncates (§ "non fare semplice truncation
        // come strategia primaria") - RemoteAiEngine's existing wire-level
        // truncateSystemPromptForWire is what guarantees <=8000 on the wire,
        // unchanged by this phase. This just confirms the worst realistic
        // case is still in the same ballpark FASE 2A.1/2A.2 already measured
        // and handled, not a new, larger problem.
        assertTrue(built.length < 12000, "worst-case RICH+full-catalog grew unexpectedly: ${built.length}")
    }

    @Test
    fun `FAST tier with a large tool selection is always smaller than RICH with the same tools`() {
        val fiftyThreeTools = (1..53).map { "tool_$it" to "Descrizione di esempio per lo strumento numero $it, non troppo corta." }
        val fast = SystemPromptComposer.compose(SystemPromptComposer.Tier.FAST, richPersona, fiftyThreeTools)
        val rich = SystemPromptComposer.compose(SystemPromptComposer.Tier.RICH, richPersona, fiftyThreeTools)
        assertTrue(fast.length < rich.length)
    }

    // --- § FASE 2A.4 — sequential turns through the real selector+composer pipeline -----

    private val realisticCatalog = listOf(
        "flashlight" to "Accende o spegne la torcia.",
        "battery_status" to "Stato della batteria.",
        "list_agenda" to "Elenca gli impegni.",
        "add_reminder" to "Aggiunge un promemoria in agenda.",
        "remember" to "Salva un appunto in memoria.",
    )

    /** Mirrors exactly what `JarvisBrain.systemPromptFor()` does: select, then compose. */
    private fun composeForTurn(userText: String, tier: SystemPromptComposer.Tier) =
        SystemPromptComposer.compose(tier, richPersona, RelevantToolSelector.select(realisticCatalog, userText))

    @Test
    fun `sequential pipeline - TEST CORE then a device command - turn 2's prompt never contains turn 1's literal text`() {
        val turn1 = composeForTurn("Rispondi solo: TEST CORE", SystemPromptComposer.Tier.FAST)
        val turn2 = composeForTurn("Accendi la luce della camera", SystemPromptComposer.Tier.FAST)
        assertFalse("TEST CORE" in turn2)
        assertTrue("flashlight" in turn2)
        assertNotEquals(turn1, turn2)
    }

    @Test
    fun `sequential pipeline - an agenda question then a device command - turn 2 carries only device tools`() {
        val turn1 = composeForTurn("Che impegni ho oggi?", SystemPromptComposer.Tier.FAST)
        val turn2 = composeForTurn("Accendi la luce della camera", SystemPromptComposer.Tier.FAST)
        assertTrue("list_agenda" in turn1)
        assertFalse("list_agenda" in turn2)
        assertTrue("flashlight" in turn2)
    }

    @Test
    fun `sequential pipeline - reverse order - device command then an agenda question - is equally isolated`() {
        val turn1 = composeForTurn("Accendi la luce della camera", SystemPromptComposer.Tier.FAST)
        val turn2 = composeForTurn("Che impegni ho oggi?", SystemPromptComposer.Tier.FAST)
        assertTrue("flashlight" in turn1)
        assertFalse("flashlight" in turn2)
        assertTrue("list_agenda" in turn2)
    }
}
