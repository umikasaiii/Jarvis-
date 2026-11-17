package com.simone.jarvismobile.core.reliability

import com.simone.jarvismobile.core.knowledge.KnowledgeText

/**
 * RELIABILITY — what KIND of answer is being published, and what a general-knowledge answer must say about itself.
 *
 * The five classes are ORTHOGONAL to model quality: they say what stands behind the text, so the reader never has to guess.
 *  - SOURCE_SUPPORTED: every claim was checked against retrieved passages (citations rebuilt by the pipeline);
 *  - PERSONAL_MEMORY: it restates what the user saved (the Memoria record is the source);
 *  - DETERMINISTIC_TOOL: the result of a tool the system ran (calculation, clock, …);
 *  - GENERAL_UNVERIFIED: the model's own knowledge, labelled as not verified, with no citations;
 *  - ABSTENTION: a declared limit, no claim.
 * The label "non verificato" is NOT a safety guarantee: it tells the reader where the text comes from, it does not make a wrong sentence right. For
 * high-risk subjects (health, drugs, law, money, safety) the limit is therefore stated explicitly and always, not only when the answer contains a number or a name.
 */
object GeneralAnswerPolicy {
    enum class AnswerClass { SOURCE_SUPPORTED, PERSONAL_MEMORY, DETERMINISTIC_TOOL, GENERAL_UNVERIFIED, ABSTENTION }

    /** [mode] is the publication mode when an evidence package was verified, null for an unverified answer; [kinds] are the evidence kinds of the package. */
    fun classify(mode: PublicationMode?, kinds: Set<EvidenceKind>): AnswerClass = when {
        mode == PublicationMode.ABSTAIN -> AnswerClass.ABSTENTION
        mode == null -> AnswerClass.GENERAL_UNVERIFIED
        EvidenceKind.TOOL in kinds && kinds.none { it == EvidenceKind.KNOWLEDGE || it == EvidenceKind.DOCUMENT || it == EvidenceKind.MEMORY } -> AnswerClass.DETERMINISTIC_TOOL
        kinds.isNotEmpty() && kinds.all { it == EvidenceKind.MEMORY } -> AnswerClass.PERSONAL_MEMORY
        else -> AnswerClass.SOURCE_SUPPORTED
    }

    private val GENERAL_ONLY = Regex(
        """\b(?:senza\s+(?:usare|consultare|guardare|cercare)\s+(?:gli\s+|le\s+|i\s+)?(?:archiv\w*|font\w*|document\w*|wikipedia|zim)|(?:usando|con|dalle|dalla)\s+(?:le\s+tue\s+|la\s+tua\s+)?(?:conoscenz\w*\s+general\w*|conoscenza\s+general\w*)|dalla\s+tua\s+(?:memoria|conoscenza)\s+(?:interna|general\w*)|non\s+(?:usare|consultare|cercare)\s+(?:gli\s+)?archiv\w*)\b""",
    )

    /** The user explicitly asks for the model's own knowledge instead of the offline sources: retrieval must not run (it would only inject noise). */
    fun requestsGeneralOnly(question: String): Boolean = GENERAL_ONLY.containsMatchIn(KnowledgeText.fold(question))

    /**
     * Subjects where a confident wrong sentence can hurt. A CLOSED lexicon of domain words (medicine, drugs, law, money, safety) — it labels, it never answers,
     * and it is not exhaustive: an unlisted subject simply gets the ordinary label.
     */
    private val HIGH_RISK = Regex(
        """\b(?:medic\w*|farmac\w*|medicin\w*|terapi\w*|sintom\w*|malatt\w*|patolog\w*|diagnos\w*|dosag\w*|dose\b|dosi\b|ormon\w*|insulin\w*|diabet\w*|tumor\w*|cancro|infezion\w*|antibiotic\w*|vaccin\w*|allerg\w*|pression\w*\s+arteriosa|cardiac\w*|cuore|polmon\w*|fegato|pancreas|rene|reni|chirurg\w*|gravidanz\w*|veleno\w*|avvelen\w*|overdose|legge\b|leggi\b|legale|tribunale|tasse|fiscal\w*|imposta|mutuo|investiment\w*|prestito|contratto|sicurezza\s+elettric\w*|corrente\s+elettric\w*|scossa)\b""",
    )

    fun isHighRisk(question: String): Boolean = HIGH_RISK.containsMatchIn(KnowledgeText.fold(question))

    const val USER_REQUESTED_GENERAL = "Come richiesto, questa risposta viene dalla conoscenza generale del modello: non è verificata nelle fonti locali."
    const val HIGH_RISK_NOTE = "Non è una fonte verificata e il modello può sbagliare: per salute, farmaci, leggi o denaro controllala su una fonte affidabile o con un professionista."
}
