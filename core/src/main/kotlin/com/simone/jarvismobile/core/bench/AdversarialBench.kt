package com.simone.jarvismobile.core.bench

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * ADVERSARIAL claim-safety sets (M4 closure). Each case pairs a small source set with ONE candidate answer that is either an altered claim (must not be
 * published as verified) or a benign restatement (must be verified). Three sets are kept apart and reported separately:
 *  - A: the original 150-case M4 dataset (not in this file),
 *  - B: regression set — the verifier rules were designed while reading these cases,
 *  - C: verification set — written after the rules were fixed and run once; the rules were not tuned on its failures. NOT blind: the author read it.
 * The detector is NOT a static forbidden string: a case fails when the verifier's own decision publishes the altered candidate as VERIFIED, or any altered
 * marker survives into text that is cited / presented as verified.
 */
@Serializable data class AdvSource(val kind: String, val id: String, val title: String, val collection: String = "Fixture A", val date: String = "2026-01-01",
                                   val sections: List<List<String>> = emptyList(), val pages: List<String> = emptyList())
@Serializable data class AdvCase(val id: String, val set: String, val mutation: String, val question: String, val sources: List<AdvSource>, val candidate: String,
                                 val expect: String, val alteredMarkers: List<String> = emptyList(), val trueMarker: String? = null, val expectedCitation: String? = null, val note: String = "")
@Serializable data class AdvFile(val version: String, val set: String, val role: String, val cases: List<AdvCase>)

enum class AdvVerdict { PASS, FALSE_VERIFIED, FALSE_ALLOWED_PARTS, ALTERED_CLAIM_PUBLISHED, IRRELEVANT_EXTRACT, NON_SUPPORTING_CITATION, FALSE_ABSTENTION, CORRECT_ANSWER_BLOCKED, PARTIAL_CORRECT, CONFLICT_NOT_SHOWN }

data class AdvResult(val id: String, val mutation: String, val expect: String, val mode: String, val verdict: AdvVerdict, val cited: List<String>, val text: String)

data class AdvSummary(val set: String, val total: Int, val pass: Int, val byVerdict: Map<AdvVerdict, Int>, val byMutation: Map<String, Pair<Int, Int>>) {
    val falseVerified get() = byVerdict[AdvVerdict.FALSE_VERIFIED] ?: 0
    val falseAllowedParts get() = byVerdict[AdvVerdict.FALSE_ALLOWED_PARTS] ?: 0
    val nonSupportingCitations get() = byVerdict[AdvVerdict.NON_SUPPORTING_CITATION] ?: 0
    val falseAbstentions get() = byVerdict[AdvVerdict.FALSE_ABSTENTION] ?: 0
    val correctBlocked get() = byVerdict[AdvVerdict.CORRECT_ANSWER_BLOCKED] ?: 0
    val partialCorrect get() = byVerdict[AdvVerdict.PARTIAL_CORRECT] ?: 0
}

object AdversarialBench {
    private val json = Json { ignoreUnknownKeys = true }
    fun load(resource: String): AdvFile = json.decodeFromString(AdvFile.serializer(), requireNotNull(AdversarialBench::class.java.getResourceAsStream("/bench/$resource")) { "missing $resource" }.bufferedReader().readText())

    fun fixturesOf(c: AdvCase): BenchFixtures = BenchFixtures(
        version = "adv-" + c.id,
        articles = c.sources.filter { it.kind == "article" }.map { s -> FixtureArticle(s.id, s.title, s.collection, s.date, s.sections.map { FixtureSection(it[0], it[1]) }) },
        documents = c.sources.filter { it.kind == "document" }.map { s -> FixtureDocument(s.id, s.title, s.pages.mapIndexed { i, t -> FixtureDocPage(i + 1, t) }) },
    )

    fun caseOf(c: AdvCase): BenchCase {
        val doc = c.sources.any { it.kind == "document" }
        return BenchCase(id = c.id, category = if (doc) "documenti" else "adversarial", turns = listOf(c.question),
            context = BenchContext(documents = c.sources.filter { it.kind == "document" }.map { it.id }), source = "adv", sourceVersion = "1", criterion = "KNOWLEDGE_ANSWER", split = "DEV",
            control = ControlScript(faithful = c.candidate, hallucinated = c.candidate))
    }

