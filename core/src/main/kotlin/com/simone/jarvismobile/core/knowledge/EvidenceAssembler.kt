package com.simone.jarvismobile.core.knowledge

import com.simone.jarvismobile.core.knowledge.zim.ZimKnowledgeSource
import com.simone.jarvismobile.core.knowledge.zim.ZimTitleSearch
import com.simone.jarvismobile.core.quality.AnswerQuality

/**
 * KNOWLEDGE ENGINE 2.0 — separates RELEVANCE from SUFFICIENCY.
 *
 * Question → candidate passages (ZIM articles whose TITLE the question names, plus library-document passages) → rank with the
 * shared [KnowledgeSearch] → [SupportJudge] on every candidate: a passage is evidence only if a window of one or two
 * sentences contains the question's terms together (terms the article's own title names are implied). Outcomes:
 *  - [Evidence.Grounded]: at least one supporting window; each [ScoredChunk.supportWindow] holds it. STRONG only when the
 *    article is about the subject AND the window covers every other term; otherwise MODERATE.
 *  - [Evidence.Insufficient]: relevant sources exist (a title the question named, a document passage sharing its terms) but no
 *    window supports the question. They are reported as consulted, never as support.
 *  - [Evidence.NoEvidence]: nothing relevant.
 * The model is never asked to judge its own evidence — this is plain code over the retrieved text, and it does NOT claim
 * semantic understanding: see [SupportJudge] for the limits.
 */
class EvidenceAssembler(private val search: KnowledgeSearch = KnowledgeSearch(minScore = 0.0)) {

    fun assemble(
        question: String,
        zimHits: List<ZimKnowledgeSource.ArticleHit>,
        documentChunks: List<KnowledgeChunk>,
        limit: Int = 3,
    ): Evidence {
        val qStems = SupportJudge.stems(question).distinct()
        if (qStems.isEmpty()) return Evidence.NoEvidence
        val quantity = SupportJudge.asksQuantity(question)

        val titleMatched = HashSet<String>()
        val approximate = HashSet<String>() // matched only by a typo-tolerant guess: never better than MODERATE
        val leads = HashMap<String, KnowledgeChunk>() // sourceId -> first passage of the article
        val pool = ArrayList<KnowledgeChunk>()
        for (h in zimHits) {
            val id = h.chunks.first().sourceId
            if (h.candidate.score >= TITLE_MATCH_MIN) titleMatched += id
            if (h.candidate.kind == ZimTitleSearch.Kind.FUZZY) approximate += id
            leads[id] = h.chunks.first()
            pool += h.chunks
        }
        pool += documentChunks
        if (pool.isEmpty()) return Evidence.NoEvidence

        val ranked = (search.search(question, pool, pool.size) as? Evidence.Grounded)?.passages.orEmpty()
        // which question terms each retrieved article's title names: another article's terms are candidate SUBJECTS, not attributes
        val ownBySource = HashMap<String, Set<String>>()
        val lexicalSources = HashSet<String>() // a dictionary lemma ("anno", "superficie") is a word, not a competing subject
        for (h in zimHits) if (h.candidate.score >= TITLE_MATCH_MIN) {
            val lead = h.chunks.first()
            if (lead.lexical) lexicalSources += lead.sourceId
            // the title AND the phrase of the question the title search matched (a redirect such as "Memoria ad accesso casuale" → RAM)
            ownBySource[lead.sourceId] = SupportJudge.ownTerms(qStems, lead.sourceTitle) + SupportJudge.stems(h.candidate.phrase).filter { it in qStems }
        }
        val accepted = ArrayList<Triple<ScoredChunk, EvidenceStrength, SupportJudge.Result>>()
        var bestUnsupported: SupportJudge.Result? = null
        for (sc in ranked) {
            val isLead = sc.chunk.lead || leads[sc.chunk.sourceId] === sc.chunk
            val ownHere = ownBySource[sc.chunk.sourceId].orEmpty()
            val others = ownBySource.filterKeys { it != sc.chunk.sourceId && it !in lexicalSources }.values.flatten().filter { it !in ownHere }.toSet()
            val r = SupportJudge.judge(qStems, quantity, sc.chunk, isLead, others, ownHere)
            if (r.level == SupportJudge.Level.NONE) {
                if (sc.chunk.sourceId in titleMatched && (bestUnsupported == null || r.coverage > bestUnsupported.coverage)) bestUnsupported = r
                continue
            }
            val named = sc.chunk.sourceId in titleMatched
            val related = r.level == SupportJudge.Level.RELATED
            val strength = if (!related && named && r.aboutSubject && r.complete && sc.chunk.sourceId !in approximate) EvidenceStrength.STRONG else EvidenceStrength.MODERATE
            accepted += Triple(ScoredChunk(sc.chunk, sc.score, r.window, related), strength, r)
        }

        // relevant but not sufficient: what was consulted, and what it lacked
        fun insufficient(extraMissing: List<String> = emptyList()): Evidence {
            val consulted = LinkedHashMap<String, KnowledgeChunk>()
            for ((id, lead) in leads) if (id in titleMatched) consulted[id] = lead
            for (c in documentChunks) {
                val cov = qStems.count { it in search.terms(c.text + " " + c.section + " " + c.sourceTitle).toSet() }.toDouble() / qStems.size
                if (cov >= DOCUMENT_RELEVANCE) consulted.putIfAbsent(c.sourceId, c)
            }
            if (consulted.isEmpty()) return Evidence.NoEvidence
            val missing = (extraMissing + (bestUnsupported?.missing ?: qStems)).distinct()
            return Evidence.Insufficient(consulted.values.take(MAX_CONSULTED), missing)
        }
        if (accepted.isEmpty()) return insufficient()

        // Entity coverage: a PROPER NOUN of the question that no supporting passage covers means the question's subject is
        // missing from the sources — a generic-word article that merely shares a word with it is not evidence about it.
        val nouns = properNouns(question)
        val covered = StringBuilder()
        for (h in zimHits) if (h.candidate.score >= TITLE_MATCH_MIN) covered.append(h.candidate.phrase).append(' ')
        for ((sc, _, _) in accepted) covered.append(sc.chunk.sourceTitle).append(' ').append(sc.supportWindow).append(' ')
        val coveredSquashed = KnowledgeText.squash(covered.toString())
        val uncovered = nouns.filter { n -> KnowledgeText.squash(n).let { it.length >= 3 && it !in coveredSquashed } }
        if (nouns.isNotEmpty() && uncovered.size == nouns.size) return insufficient(uncovered.map { SupportJudge.stems(it).firstOrNull() ?: it })

        // at most two passages per article; supported-about-subject first, strongest first, newest source first on ties
        val perSource = HashMap<String, Int>()
        val chosen = accepted.sortedWith(
            compareByDescending<Triple<ScoredChunk, EvidenceStrength, SupportJudge.Result>> { it.second == EvidenceStrength.STRONG }
                .thenByDescending { it.third.aboutSubject }
                .thenByDescending { it.third.coverage }
                .thenByDescending { it.first.score }
                .thenByDescending { it.first.chunk.date },
        ).filter { (sc, _, _) -> (perSource.merge(sc.chunk.sourceId, 1, Int::plus) ?: 1) <= MAX_PER_SOURCE }.take(limit)

        val strength = if (chosen.any { it.second == EvidenceStrength.STRONG }) EvidenceStrength.STRONG else EvidenceStrength.MODERATE
        val conflict = hasConflict(chosen.map { it.first.chunk })
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
        const val MAX_CONSULTED = 3
        const val DOCUMENT_RELEVANCE = 0.34
    }
}
