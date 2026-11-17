package com.simone.jarvismobile.core.bench

import com.simone.jarvismobile.core.knowledge.Evidence
import com.simone.jarvismobile.core.knowledge.EvidenceAssembler
import com.simone.jarvismobile.core.knowledge.KnowledgeChunk
import com.simone.jarvismobile.core.knowledge.ScoredChunk
import com.simone.jarvismobile.core.reliability.AnswerFraming
import com.simone.jarvismobile.core.reliability.EvidenceKind
import com.simone.jarvismobile.core.reliability.EvidencePackage
import com.simone.jarvismobile.core.reliability.PublicationKind
import com.simone.jarvismobile.core.reliability.PublicationPolicy
import com.simone.jarvismobile.core.reliability.RequestKind
import com.simone.jarvismobile.core.reliability.RetrievalState
import com.simone.jarvismobile.core.tools.builtin.CalculateTool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import com.simone.jarvismobile.core.tools.ToolResult

/** What retrieval returned for a case, plus how to name a retrieved chunk in fixture-passage terms (`article#section`). */
class RetrievedEvidence(val evidence: Evidence, val passageId: (ScoredChunk) -> String)

/**
 * PIPELINE CONTROL — drives the PRODUCTION retrieval result, [PublicationPolicy] (claim verification, rebuilt citations, abstention), [AnswerFraming] and
 * the real [CalculateTool] with SCRIPTED candidate answers (`control.faithful` / `control.hallucinated`). It proves that the harness, the scoring and the
 * publication gate behave as specified on the whole dataset; it does NOT measure any model: the candidate is not generated. Cases that need a model's own
 * generation (reasoning, conversation) or Android tools are reported NOT_EXECUTED. Memory and document evidence is GIVEN (the retrieval of those stores has
 * its own tests); only knowledge goes through the supplied [retrieve].
 */
class PipelineControlExecutor(
    private val retrieve: suspend (BenchCase) -> RetrievedEvidence,
    private val variant: String = "faithful",
) : BenchExecutor {
    override suspend fun execute(c: BenchCase, fixtures: BenchFixtures): BenchOutcome? {
        if (c.requiresLlm || c.requiresAndroidTools) return null
        val question = c.turns.last()
        if (c.criterion == "TOOL_NUMERIC") return tool(c)
        val candidate = (if (variant == "hallucinated") c.control?.hallucinated ?: c.control?.faithful else c.control?.faithful) ?: return null

        val raws = ArrayList<EvidencePackage.Companion.Raw>(); val keys = ArrayList<String>()
        val retrievedIds = ArrayList<String>()
        var support: Boolean? = null; var conflict: Boolean? = null
        var state = RetrievalState.NO_RESULT
        val needsKnowledge = c.category !in setOf("memoria", "documenti")
        if (needsKnowledge) {
            val r = retrieve(c)
            when (val e = r.evidence) {
                is Evidence.Grounded -> {
                    support = e.supportEstablished; conflict = e.conflict
                    e.passages.forEach { retrievedIds += r.passageId(it) }
                    if (e.supportEstablished) e.passages.forEach { p -> raws += EvidencePackage.Companion.Raw(p.chunk.reference, p.chunk.section, p.supportWindow.ifBlank { p.chunk.text }.replace('\n', ' '), r.passageId(p)); keys += r.passageId(p) }
                    else state = RetrievalState.INSUFFICIENT
                }
                is Evidence.Insufficient -> { support = false; state = RetrievalState.INSUFFICIENT }
                Evidence.NoEvidence -> { support = false; state = RetrievalState.NO_RESULT }
            }
        } else {
            // only ACTIVE facts are evidence: a supposition ("immagino che…") or a superseded entry is not retrievable as a fact (M1 rule)
            c.context.memory.forEach { id -> fixtures.memory.firstOrNull { it.id == id && it.kind == "fact" }?.let { m -> raws += EvidencePackage.Companion.Raw("Memoria", m.type, m.text, "mem:${m.id}", EvidenceKind.MEMORY); retrievedIds += "mem:${m.id}" } }
            c.context.documents.forEach { id -> fixtures.documents.firstOrNull { it.id == id }?.let { d -> d.pages.forEach { pg -> raws += EvidencePackage.Companion.Raw(d.title, "p.${pg.page}", pg.text, "doc:${d.id}#${pg.page}", EvidenceKind.DOCUMENT); retrievedIds += "doc:${d.id}#${pg.page}" } } }
            support = null // memory/document evidence is GIVEN here: sufficiency is not assessed for it
        }
        val pkg = EvidencePackage.of(raws)
        if (pkg.items.isEmpty()) {
            val framed = AnswerFraming.frame(RequestKind.FACTUAL, if (state == RetrievalState.INSUFFICIENT) RetrievalState.INSUFFICIENT else RetrievalState.NO_RESULT, PublicationPolicy.stripCitations(candidate), question)
            return BenchOutcome(framed, "UNVERIFIED_FRAMED", retrievedIds, emptyList(), support, conflict)
        }
        val pub = PublicationPolicy.decide(question, candidate, pkg, PublicationKind.ANSWER)
        val markers = Regex("""\[(\d{1,3})]""").findAll(pub.text).map { it.groupValues[1].toInt() }.toSet()
        val cited = markers.mapNotNull { n -> pub.sources.getOrNull(n - 1)?.sourceKey }.map { it }
        return BenchOutcome(pub.text, pub.mode.name, retrievedIds, cited, support, conflict)
    }

    private suspend fun tool(c: BenchCase): BenchOutcome? {
        val a = c.expectedActions.firstOrNull() ?: return null
        if (a.tool != "calculate") return null
        val res = CalculateTool().execute(JsonObject(mapOf("expression" to JsonPrimitive(a.args["expression"].orEmpty()))))
        val v = (res as? ToolResult.Success)?.output?.get("result")?.jsonPrimitive?.doubleOrNull
        return BenchOutcome(if (v != null) "Il risultato è $v" else "errore", "TOOL", toolName = "calculate", toolNumber = v)
    }

    /** The retrieval used when no archive is available: the production assembler over the fixture articles' passages as chunks (no title search, no index). */
    companion object {
        fun chunkRetrieval(fixtures: BenchFixtures): suspend (BenchCase) -> RetrievedEvidence = { c ->
            val chunks = fixtures.articles.flatMap { a -> a.sections.mapIndexed { i, s -> KnowledgeChunk("fx:${a.id}", a.title, s.name.ifEmpty { a.title }, s.text, sourceKind = "zim", collection = a.collection, date = a.date, lead = i == 0) } }
            RetrievedEvidence(EvidenceAssembler().assemble(c.turns.last(), emptyList(), chunks, 5)) { sc -> sc.chunk.sourceId.removePrefix("fx:") + "#" + (if (sc.chunk.section == sc.chunk.sourceTitle) "lead" else sc.chunk.section) }
        }
    }
}
