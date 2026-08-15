package com.simone.jarvismobile.core.intent

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Measures the structured parser against a labelled corpus instead of asserting
 * one phrase at a time, so a change that fixes one wording and quietly breaks
 * five others shows up as a number going down.
 *
 * The corpus deliberately mixes the shapes people really use — aliases, word
 * order, colloquial imperatives — with **negatives**: sentences that merely look
 * like planner commands. Those are what protect the user, and they are held to a
 * stricter standard than the positives: a missed command is an annoyance, a
 * false delete is lost data.
 */
class IntentMetricsTest {

    private val now = LocalDateTime.of(2026, 8, 6, 10, 0) // Thursday

    /** One labelled example. A null [action] means "must not be a command". */
    private data class Sample(val text: String, val action: Action?)

    private fun pos(action: Action, vararg texts: String) = texts.map { Sample(it, action) }
    private fun neg(vararg texts: String) = texts.map { Sample(it, null) }

    private val corpus: List<Sample> = buildList {
        addAll(
            pos(
                Action.DELETE,
                "elimina l'appuntamento dal dentista",
                "cancella l'appuntamento dal dentista",
                "rimuovi l'appuntamento dal dentista",
                "togli l'appuntamento dal dentista",
                "disdici l'appuntamento dal dentista",
                "annulla l'appuntamento dal dentista",
                "cancella dentista di domani",
                "elimina la riunione di lunedì",
                "togli l'impegno delle 18",
                "rimuovi l'evento di venerdì",
                "domani dentista, toglilo",
                "quello del dentista cancellalo",
                "non voglio più l'appuntamento dal dentista",
                "puoi cancellare l'appuntamento dal dentista?",
                "JARVIS, elimina l'appuntamento dal dentista per favore",
                "Cancella L'Appuntamento Dal Dentista!",
            ),
        )
        addAll(
            pos(
                Action.MOVE,
                "sposta il dentista a venerdì",
                "rimanda il dentista a venerdì",
                "posticipa il dentista a venerdì",
                "riprogramma il dentista a venerdì",
                "porta il dentista a venerdì",
                "sposta la riunione alle 17",
                "rimanda la riunione alle 17",
                "anticipa il dentista alle 15",
                "porta l'appuntamento di domani a lunedì",
                "sposta l'evento di Marco di due ore",
                "anticipalo di mezz'ora",
                "rimanda la riunione di un giorno",
                "spostalo alle 16",
                "quello di domani spostalo alle 16",
                "cambia l'orario alle 16",
                "puoi spostare il dentista a venerdì?",
            ),
        )
        addAll(
            pos(
                Action.UPDATE,
                "modifica dentista in visita dentistica",
                "cambia dentista in visita dentistica",
                "rinomina l'appuntamento dal dentista in visita annuale",
                "cambialo in visita annuale",
                "correggi l'appuntamento dentista in visita",
            ),
        )
        // Negatives: none of these may become a planner write.
        addAll(
            neg(
                "che impegni ho domani?",
                "cosa devo fare oggi?",
                "come stai?",
                "che ore sono?",
                "aggiungi appuntamento domani alle 18 dal dentista",
                "ricordami di comprare il pane domani",
                "porta il pane a casa",
                "cambia la lingua",
                "cambia la voce di jarvis",
                "accendi la torcia",
                "metti in pausa la musica",
                "quanto manca alle 16?",
                "elimina",
                "cancella",
                "sposta",
                "ciao",
                "grazie mille",
                "raccontami una barzelletta",
                "quanta batteria ho?",
                "apri spotify",
            ),
        )
    }

    private fun predict(text: String): Action? =
        AgendaCommandParser.parse(text, now)
            ?.takeIf { it.domain == Domain.AGENDA }
            ?.action

