package com.simone.jarvismobile.core.bench

import com.simone.jarvismobile.core.knowledge.KnowledgeText

/** What a run observed for one case (no model text beyond the published answer). */
data class BenchOutcome(
    val publishedText: String,
    /** VERIFIED / ALLOWED_PARTS / FAITHFUL_EXTRACT / ABSTAIN / UNVERIFIED_FRAMED / TOOL / MODEL (a real run reports its own). */
    val mode: String,
    val retrievedPassages: List<String> = emptyList(),
    /** Passage ids the published `[n]` markers resolve to. */
    val citedPassages: List<String> = emptyList(),
    val supportEstablished: Boolean? = null,
    val conflictFlagged: Boolean? = null,
    val toolName: String? = null,
    val toolNumber: Double? = null,
    /** Footer citations that could not be tied to ONE retrieved passage (AMBIGUOUS / NOT_VERIFIABLE): never counted as valid. */
    val citationsAmbiguous: Int = 0,
    val error: String? = null,
    val latencyMs: Long? = null,
)

enum class Dim { RETRIEVAL, FACT, FORBIDDEN_ABSENT, NUMERIC, CITATION_VALID, ABSTENTION, SUFFICIENCY, CONFLICT_SHOWN, ROUTING_ACTION, FORBIDDEN_ACTION, NO_FALSE_PROOF }

data class CaseScore(
    val id: String, val category: String, val split: String, val criterion: String,
    val executed: Boolean, val notExecutedReason: String?,
    /** null = not applicable to this case. */
    val dims: Map<Dim, Boolean?>, val passed: Boolean?, val notes: List<String>,
    /** An answer that restates a forbidden/unsupported claim as verified or cited (the worst class). */
    val falseProof: Boolean = false,
    /** The answer declined or qualified instead of answering (correct for ABSENT cases, a miss otherwise). */
    val abstained: Boolean = false,
    val latencyMs: Long? = null,
)

object BenchScoring {
    private val ABSTAIN_RE = Regex("""(?i)\b(non (ho|trovo|riesco|so|c'è|è (verificat|confermat|presente|riportat))|nelle fonti[^.]{0,60}non|non verificat|non è stato possibile|nessuna informazione|manca)""")
    private val CITATION_RE = Regex("""\[(\d{1,3})]""")

    fun norm(s: String): String = KnowledgeText.fold(s).replace(Regex("(?<=\\d)[.\\s](?=\\d{3}\\b)"), "").replace(Regex("(?<=\\d),(?=\\d)"), ".").replace(Regex("\\s+"), " ").trim()

