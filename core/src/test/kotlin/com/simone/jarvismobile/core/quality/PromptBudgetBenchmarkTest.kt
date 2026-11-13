package com.simone.jarvismobile.core.quality

import com.simone.jarvismobile.core.llm.ChatSeed
import com.simone.jarvismobile.core.llm.SeedTurn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private data class Req(val id: String, val category: String, val prompt: String, val split: String, val group: String?, val route: String, val observed: Boolean)

private fun loadDataset(): List<Req> {
    val text = object {}.javaClass.getResourceAsStream("/quality/benchmark_v1.json")!!.bufferedReader().readText()
    return (Json.parseToJsonElement(text) as JsonArray).map {
        val o = it.jsonObject
        Req(
            o["id"]!!.jsonPrimitive.content, o["category"]!!.jsonPrimitive.content, o["prompt"]!!.jsonPrimitive.content,
            o["split"]!!.jsonPrimitive.content, o["group"]?.jsonPrimitive?.content?.takeIf { g -> g != "null" },
            o["route"]!!.jsonPrimitive.content, o["observed"] != null,
        )
    }
}

class BenchmarkDatasetTest {
    private val ds = loadDataset()

    @Test fun `the benchmark has at least 120 requests across the twelve required categories`() {
        assertTrue(ds.size >= 120, "size=${ds.size}")
        val required = setOf(
            "general_knowledge", "technical_definitions", "quantitative", "natural_conversation", "complex_requests", "ambiguous",
            "personal_memory", "documents", "calc_tools", "topic_switch", "no_source", "long_conversation_recovery",
        )
        assertEquals(required, ds.map { it.category }.toSet())
        required.forEach { c -> assertTrue(ds.count { it.category == c } >= 10, "category $c too small") }
        assertEquals(ds.size, ds.map { it.id }.toSet().size, "ids must be unique")
    }

    @Test fun `a separate holdout and paraphrases exist and the observed questions are a small minority`() {
        val holdout = ds.count { it.split == "holdout" }
        assertTrue(holdout >= ds.size * 0.3, "holdout=$holdout")
        assertTrue(ds.groupBy { it.group }.filterKeys { it != null }.values.count { it.size >= 2 } >= 5, "paraphrase groups")
        assertTrue(ds.count { it.observed } <= 6)
        assertTrue(ds.count { it.observed } * 10 < ds.size, "the user's own questions must not dominate the benchmark")
        // Observed questions are only ever in the dev split; their paraphrases are held out.
        assertTrue(ds.filter { it.observed }.none { it.split == "holdout" && it.group == null })
    }
}

/**
 * HOTFIX 5 — REAL MEASUREMENT of the prompt the app sends, before vs after, over the 133-request benchmark.
 * TEST KIND: pure code. "Before" is a faithful reconstruction of the previous in-app builder (legacy asset text +
 * persona + name + date + notes + short memory + a recap of up to 16 messages x 320 chars pasted INTO the system
 * block). "After" is the shipped [ClassicPromptComposer] + [PromptBudgeter] with history handed over as seed turns.
 * Token figures are ESTIMATES (3 chars/token, conservative) — the SDK exposes no tokenizer. This measures prompt
 * size/headroom only; it says NOTHING about answer quality.
 */
class PromptBudgetBenchmarkTest {
    private val ds = loadDataset()
    private val legacyBase = File("src/test/resources/quality/legacy_system_prompt_it.md").readText()
    private val newBase = File("../app/src/main/assets/prompts/jarvis_system_it.md").readText()
    private val persona = "Hai un carattere asciutto e sicuro, con un tocco di ironia british alla Tony Stark. Dai del tu, vai dritto al punto, niente frasi di circostanza o servili. Sei competente e leale; se qualcosa non si può fare lo dici con garbo, senza girarci intorno."
    private val now = LocalDateTime.of(2026, 11, 13, 10, 30)
    private val budget = ContextBudget()

    private fun legacySystem(notes: List<String>, history: List<SeedTurn>): String = buildString {
        append(legacyBase.trim())
        append("\n\nPERSONALITÀ (adotta questo carattere in ogni risposta):\n").append(persona)
        append("\n\nTi chiami JARVIS. Se Simone ti chiede il tuo nome, rispondi con questo.")
        append("\n\nAdesso è 13 novembre 2026, 10:30.")
        if (notes.isNotEmpty()) { append("\n\nAPPUNTI DI SIMONE (usali quando sono pertinenti, senza citarli se non serve):\n"); notes.forEach { append("- ").append(it).append('\n') } }
        if (history.isNotEmpty()) {
            append("\n\nCONVERSAZIONE IN CORSO CON SIMONE (le battute precedenti, continuala senza ripeterla e senza salutare di nuovo):\n")
            history.takeLast(16).forEach { m -> append(if (m.fromUser) "Simone: " else "Tu: "); append(m.text.replace('\n', ' ').take(320)); append('\n') }
        }
    }

