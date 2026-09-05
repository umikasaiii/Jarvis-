package com.simone.jarvismobile.core.semantic

/**
 * § FASE 2A.9 §2 — the compact, non-conversational prompt for a
 * [SemanticInterpreter] implementation backed by a local model. Deliberately
 * NOT the JARVIS persona, NOT the tool catalog, NOT long memory: this is a
 * classifier/semantic-parser prompt only — the model never answers Simone
 * here, it only emits one [SemanticOutputParser] line. [previousDomainsHint]
 * is a coarse, non-personal hint (domain names only, e.g. "HEALTH", never
 * metric values/dates/content) so the model can recognize an elliptical
 * follow-up shape even though it never sees the previous turn's actual text.
 */
object SemanticInterpreterPrompt {

    fun build(userText: String, previousDomainsHint: String?): String {
        val hintLine = if (previousDomainsHint.isNullOrBlank()) {
            "Argomento del turno precedente: nessuno."
        } else {
            "Argomento del turno precedente: $previousDomainsHint."
        }
        return """
            Analizza SOLO il significato dell'ultima frase di Simone. Non rispondere a Simone, non aggiungere testo.
            Rispondi con UNA SOLA RIGA in questo formato esatto, con questi valori esatti (mai altri):
            SFV1|INTENT|DOMINI|OPERAZIONE|TEMPO|METRICA|AGGREGAZIONE|RIFERIMENTO|CONFIDENZA

            INTENT uno tra: DIRECT_COMMAND CAPABILITY_QUERY KNOWLEDGE_QUERY CONVERSATION MULTI_SOURCE_REASONING CLARIFICATION UNKNOWN
            DOMINI: uno o più tra WEATHER AGENDA HEALTH DEVICE_INFO DEVICE MEMORY ARCHIVE COMMUNICATION MEDIA DRIVING UTILITY KNOWLEDGE SYSTEM_APP separati da virgola, oppure - se la frase da sola non lo dice
            OPERAZIONE una tra: GET LIST SUMMARIZE COMPARE CONTROL SEARCH RECOMMEND OPEN CREATE UPDATE DELETE UNKNOWN (UNKNOWN se non la dice)
            TEMPO: le parole ESATTE di tempo usate da Simone (es. "domani", "la settimana prossima"), oppure -
            METRICA: la parola esatta della metrica (es. "ram", "sonno", "battito"), oppure -
            AGGREGAZIONE: "media" o "totale" se detto, oppure -
            RIFERIMENTO uno tra: NONE PARTITIVE ELLIPSIS (usa ELLIPSIS se la frase è tipo "e domani?", PARTITIVE se è tipo "quanta ne ho?", altrimenti NONE)
            CONFIDENZA: un numero tra 0 e 1

            $hintLine

            Esempi:
            Frase: "Che tempo farà domani?"
            SFV1|CAPABILITY_QUERY|WEATHER|GET|domani|-|-|NONE|0.9

            Frase: "E dopodomani?" (precedente: AGENDA)
            SFV1|CAPABILITY_QUERY|-|GET|dopodomani|-|-|ELLIPSIS|0.85

            Frase: "Domani farà caldo?" (precedente: HEALTH)
            SFV1|CAPABILITY_QUERY|WEATHER|GET|domani|-|-|NONE|0.9

            Frase: "Che differenza c'è tra RAM e VRAM?"
            SFV1|KNOWLEDGE_QUERY|KNOWLEDGE|COMPARE|-|ram,vram|-|NONE|0.85

            Frase: "Quanta ne ho nel telefono?" (precedente: KNOWLEDGE)
            SFV1|CAPABILITY_QUERY|DEVICE_INFO|GET|-|ram|-|PARTITIVE|0.7

            Frase: "Considerando come ho dormito e gli impegni di domani, a che ora dovrei andare a letto?"
            SFV1|MULTI_SOURCE_REASONING|HEALTH,AGENDA|RECOMMEND|domani|-|-|NONE|0.8

            Frase attuale: "$userText"
            Rispondi con una sola riga:
        """.trimIndent()
    }
}