    @Test
    fun corpusMetricsMeetTheirTargets() {
        var truePositive = 0
        var falsePositive = 0
        var falseNegative = 0
        var wrongAction = 0
        val confusion = linkedMapOf<String, MutableList<String>>()

        for (sample in corpus) {
            val predicted = predict(sample.text)
            when {
                sample.action == null && predicted == null -> Unit
                sample.action == null && predicted != null -> {
                    falsePositive++
                    confusion.getOrPut("null→$predicted") { mutableListOf() }.add(sample.text)
                }
                sample.action != null && predicted == null -> {
                    falseNegative++
                    confusion.getOrPut("${sample.action}→null") { mutableListOf() }.add(sample.text)
                }
                sample.action == predicted -> truePositive++
                else -> {
                    wrongAction++
                    confusion.getOrPut("${sample.action}→$predicted") { mutableListOf() }.add(sample.text)
                }
            }
        }

        val predictedPositives = truePositive + falsePositive + wrongAction
        val actualPositives = corpus.count { it.action != null }
        val precision = if (predictedPositives == 0) 1.0 else truePositive.toDouble() / predictedPositives
        val recall = if (actualPositives == 0) 1.0 else truePositive.toDouble() / actualPositives

        println("=== IntentRouter metrics over ${corpus.size} samples ===")
        println("true positives : $truePositive")
        println("false positives: $falsePositive   (a command invented from a non-command)")
        println("false negatives: $falseNegative   (a real command missed)")
        println("wrong action   : $wrongAction")
        println("precision      : ${"%.3f".format(precision)}")
        println("recall         : ${"%.3f".format(recall)}")
        if (confusion.isNotEmpty()) {
            println("--- confusion ---")
            confusion.forEach { (k, v) -> println("$k : ${v.size} → ${v.take(3)}") }
        }

        // A non-command must never become a planner write. This is the one that
        // protects real data, so it is exact rather than a threshold.
        assertEquals(0, falsePositive, "false positives: ${confusion.filterKeys { it.startsWith("null→") }}")
        // Mixing up delete with move would act on the right item in the wrong way.
        assertEquals(0, wrongAction, "wrong actions: $confusion")
        assertTrue(precision >= 0.95, "precision too low: $precision")
        assertTrue(recall >= 0.90, "recall too low: $recall ($falseNegative missed)")
    }

    @Test
    fun destructiveIntentsNeverSelfApproveWithoutANamedTarget() {
        // Every phrase that parses as a DELETE must either name what to delete or
        // refuse to act on its own. Nothing may be deleted on a guess.
        val deletes = corpus.filter { it.action == Action.DELETE }
            .mapNotNull { AgendaCommandParser.parse(it.text, now) }
        assertTrue(deletes.isNotEmpty())
        for (intent in deletes) {
            if (intent.target.isEmpty) {
                assertTrue(!intent.mayAct(), "would act with no target: ${intent.rawText}")
            }
        }
    }

    @Test
    fun affirmativeAndNegativeVocabulariesDoNotOverlap() {
        // A reply that counted as both would make a confirmation non-deterministic.
        val both = (IntentAliases.AFFIRMATIVE + IntentAliases.NEGATIVE)
            .filter { IntentAliases.isAffirmative(it) && IntentAliases.isNegative(it) }
        assertEquals(emptyList(), both, "ambiguous confirmation words: $both")
    }

    @Test
    fun confirmationRepliesAreClassifiedAsExpected() {
        for (yes in listOf("sì", "si", "ok", "va bene", "certo", "procedi", "confermo", "fallo", "d'accordo")) {
            assertTrue(IntentAliases.isAffirmative(yes), "not affirmative: $yes")
            assertTrue(!IntentAliases.isNegative(yes), "also negative: $yes")
        }
        for (no in listOf("no", "annulla", "lascia stare", "non farlo", "ferma", "niente")) {
            assertTrue(IntentAliases.isNegative(no), "not negative: $no")
            assertTrue(!IntentAliases.isAffirmative(no), "also affirmative: $no")
        }
    }

    @Test
    fun bareCancelDuringAConfirmationMeansAbortNotDelete() {
        // The collision the brief calls out: alone it aborts the pending action…
        for (abort in listOf("cancella", "annulla", "cancella operazione", "lascia stare")) {
            assertTrue(IntentAliases.isCancellationOfPendingAction(abort), "not an abort: $abort")
        }
        // …but with an object it is a real delete request and must fall through.
        for (real in listOf("cancella la nota sulla moto", "elimina l'appuntamento dal dentista")) {
            assertTrue(
                !IntentAliases.isCancellationOfPendingAction(real),
                "swallowed a real delete: $real",
            )
        }
    }
}
