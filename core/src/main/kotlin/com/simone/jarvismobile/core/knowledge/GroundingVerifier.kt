package com.simone.jarvismobile.core.knowledge

import com.simone.jarvismobile.core.quality.AnswerQuality

/**
 * KNOWLEDGE ENGINE 2.0 — decides, AFTER generation, how far the final answer is supported by the retrieved passages. Plain
 * code over text; the model that wrote the answer is never asked to vouch for it (a second pass of the same model is not
 * independent verification).
 *
 * What it can and cannot establish, stated precisely:
 *  - [GroundingState.CLAIM_SUPPORTED] means every checkable value of the answer (each number, each named thing the question
 *    did not already contain) occurs in a SUPPORTING window — a window in which the question's terms co-occur — or, for an
 *    answer with no such values, that most of its content words occur in the supporting windows. It is LEXICAL support: it
 *    does not prove that the sentence logically entails the answer (negations, comparisons and paraphrases with different
 *    words are not understood).
 *  - A passage that is merely [ScoredChunk.related] can never make an answer CLAIM_SUPPORTED.
 *  - Anything it cannot confirm is reported as such, never rounded up.
 */
object GroundingVerifier {
    /** [supporting] are the passages that may be cited as support (empty unless CLAIM_SUPPORTED); [unconfirmed] the answer values no window contains. */
    data class Verdict(val state: GroundingState, val supporting: List<ScoredChunk>, val unconfirmed: List<String>)

    private const val OVERLAP_MIN_TERMS = 4
    private const val OVERLAP_SUPPORTED = 0.6

    fun verify(question: String, answer: String, evidence: Evidence?): Verdict = when (evidence) {
        null, Evidence.NoEvidence -> Verdict(GroundingState.NO_EVIDENCE, emptyList(), emptyList())
        is Evidence.Insufficient -> Verdict(GroundingState.INSUFFICIENT_EVIDENCE, emptyList(), emptyList())
        is Evidence.Grounded -> verifyGrounded(question, answer, evidence)
    }

    private fun verifyGrounded(question: String, answer: String, ev: Evidence.Grounded): Verdict {
        val proof = ev.passages.filter { !it.related && it.supportWindow.isNotBlank() }
        if (proof.isEmpty()) return Verdict(GroundingState.SOURCE_FOUND, emptyList(), emptyList())
        val clean = AnswerQuality.stripPresentationNotes(answer)
        val windows = proof.map { it to KnowledgeText.fold(it.supportWindow) }
        val windowDigits = proof.flatMap { AnswerQuality.significantNumbers(it.supportWindow) }.map { n -> n.filter(Char::isDigit) }.toSet()
        val windowWords = windows.joinToString(" ") { it.second }
        val askedDigits = AnswerQuality.significantNumbers(question).map { n -> n.filter(Char::isDigit) }.toSet()
        val askedWords = SupportJudge.stems(question).toSet()

        val numbers = AnswerQuality.significantNumbers(clean).filter { n -> n.filter(Char::isDigit) !in askedDigits }
        val badNumbers = numbers.filter { n -> n.filter(Char::isDigit) !in windowDigits }
        val entities = AnswerQuality.namedEntities(clean).filter { e -> SupportJudge.stems(e).firstOrNull()?.let { it !in askedWords } ?: false }
        val badEntities = entities.filter { e -> KnowledgeText.fold(e) !in windowWordSet(windowWords) }
        val unconfirmed = badNumbers + badEntities
        val checkable = numbers.size + entities.size

        if (unconfirmed.isNotEmpty()) return Verdict(GroundingState.SOURCE_FOUND, emptyList(), unconfirmed)
        if (checkable > 0) {
            // cite only the passages whose window actually holds one of the answer's values
            val cited = proof.filter { p ->
                val w = KnowledgeText.fold(p.supportWindow); val d = AnswerQuality.significantNumbers(p.supportWindow).map { n -> n.filter(Char::isDigit) }.toSet()
                numbers.any { n -> n.filter(Char::isDigit) in d } || entities.any { e -> KnowledgeText.fold(e) in windowWordSet(w) }
            }
            return Verdict(GroundingState.CLAIM_SUPPORTED, cited.ifEmpty { proof }, emptyList())
        }
        // no number or name to check: lexical overlap of the answer's content words with the supporting windows
        val ansStems = SupportJudge.stems(clean).filter { it !in askedWords }.distinct()
        if (ansStems.size < OVERLAP_MIN_TERMS) return Verdict(GroundingState.SOURCE_FOUND, emptyList(), emptyList())
        val pool = SupportJudge.stems(proof.joinToString(" ") { it.supportWindow }).toSet()
        val share = ansStems.count { it in pool }.toDouble() / ansStems.size
        return if (share >= OVERLAP_SUPPORTED) Verdict(GroundingState.CLAIM_SUPPORTED, proof, emptyList())
        else Verdict(GroundingState.SOURCE_FOUND, emptyList(), emptyList())
    }

    private fun windowWordSet(folded: String): Set<String> = KnowledgeSearch.NON_WORD.split(folded).filter { it.isNotEmpty() }.toSet()
}
