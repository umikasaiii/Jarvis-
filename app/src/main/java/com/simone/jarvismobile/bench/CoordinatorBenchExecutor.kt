package com.simone.jarvismobile.bench

import com.simone.jarvismobile.audio.ChatTestSeam
import com.simone.jarvismobile.audio.SessionCoordinator
import com.simone.jarvismobile.core.bench.BenchCase
import com.simone.jarvismobile.core.bench.BenchExecutor
import com.simone.jarvismobile.core.bench.BenchFixtures
import com.simone.jarvismobile.core.bench.BenchOutcome
import com.simone.jarvismobile.core.bench.CitationResolver
import com.simone.jarvismobile.core.bench.RetrievedEvidence
import com.simone.jarvismobile.core.knowledge.Evidence

/**
 * M4 — runs a case through the REAL [SessionCoordinator] (routing, evidence package, claim verification, citation rebuild, attempt budget, framing).
 *  - [scriptedAnswer] == null: the coordinator calls the REAL loaded model → a real MODEL run (only meaningful on a device with a model).
 *  - [scriptedAnswer] != null: the model call is replaced through the existing [ChatTestSeam] → a pipeline check on the emulator, NEVER a model result;
 *    the caller must then label the run PIPELINE_CONTROL.
 * Knowledge evidence comes from [retrieve] (the production assembler over the fixture articles, injected through the seam so the real model answers from it).
 * Not executable here (reported NOT_EXECUTED with a reason, never faked): memory and document cases (synthetic facts cannot be put into the user's real
 * stores), tool cases (the coordinator does not expose which tool ran), conflicts across archives that are not installed.
 * Independent cases start on a fresh conversation; multi-turn cases keep their context, and `<<NEW_CONVERSATION>>` resets it on purpose.
 */
class CoordinatorBenchExecutor(
    private val coordinator: SessionCoordinator,
    private val retrieve: suspend (BenchCase) -> RetrievedEvidence,
    private val scriptedAnswer: ((BenchCase) -> String?)? = null,
    private val timeoutMs: Long = 120_000,
) : BenchExecutor {
    private var seq = 0

    override suspend fun reset(caseId: String) {
        coordinator.testSeam = null
        coordinator.newConversation()
    }

    override fun whyNot(c: BenchCase): String = when {
        c.category == "memoria" || c.category == "documenti" -> "synthetic memory/documents cannot be injected into the real stores without touching user data"
        c.criterion == "TOOL_NUMERIC" -> "the coordinator does not expose which tool ran: routing is not observable here"
        scriptedAnswer != null && c.requiresLlm -> "needs a real model's generation"
        else -> "not supported by this executor"
    }

    override suspend fun execute(c: BenchCase, fixtures: BenchFixtures): BenchOutcome? {
        if (c.category == "memoria" || c.category == "documenti" || c.criterion == "TOOL_NUMERIC") return null
        if (scriptedAnswer != null && c.requiresLlm) return null
        val needsKnowledge = !c.requiresLlm && c.criterion != "TOOL_NUMERIC"
        var retrieved: RetrievedEvidence? = null
        if (needsKnowledge) {
            retrieved = retrieve(c)
            val script = scriptedAnswer
            val chatFn: (suspend (String) -> String?)? = if (script == null) null else { _ -> script(c) }
            coordinator.testSeam = ChatTestSeam(chat = chatFn, evidence = retrieved.evidence)
        } else if (scriptedAnswer != null) {
            val script = scriptedAnswer
            val chatFn: (suspend (String) -> String?)? = { _ -> script(c) }
            coordinator.testSeam = ChatTestSeam(chat = chatFn)
        }
        var last: String? = null
        for (turn in c.turns) {
            if (turn == "<<NEW_CONVERSATION>>") { coordinator.newConversation(); continue }
            last = kotlinx.coroutines.withTimeout(timeoutMs) { coordinator.processQueuedText("m4-${System.nanoTime()}-${seq++}", turn) }
        }
        val text = last ?: return BenchOutcome("", "MODEL", error = "no_reply")
        val ev = retrieved?.evidence as? Evidence.Grounded
        val passages = ev?.passages.orEmpty()
        val ids = passages.map { retrieved!!.passageId(it) }
        val cites = citedFrom(text, passages, ids)
        return BenchOutcome(
            publishedText = text, mode = "MODEL", retrievedPassages = ids, citedPassages = cites.passageIds, citationsAmbiguous = cites.ambiguous + cites.unresolved,
            supportEstablished = ev?.supportEstablished, conflictFlagged = ev?.conflict,
        )
    }

    /**
     * Maps the published footer back to retrieved passages from the system's own data only (label + retrieval order). It never reads the case's gold
     * citations: an article-only label shared by several retrieved passages is AMBIGUOUS and is reported as such, never assigned to the expected one.
     */
    private fun citedFrom(text: String, passages: List<com.simone.jarvismobile.core.knowledge.ScoredChunk>, ids: List<String>): CitationResolver.Resolved {
        val footer = text.substringAfter("Fonti a supporto:", "").lines().map { it.trim() }.filter { Regex("""^\[\d+]""").containsMatchIn(it) }
        return CitationResolver.resolve(footer, passages.indices.map { CitationResolver.Candidate(ids[it], passages[it].chunk.reference) })
    }
}