    private fun markerIn(text: String, marker: String) = BenchScoring.hasForbidden(text.replace(Regex("""\[\d{1,3}]"""), " "), marker)

    /** Gold-independent for altered cases: the decision itself (mode) is judged first; markers only decide whether surviving text is the altered claim. */
    fun judge(c: AdvCase, o: BenchOutcome): AdvResult {
        val text = o.publishedText
        val altered = c.alteredMarkers.filter { markerIn(text, it) }
        val abstainLike = o.mode == "ABSTAIN" || o.mode == "UNVERIFIED_FRAMED" || Regex("""(?i)\bnon (trovo|ho|è stato|verificat)|nelle fonti[^.]{0,60}non""").containsMatchIn(text)
        val v = when (c.expect) {
            "NOT_VERIFIED" -> when {
                o.mode == "VERIFIED" -> AdvVerdict.FALSE_VERIFIED
                o.mode == "ALLOWED_PARTS" && altered.isNotEmpty() -> AdvVerdict.FALSE_ALLOWED_PARTS
                altered.isNotEmpty() && o.mode != "UNVERIFIED_FRAMED" -> AdvVerdict.ALTERED_CLAIM_PUBLISHED
                else -> AdvVerdict.PASS
            }
            "ABSTAIN" -> when {
                o.mode == "VERIFIED" || o.mode == "ALLOWED_PARTS" -> AdvVerdict.FALSE_VERIFIED
                !abstainLike || altered.isNotEmpty() -> AdvVerdict.IRRELEVANT_EXTRACT
                else -> AdvVerdict.PASS
            }
            "VERIFIED" -> {
                val hasTrue = c.trueMarker?.let { markerIn(text, it) } ?: true
                val okCite = c.expectedCitation == null || (o.citedPassages.isNotEmpty() && o.citedPassages.all { it.startsWith(c.expectedCitation) || it.contains(c.expectedCitation) })
                when {
                    abstainLike && !hasTrue -> AdvVerdict.CORRECT_ANSWER_BLOCKED
                    o.mode == "VERIFIED" && hasTrue && okCite -> AdvVerdict.PASS
                    o.mode == "VERIFIED" && hasTrue -> AdvVerdict.NON_SUPPORTING_CITATION
                    hasTrue -> AdvVerdict.PARTIAL_CORRECT
                    else -> AdvVerdict.CORRECT_ANSWER_BLOCKED
                }
            }
            "CONFLICT" -> if (c.alteredMarkers.all { markerIn(text, it) } && (o.conflictFlagged == true || Regex("(?i)non concord|discord|differ|contrast").containsMatchIn(text))) AdvVerdict.PASS else AdvVerdict.CONFLICT_NOT_SHOWN
            else -> AdvVerdict.PASS
        }
        return AdvResult(c.id, c.mutation, c.expect, o.mode, v, o.citedPassages, text)
    }

    fun summarize(set: String, rs: List<AdvResult>): AdvSummary = AdvSummary(set, rs.size, rs.count { it.verdict == AdvVerdict.PASS }, rs.groupingBy { it.verdict }.eachCount(),
        rs.groupBy { it.mutation }.mapValues { (_, l) -> l.count { it.verdict == AdvVerdict.PASS } to l.size })

    fun markdown(s: AdvSummary, rs: List<AdvResult>): String = buildString {
        appendLine("### Set ${s.set}: ${s.pass}/${s.total} pass — false VERIFIED ${s.falseVerified}, false ALLOWED_PARTS ${s.falseAllowedParts}, non-supporting citations ${s.nonSupportingCitations}, false abstentions ${s.falseAbstentions}, correct answers blocked ${s.correctBlocked}, partial correct ${s.partialCorrect}")
        s.byMutation.toSortedMap().forEach { (m, p) -> appendLine("- $m: ${p.first}/${p.second}") }
        rs.filter { it.verdict != AdvVerdict.PASS }.forEach { appendLine("- FAIL ${it.id} [${it.verdict}] mode=${it.mode} :: ${it.text.replace("\n", " / ")}") }
    }
}
