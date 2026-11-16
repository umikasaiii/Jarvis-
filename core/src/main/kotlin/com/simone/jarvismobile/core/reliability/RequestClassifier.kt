package com.simone.jarvismobile.core.reliability

import com.simone.jarvismobile.core.knowledge.KnowledgeText

/** What kind of request this is, so evidence rules apply only where they make sense. Lexical routing, not a knowledge table. */
enum class RequestKind {
    /** Greetings, thanks, opinions, small talk: no evidence expected, nothing to verify. */
    CONVERSATION,
    /** Rewrite/translate/correct/summarise text the user supplied. */
    TRANSFORM,
    /** Arithmetic or a unit conversion the system can compute. */
    CALCULATION,
    /** "Se…", "immagina…": reasoning over a supposition, not a claim about the world. */
    HYPOTHETICAL,
    /** A question about facts: evidence is expected. */
    FACTUAL,
}

object RequestClassifier {
    private val TRANSFORM = Regex("^(?:traduci|riscrivi|correggi|parafrasa|riformula|sistema il testo|rendi piu|scrivi (?:una|un|la|il) (?:mail|email|lettera|messaggio|poesia|storia))\\b")
    private val HYPO = Regex("^(?:se |e se |immagina|ipotizza|supponi|facciamo finta|poniamo)|\\bse (?:fossi|fosse|avessi|avesse|potessi)\\b")
    private val CHAT = Regex("^(?:ciao|buongiorno|buonasera|buonanotte|grazie|ok\\b|va bene|come stai|come va|chi sei|cosa ne pensi|che ne pensi|secondo te|dimmi una barzelletta|raccontami una barzelletta|mi sento|ho bisogno di|puoi aiutarmi|aiutami)")
    private val CALC = Regex("\\d\\s*[-+*/x×÷:^]\\s*\\d|\\b(?:quanto fa|calcola|converti|a quanti .* corrispond\\w+)\\b")
    private val FACT = Regex("\\b(?:qual e|quali sono|qual era|chi e|chi era|chi ha|quando|dove|quanto|quanti|quante|quanta|come si chiama|cos e|che cos e|che cosa e|cosa significa|cosa vuol dire|in che anno|di che|a cosa serve|perche|come funziona|definisci|spiegami|dimmi (?:cos|chi|quando|dove|quanto|qual)|parlami di|parlami del|raccontami (?:di|del|della|dell)|descrivi|elenca|cosa sai|che sai di|informazioni su|dimmi di|dimmi del|nome (?:di|del|della)|significato di|storia (?:di|del|della)|differenza tra|confronta)\\b|\\?$")

    /** A capitalised word that is not the first, or a digit: a request that names a specific thing is not small talk. */
    private val SPECIFIC = Regex("(?<!^)(?<![.!?]\\s)\\b\\p{Lu}\\p{L}{2,}|\\d")

    fun classify(question: String): RequestKind {
        val q = KnowledgeText.fold(question).trim().replace(Regex("[’']"), " ").replace(Regex("\\s+"), " ")
        return when {
            HYPO.containsMatchIn(q) -> RequestKind.HYPOTHETICAL
            TRANSFORM.containsMatchIn(q) -> RequestKind.TRANSFORM
            CHAT.containsMatchIn(q) -> RequestKind.CONVERSATION
            CALC.containsMatchIn(q) -> RequestKind.CALCULATION
            FACT.containsMatchIn(q) -> RequestKind.FACTUAL
            // no recognised formula, but it names a specific thing: treat as factual rather than silently as small talk
            SPECIFIC.containsMatchIn(question.trim()) -> RequestKind.FACTUAL
            else -> RequestKind.CONVERSATION
        }
    }

    /** Only factual questions need evidence; everything else keeps the model's own answer, without citations or verification claims. */
    fun needsEvidence(kind: RequestKind) = kind == RequestKind.FACTUAL
}