    /** A claim is present if the claim or one of its variants appears in the normalised text; digits are compared after thousand-separator normalisation. */
    fun has(text: String, claim: String, variants: List<String> = emptyList()): Boolean {
        val t = norm(text)
        // whole-word match: "Porto" is not in "Portogallo", "14" is not in "142"
        return (listOf(claim) + variants.filter { v -> norm(v).contains(norm(claim)) || norm(claim).contains(norm(v)) }).any { c ->
            val n = norm(c); n.isNotEmpty() && Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(n) + "(?![\\p{L}\\p{N}])").containsMatchIn(t)
        }
    }

    /** A forbidden claim "A&&B" is hit only when A and B occur in the SAME sentence (e.g. an entity bound to the wrong value); a plain claim is a whole-word match. */
    fun hasForbidden(text: String, claim: String): Boolean {
        if (!claim.contains("&&")) return has(text, claim)
        val parts = claim.split("&&").map { it.trim() }.filter { it.isNotEmpty() }
        return text.split(Regex("(?<=[.!?;\\n])\\s+")).any { sentence -> parts.all { has(sentence, it) } }
    }

    fun numbersIn(s: String): List<Double> = Regex("""-?\d+(?:[.,]\d+)?""").findAll(norm(s)).mapNotNull { it.value.toDoubleOrNull() }.toList()

    fun withinTolerance(got: Double, g: NumericGold): Boolean = if (g.kind == "rel") kotlin.math.abs(got - g.value) <= g.tolerance * kotlin.math.abs(g.value) else kotlin.math.abs(got - g.value) <= g.tolerance

    fun notExecuted(c: BenchCase, reason: String) = CaseScore(c.id, c.category, c.split, c.criterion, false, reason, emptyMap(), null, emptyList())

    fun score(c: BenchCase, o: BenchOutcome): CaseScore {
        val d = LinkedHashMap<Dim, Boolean?>(); val notes = ArrayList<String>()
        // the `[n]` markers are citations, not part of the claim: "tra 60 [1] e 100" states "tra 60 e 100"
        val text = o.publishedText.replace(CITATION_RE, " ").replace(Regex("\\s+"), " ")
        val abstained = ABSTAIN_RE.containsMatchIn(text) || o.mode == "ABSTAIN"
        val forbiddenHit = c.forbiddenClaims.filter { hasForbidden(text, it) }
        // an ABSENT case may answer from general knowledge ONLY if the answer is explicitly qualified as unverified and cites nothing: that is a cautious,
        // honestly-labelled answer, not a hallucination (it is counted separately as "qualified unverified")
        val qualifiedOnly = c.criterion == "ABSTAIN" && o.mode == "UNVERIFIED_FRAMED" && ABSTAIN_RE.containsMatchIn(o.publishedText) && o.citedPassages.isEmpty()
        d[Dim.FORBIDDEN_ABSENT] = if (c.forbiddenClaims.isEmpty()) null else (forbiddenHit.isEmpty() || qualifiedOnly)
        if (forbiddenHit.isNotEmpty()) notes += "forbidden claim published: " + forbiddenHit.joinToString()
        val cited = o.citedPassages
        // a forbidden claim is a FALSE PROOF only when the answer presents it as verified or cites a source for it; a clearly qualified general-knowledge answer is not
        val framedUnverified = o.mode == "UNVERIFIED_FRAMED" && ABSTAIN_RE.containsMatchIn(text)
        val falseProof = forbiddenHit.isNotEmpty() && !framedUnverified && (cited.isNotEmpty() || o.mode == "VERIFIED" || o.mode == "ALLOWED_PARTS")
        d[Dim.NO_FALSE_PROOF] = !falseProof

        when (c.criterion) {
            "KNOWLEDGE_ANSWER" -> {
                d[Dim.RETRIEVAL] = c.supportingEvidence.isEmpty().not() && o.retrievedPassages.containsAll(c.supportingEvidence)
                d[Dim.SUFFICIENCY] = o.supportEstablished?.let { it == true }
                val missing = c.requiredClaims.filterNot { has(text, it, c.acceptableVariants) }
                d[Dim.FACT] = missing.isEmpty(); if (missing.isNotEmpty()) notes += "missing claim: " + missing.joinToString()
                c.numeric?.let { g -> d[Dim.NUMERIC] = numbersIn(text).any { withinTolerance(it, g) } }
                val allowed = (c.expectedCitations + c.supportingEvidence).toSet()
                val invalid = cited.filter { it !in allowed }
                d[Dim.CITATION_VALID] = cited.isNotEmpty() && invalid.isEmpty() && o.citationsAmbiguous == 0 && cited.any { it in c.expectedCitations }
                if (o.citationsAmbiguous > 0) notes += "citation not verifiable (article label shared by several passages): ${o.citationsAmbiguous}"
                if (invalid.isNotEmpty()) notes += "citation to a passage that does not support: " + invalid.joinToString()
            }
            "ABSTAIN" -> {
                d[Dim.SUFFICIENCY] = o.supportEstablished?.let { !it }
                d[Dim.ABSTENTION] = abstained
                d[Dim.CITATION_VALID] = cited.isEmpty() && o.citationsAmbiguous == 0
                if (!abstained) notes += "no abstention/qualification in the published text"
            }
            "CONFLICT" -> {
                d[Dim.CONFLICT_SHOWN] = c.requiredClaims.all { has(text, it, c.acceptableVariants) } && (o.conflictFlagged == true || ABSTAIN_RE.containsMatchIn(text) || norm(text).contains("non concord"))
                d[Dim.CITATION_VALID] = cited.isNotEmpty() && o.citationsAmbiguous == 0 && cited.toSet().containsAll(c.expectedCitations.toSet())
            }
            "TOOL_NUMERIC" -> {
                val exp = c.expectedActions.firstOrNull()
                d[Dim.ROUTING_ACTION] = exp != null && o.toolName == exp.tool
                d[Dim.NUMERIC] = c.numeric != null && o.toolNumber != null && withinTolerance(o.toolNumber, c.numeric)
                d[Dim.FORBIDDEN_ACTION] = if (c.forbiddenActions.isEmpty()) null else o.toolName !in c.forbiddenActions
            }
            "REASONING_EXACT", "CONVERSATION_CONTINUITY", "HUMAN_REVIEW" -> {
                if (c.requiredClaims.isNotEmpty()) d[Dim.FACT] = c.requiredClaims.all { has(text, it, c.acceptableVariants) }
                c.numeric?.let { g -> d[Dim.NUMERIC] = numbersIn(text).any { withinTolerance(it, g) } }
            }
        }
        if (o.error != null) notes += "error: ${o.error}"
        val decided = d.values.filterNotNull()
        val passed = if (c.humanReview && c.criterion == "HUMAN_REVIEW") null else decided.isNotEmpty() && decided.all { it } && o.error == null
        return CaseScore(c.id, c.category, c.split, c.criterion, true, null, d, passed, notes, falseProof, abstained, o.latencyMs)
    }
}
