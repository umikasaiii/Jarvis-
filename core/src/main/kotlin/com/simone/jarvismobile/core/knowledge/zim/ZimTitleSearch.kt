package com.simone.jarvismobile.core.knowledge.zim

import com.simone.jarvismobile.core.knowledge.KnowledgeText

/**
 * KNOWLEDGE ENGINE — finds the ARTICLES a question is about, using only the ZIM title index (no Xapian). General by
 * construction: it never looks at what is being asked, only at which word sequences of the question are the title of an
 * article, with accent/case folding, prefix matching for multi-word names and bounded typo tolerance (edit distance on
 * the leading word). A question that names nothing the archive has simply yields no candidates.
 */
class ZimTitleSearch(
    private val perPhraseLimit: Int = 6,
    private val fuzzyPoolPerProbe: Int = 60,
) {
    enum class Kind { EXACT, PREFIX, FUZZY }

    data class Candidate(val entry: ZimEntry, val phrase: String, val score: Double, val kind: Kind)

    fun search(zim: ZimArchive, question: String, limit: Int = 4): List<Candidate> {
        val ns = zim.articleNamespace
        val all = KnowledgeText.words(question)
        if (all.isEmpty()) return emptyList()
        val best = LinkedHashMap<String, Candidate>() // resolved path -> best candidate

        fun offer(entry: ZimEntry, phrase: String, score: Double, kind: Kind) {
            val target = zim.resolve(entry) ?: return
            if (target.mimeType?.startsWith("text/html") != true) return
            val key = target.path
            val prev = best[key]
            if (prev == null || prev.score < score) best[key] = Candidate(target, phrase, score, kind)
        }

        // 1) every contiguous word sequence (1..5) that neither starts nor ends with a function word
        val phrases = LinkedHashSet<List<KnowledgeText.Word>>()
        for (n in 5 downTo 1) for (i in 0..all.size - n) {
            val seq = all.subList(i, i + n)
            if (KnowledgeText.isStopword(seq.first().folded) || KnowledgeText.isStopword(seq.last().folded)) continue
            phrases += seq
        }
        for (seq in phrases) {
            val text = seq.joinToString(" ") { it.original }
            val squashedPhrase = KnowledgeText.squash(text)
            val words = seq.size
            val specificity = 0.85 + 0.15 * minOf(words, 3) / 3.0
            // The title index is sorted case-SENSITIVELY and Wikipedia capitalises titles unpredictably ("Muro di Berlino"),
            // so probe by the leading 1 / 2 / all words in a few case shapes and compare the FOLDED text locally.
            val probes = LinkedHashSet<String>()
            for (k in listOf(1, 2, words).distinct().filter { it <= words }) {
                probes += caseVariants(seq.take(k).joinToString(" ") { it.original })
            }
            // Modern archives list only real articles in the title index, so a REDIRECT title ("USA", "Berlin") is invisible to a
            // prefix scan. The URL index does hold it: probe the exact path (underscored) in a few case shapes, bounded.
            if (zim.titleIndexMode == ZimTitleIndex.LISTING_V1 && words <= 4) {
                for (v in caseVariants(text).take(3)) {
                    val hit = runCatching { zim.findByPath(ns, v.replace(' ', '_')) }.getOrNull() ?: continue
                    if (hit.isRedirect) offer(hit, text, 0.95 * specificity, Kind.EXACT)
                }
            }
            val seenPaths = HashSet<String>()
            for (probe in probes) {
                val limit = if (probe.contains(' ') && probe.split(' ').size >= words) perPhraseLimit else PROBE_POOL
                for (e in zim.titlesWithPrefix(ns, probe, limit)) {
                    if (!seenPaths.add(e.path)) continue
                    val bare = stripDisambiguation(e.displayTitle)
                    val squashedTitle = KnowledgeText.squash(bare)
                    when {
                        squashedTitle == squashedPhrase -> offer(e, text, 1.0 * specificity, Kind.EXACT)
                        // a multi-word name may be the start of a longer title ("Stati Uniti" -> "Stati Uniti d'America"); a single word
                        // may NOT ("Albert" is not evidence about "Albert Einstein" because an "Albert ..." article exists)
                        words >= 2 && squashedTitle.startsWith(squashedPhrase) ->
                            offer(e, text, 0.7 * specificity * squashedPhrase.length / squashedTitle.length.coerceAtLeast(1), Kind.PREFIX)
                    }
                }
            }
        }

        // 2) typo tolerance: a name (1-3 words, >= 5 letters) that matched nothing exactly is probed by the first letters
        //    of its first word and compared with the whole candidate title by edit distance.
        val matched = best.values.map { KnowledgeText.fold(it.phrase) }.toSet()
        val fuzzyPhrases = phrases.filter { it.size <= 3 }.map { seq -> seq to seq.joinToString(" ") { it.folded } }
            .filter { (_, folded) -> folded.length >= 6 && folded !in matched && matched.none { m -> folded in m || m in folded } }
        val seen = HashSet<String>()
        for ((seq, foldedPhrase) in fuzzyPhrases) {
            // A short word one letter away from a different word is a different word ("Mount"/"Mound"): no fuzzy below
            // 6 letters, one edit up to 8 letters, two beyond.
            val maxDist = if (foldedPhrase.length >= 9) 2 else 1
            for (probe in listOf(3, 4, 5, 6)) {
                val head = seq.first().original.take(probe)
                for (variant in caseVariants(head)) {
                    for (e in zim.titlesWithPrefix(ns, variant, fuzzyPoolPerProbe)) {
                        if (!seen.add(foldedPhrase + "|" + e.path)) continue
                        val title = KnowledgeText.fold(stripDisambiguation(e.displayTitle))
                        var dist = KnowledgeText.editDistance(foldedPhrase, title, maxDist)
                        if (seq.size == 1) dist = minOf(dist, KnowledgeText.editDistance(foldedPhrase, title.substringBefore(' '), maxDist))
                        if (dist in 1..maxDist) offer(e, seq.joinToString(" ") { it.original }, 0.6 * (1.0 - dist.toDouble() / foldedPhrase.length), Kind.FUZZY)
                    }
                }
            }
        }
        // A name the question spells as one capitalised run ("Albert Einstein", "Mount Everest") is not satisfied by an article
        // that matches only part of it ("Albert", "Mount"): reject partial-run matches unless the whole run matched too.
        val runs = capitalisedRuns(question)
        val kept = best.values.filter { c ->
            val cw = KnowledgeText.words(c.phrase).map { it.folded }
            val containing = runs.filter { run -> run.size > cw.size && run.windowed(cw.size).any { it == cw } }
            containing.isEmpty() || best.values.any { o -> KnowledgeText.words(o.phrase).map { it.folded }.let { ow -> containing.any { run -> ow == run } } }
        }
        return kept.sortedByDescending { it.score }.take(limit)
    }

    private fun caseVariants(text: String): List<String> {
        val lower = text.lowercase()
        val first = lower.replaceFirstChar { it.uppercase() }
        val title = lower.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
        return listOf(text, first, title, lower, text.uppercase()).distinct()
    }

    private companion object {
        /** Titles examined per probe prefix; the exact-title check is local, so a generous pool is cheap. */
        const val PROBE_POOL = 60
    }

    /** Maximal runs of consecutive capitalised, non-sentence-initial words (folded). */
    private fun capitalisedRuns(question: String): List<List<String>> {
        val runs = ArrayList<List<String>>()
        var cur = ArrayList<String>()
        val ws = Regex("""\p{L}[\p{L}'’-]*""").findAll(question).toList()
        for ((i, m) in ws.withIndex()) {
            var j = m.range.first - 1
            while (j >= 0 && question[j] == ' ') j--
            val initial = i == 0 || j < 0 || question[j] in ".!?:;"
            if (m.value[0].isUpperCase() && !initial && !KnowledgeText.isStopword(KnowledgeText.fold(m.value))) {
                cur.add(KnowledgeText.fold(m.value))
            } else {
                if (cur.size >= 2) runs += cur
                cur = ArrayList()
            }
        }
        if (cur.size >= 2) runs += cur
        return runs
    }

    private fun stripDisambiguation(t: String): String = t.replace(Regex("""\s*\([^)]*\)\s*$"""), "")
}
