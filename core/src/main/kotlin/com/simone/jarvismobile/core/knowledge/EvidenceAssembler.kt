package com.simone.jarvismobile.core.knowledge

import com.simone.jarvismobile.core.knowledge.zim.ZimKnowledgeSource
import com.simone.jarvismobile.core.knowledge.zim.ZimTitleSearch
import com.simone.jarvismobile.core.quality.AnswerQuality

/**
 * KNOWLEDGE ENGINE — decides whether retrieved passages are SUFFICIENT evidence, not merely lexically similar.
 *
 * Question → candidate passages (ZIM articles whose TITLE the question names, plus library-document passages) →
 * rank with the shared [KnowledgeSearch] → gate:
 *  - a passage from an article the question NAMED counts when it also shares part of the question's other terms
 *    (STRONG ≥ [STRONG_COVERAGE], otherwise MODERATE: the entity is right but the lead may not answer the question);
 *  - a passage with no named entity (a plain document) must share at least half of the question's content terms.
 * Anything else is [Evidence.NoEvidence]: a lexically similar paragraph about something else is not a proof.
 * The model is never asked to judge its own evidence — this gate is plain code over the retrieved text.
 */
class EvidenceAssembler(private val search: KnowledgeSearch = KnowledgeSearch(minScore = 0.0)) {

    fun assemble(
        question: String,
        zimHits: List<ZimKnowledgeSource.ArticleHit>,
        documentChunks: List<KnowledgeChunk>,
        limit: Int = 3,
    ): Evidence {
        val qStems = search.terms(question).distinct()
        if (qStems.isEmpty()) return Evidence.NoEvidence

        // candidate chunks + which ones come from a title-matched article
        val titleMatched = HashSet<String>()
        val approximate = HashSet<String>() // matched only by a typo-tolerant guess: never better than MODERATE
        val pool = ArrayList<KnowledgeChunk>()
        for (h in zimHits) {
            if (h.candidate.score >= TITLE_MATCH_MIN) titleMatched += h.chunks.first().sourceId
            if (h.candidate.kind == ZimTitleSearch.Kind.FUZZY) approximate += h.chunks.first().sourceId
            pool += h.chunks
        }
        pool += documentChunks
        if (pool.isEmpty()) return Evidence.NoEvidence

        val ranked = (search.search(question, pool, pool.size) as? Evidence.Grounded)?.passages.orEmpty()
        val scoredBySource = ranked.groupBy { it.chunk.sourceId }
        val accepted = ArrayList<Pair<ScoredChunk, EvidenceStrength>>()
        for (sc in ranked) {
            val chunkStems = search.terms(sc.chunk.text + " " + sc.chunk.section + " " + sc.chunk.sourceTitle).toSet()
            val coverage = qStems.count { it in chunkStems }.toDouble() / qStems.size
            val named = sc.chunk.sourceId in titleMatched
            val strength = when {
                named && coverage >= STRONG_COVERAGE && sc.chunk.sourceId !in approximate -> EvidenceStrength.STRONG
                named && coverage > 0.0 -> EvidenceStrength.MODERATE
                !named && coverage >= DOCUMENT_COVERAGE && sc.score >= DOCUMENT_MIN_SCORE -> EvidenceStrength.STRONG
                else -> null
            } ?: continue
            accepted += sc to strength
        }
        // An article the question NAMED is the subject even when no sentence repeats the question's other words (the
        // question may ask about something the article states differently). Its lead goes in as MODERATE evidence so the
        // model can say what the source does and does not contain, instead of improvising.
        val acceptedSources = accepted.map { it.first.chunk.sourceId }.toSet()
        for (h in zimHits) {
            val lead = h.chunks.firstOrNull() ?: continue
            if (h.candidate.score >= TITLE_MATCH_MIN && lead.sourceId !in acceptedSources) {
                accepted += ScoredChunk(lead, 0.0) to EvidenceStrength.MODERATE
            }
        }
        if (accepted.isEmpty()) return Evidence.NoEvidence

        // Entity coverage: a PROPER NOUN of the question (capitalised, not the first word) that no retrieved passage covers
        // means the question's subject is missing from the sources — a generic-word article ("Population") that merely shares
        // a word with it is not evidence about "the population of Brazil".
        val nouns = properNouns(question)
        val covered = StringBuilder()
        for (h in zimHits) if (h.candidate.score >= TITLE_MATCH_MIN) covered.append(h.candidate.phrase).append(' ')
        for ((sc, _) in accepted) covered.append(sc.chunk.sourceTitle).append(' ').append(sc.chunk.text).append(' ')
        // compared squashed, so "Wi-Fi", "Wi Fi" and "wifi" are the same name
        val coveredSquashed = KnowledgeText.squash(covered.toString())
        val uncovered = nouns.filter { n -> KnowledgeText.squash(n).let { it.length >= 3 && it !in coveredSquashed } }
        if (nouns.isNotEmpty() && uncovered.size == nouns.size) return Evidence.NoEvidence

        // at most two passages per article, strongest first, newest source first on ties
        val perSource = HashMap<String, Int>()
        val chosen = accepted.sortedWith(
            compareByDescending<Pair<ScoredChunk, EvidenceStrength>> { it.second == EvidenceStrength.STRONG }
                .thenByDescending { it.first.score }
                .thenByDescending { it.first.chunk.date },
        ).filter { (sc, _) -> (perSource.merge(sc.chunk.sourceId, 1, Int::plus) ?: 1) <= MAX_PER_SOURCE }.take(limit)

        val strength = if (chosen.any { it.second == EvidenceStrength.STRONG }) EvidenceStrength.STRONG else EvidenceStrength.MODERATE
        val conflict = hasConflict(chosen.map { it.first.chunk })
        // When sources disagree the most recent one is presented first.
        val ordered = if (conflict) chosen.sortedByDescending { it.first.chunk.date } else chosen
        return Evidence.Grounded(
            passages = ordered.map { it.first },
            strength = strength,
            conflict = conflict,
            titleMatched = ordered.any { it.first.chunk.sourceId in titleMatched },
            uncovered = uncovered,
        )
    }

