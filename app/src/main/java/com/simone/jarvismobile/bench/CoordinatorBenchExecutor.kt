package com.simone.jarvismobile.bench

import com.simone.jarvismobile.audio.ChatTestSeam
import com.simone.jarvismobile.audio.SessionCoordinator
import com.simone.jarvismobile.core.bench.BenchCase
import com.simone.jarvismobile.core.bench.BenchExecutor
import com.simone.jarvismobile.core.bench.BenchFixtures
import com.simone.jarvismobile.core.bench.BenchOutcome
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
        return BenchOutcome(
            publishedText = text, mode = "MODEL", retrievedPassages = ids, citedPassages = citedFrom(text, passages.map { it.chunk.reference to it }, ids, c),
            supportEstablished = ev?.supportEstablished, conflictFlagged = ev?.conflict,
        )
    }

    /**
     * Maps the published footer back to fixture passages. The footer names the SOURCE (title + collection), and a passage only when two passages share a label
     * (`(brano E3)` = the third supporting passage); otherwise the passage inside that article that the case expects is chosen. Section-level exactness is therefore
     * verified only for duplicate labels — a documented limit of device-mode citation checking.
     */
    private fun citedFrom(text: String, refs: List<Pair<String, com.simone.jarvismobile.core.knowledge.ScoredChunk>>, ids: List<String>, c: BenchCase): List<String> {
        val footer = text.substringAfter("Fonti a supporto:", "").lines().map { it.trim() }.filter { Regex("""^\[\d+]""").containsMatchIn(it) }
        val out = ArrayList<String>()
        for (line in footer) {
            val label = line.replace(Regex("""^\[\d+]\s*"""), "")
            Regex("""\(brano E(\d+)\)""").find(label)?.let { m -> ids.getOrNull(m.groupValues[1].toInt() - 1)?.let { out += it }; continue }
            val matching = refs.indices.filter { i -> label.startsWith(refs[i].first.substringBefore(" (")) }
            val pick = matching.map { ids[it] }.let { cand -> cand.firstOrNull { it in c.expectedCitations } ?: cand.firstOrNull() }
            if (pick != null) out += pick
        }
        return out.distinct()
    }
}