    private fun history(turns: Int, seedIdx: Int): List<SeedTurn> {
        val lens = intArrayOf(180, 320, 480, 640)
        val out = ArrayList<SeedTurn>()
        for (i in 0 until turns) {
            val q = ds[(seedIdx + i) % ds.size].prompt
            out += SeedTurn(true, q)
            out += SeedTurn(false, ("Risposta di esempio a «$q». " + "Testo di riempimento realistico per una risposta. ".repeat(20)).take(lens[(seedIdx + i) % lens.size]))
        }
        return out
    }

    private data class Row(val turns: Int, val oldTok: IntArray, val newTok: IntArray)

    @Test fun `prompt size and reply headroom before and after over the whole benchmark`() {
        val notes = listOf("Marco è il mio collega di lavoro, ci vediamo il giovedì.", "La mia auto ha la revisione a marzo.", "Il mio cibo preferito è la pizza.")
        val rows = ArrayList<Row>()
        val md = StringBuilder("# Prompt budget — before vs after (ESTIMATED tokens, 3 chars/token; window=${budget.windowTokens}, reserve=${budget.replyReserveTokens})\n\n")
        md.append("Requests: ${ds.size} (dev ${ds.count { it.split == "dev" }}, holdout ${ds.count { it.split == "holdout" }}); fixtures: history of 0/3/8/15 earlier exchanges, with and without 3 personal notes.\n\n")
        md.append("| history (exchanges) | before: avg / max | after: avg / max | before: headroom<reserve | after: headroom<reserve |\n|---|---|---|---|---|\n")
        for (turns in listOf(0, 3, 8, 15)) {
            val oldT = ArrayList<Int>(); val newT = ArrayList<Int>()
            ds.forEachIndexed { idx, req ->
                val h = history(turns, idx)
                val nts = if (idx % 2 == 0) notes else emptyList()
                val old = TokenEstimator.message(legacySystem(nts, h)) + TokenEstimator.message(req.prompt)
                val sys = ClassicPromptComposer.compose(newBase, persona, "JARVIS", now, nts, "", budget)
                val plan = PromptBudgeter.plan(sys, emptyList(), h, req.prompt, budget)
                assertFalse(plan.userExceedsBudget)
                assertTrue(plan.estTotal <= budget.promptCeiling, "after: ${plan.estTotal} > ${budget.promptCeiling} for ${req.id}")
                oldT += old; newT += plan.estTotal
            }
            fun s(l: List<Int>) = "${l.average().toInt()} / ${l.max()}"
            fun short(l: List<Int>) = l.count { budget.windowTokens - it < budget.replyReserveTokens }
            md.append("| $turns | ${s(oldT)} | ${s(newT)} | ${short(oldT)}/${oldT.size} | ${short(newT)}/${newT.size} |\n")
            if (turns >= 8) assertTrue(newT.average() < oldT.average(), "after must be smaller than before at history=$turns")
        }
        val sysOld = TokenEstimator.message(legacySystem(emptyList(), emptyList()))
        val sysNew = TokenEstimator.message(ClassicPromptComposer.compose(newBase, persona, "JARVIS", now, emptyList(), "", budget))
        md.append("\nBase system block (no notes, no history): before ${legacyBase.length + persona.length} chars / ~$sysOld tokens; after ${newBase.length + persona.length} chars / ~$sysNew tokens.\n")
        // Native conversation growth: when would the live KV cache hit the window?
        var usedOld = sysOld; var turnOld = -1
        val ledger = ConversationTokenLedger(budget); ledger.start(sysNew)
        var rotations = 0
        for (t in 1..60) {
            val u = TokenEstimator.message("domanda di prova numero $t, di lunghezza ordinaria per una chat"); val r = TokenEstimator.message("r".repeat(480))
            usedOld += u + r
            if (turnOld < 0 && usedOld > budget.windowTokens - budget.replyReserveTokens) turnOld = t
            if (ledger.needsRotation(u)) { rotations++; ledger.start(sysNew) }
            ledger.record(u, r)
            assertTrue(ledger.used <= budget.promptCeiling + u + r, "ledger must stay within the window")
        }
        md.append("Live conversation (60 turns, ~480-char replies): before — reply reserve is eaten at turn ${if (turnOld < 0) "never" else turnOld} and nothing prevents overflow; after — the conversation is rotated ${rotations} times to stay within the prompt ceiling.\n")
        File("build/reports/quality").apply { mkdirs() }.resolve("prompt_budget.md").writeText(md.toString())
        assertTrue(sysNew < sysOld)
    }
}