    /** Capitalised words that are not sentence-initial and not function words: the names the question is about. */
    private fun properNouns(question: String): List<String> {
        val out = LinkedHashSet<String>()
        val ws = Regex("""\p{L}[\p{L}'’-]*""").findAll(question).toList()
        for ((i, m) in ws.withIndex()) {
            val w = m.value
            if (i == 0 || w.length < 3 || !w[0].isUpperCase()) continue
            var j = m.range.first - 1
            while (j >= 0 && question[j] == ' ') j--
            if (j < 0 || question[j] in ".!?:;") continue // sentence-initial
            if (KnowledgeText.isStopword(KnowledgeText.fold(w))) continue
            out += w.trim('\'', '’', '-')
        }
        return out.toList()
    }

    /** Two collections that cover the SAME subject and state different numbers disagree; the caller must say so. */
    private fun hasConflict(chunks: List<KnowledgeChunk>): Boolean {
        val bySubject = chunks.filter { it.sourceKind == "zim" }.groupBy { KnowledgeText.fold(it.sourceTitle) }
        for ((_, group) in bySubject) {
            val byCollection = group.groupBy { it.collection + "|" + it.date }
            if (byCollection.size < 2) continue
            val sets = byCollection.values.map { g -> g.flatMap { AnswerQuality.significantNumbers(it.text) }.map { n -> n.filter(Char::isDigit) }.toSet() }
            if (sets.all { it.isNotEmpty() } && sets.zipWithNext().any { (a, b) -> a.intersect(b).isEmpty() }) return true
        }
        return false
    }

    companion object {
        const val STRONG_COVERAGE = 0.34
        const val DOCUMENT_COVERAGE = 0.5
        const val DOCUMENT_MIN_SCORE = 0.12
        const val TITLE_MATCH_MIN = 0.45
        const val MAX_PER_SOURCE = 2
    }
}
