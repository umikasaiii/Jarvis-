package com.simone.jarvismobile.core.quality

import com.simone.jarvismobile.core.quality.AnswerQuality.Flag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * HOTFIX 5 — measures the VALIDATOR (not the model) on a hand-written, labelled bank of outputs.
 * TEST KIND: pure code on a SYNTHETIC output bank. The good/bad texts were written for this test; the numbers below
 * say how the rules behave on this bank, NOT how often a real Gemma output is flagged. The rules come from closed
 * grammatical classes and set similarity, not from tuning against these examples.
 */
class AnswerQualityBankTest {
    private data class Item(val label: String, val question: String, val answer: String, val earlier: List<String> = emptyList(), val earlierQ: List<String> = emptyList())

    private val prevLang = "La lingua ufficiale dell'Italia è l'italiano, parlato da quasi tutta la popolazione insieme a vari dialetti."
    private val prevSsd = "Un SSD è un'unità di memoria a stato solido che salva i dati su chip flash, senza parti meccaniche in movimento."

    private val good = listOf(
        "Qual è la capitale della Francia?" to "La capitale della Francia è Parigi.",
        "Che cos'è la fotosintesi?" to "È il processo con cui le piante usano la luce del sole per trasformare acqua e anidride carbonica in zuccheri e ossigeno.",
        "Quanto fa 12 per 12?" to "Fa 144.",
        "Chi ha scritto I promessi sposi?" to "Alessandro Manzoni.",
        "Ciao come stai?" to "Bene, grazie! E tu come va la giornata?",
        "Dimmi un piatto tipico italiano" to "Le lasagne alla bolognese: sfoglie di pasta, ragù e besciamella cotte al forno.",
        "Che differenza c'è tra RAM e memoria di archiviazione?" to "La RAM è veloce e temporanea: serve ai programmi mentre girano e si svuota allo spegnimento. L'archiviazione, come un SSD, conserva i dati anche a telefono spento.",
        "Come posso rilassarmi?" to "Prova a fare qualche respiro lento, bevi qualcosa di caldo e stacca lo schermo per dieci minuti.",
        "Spiegami cos'è un algoritmo" to "Un algoritmo è una sequenza ordinata di passi che risolve un problema: come una ricetta, ma per un computer.",
        "Che ore sono?" to "Sono le 10:42.",
        "Quanti giorni ha febbraio?" to "Febbraio ha 28 giorni, che diventano 29 negli anni bisestili.",
        "Grazie" to "Di nulla, figurati.",
        "Cos'è il Wi-Fi?" to "È una tecnologia che permette ai dispositivi di collegarsi a una rete senza cavi, usando onde radio.",
        "Fammi una lista della spesa" to "Ecco un esempio: pane, latte, uova, pomodori, pasta.",
        "Mi serve un consiglio per studiare" to "Studia a blocchi di 25 minuti con pause brevi, ripeti ad alta voce e fai qualche domanda di verifica a fine sessione.",
        "Sei sicuro?" to "Su questo non sono del tutto sicuro: è una cosa che andrebbe verificata su una fonte affidabile.",
        "Qual è il mio cibo preferito?" to "Non ho questa informazione tra i tuoi appunti.",
        "Che tempo farà domani?" to "Non posso saperlo: sono offline e non ho accesso alle previsioni.",
        "Cos'è un firmware?" to "Il firmware è il software di base di un dispositivo: gestisce l'hardware e si trova in una memoria interna.",
        "Quanto è alto il Monte Bianco?" to "Circa 4.800 metri, ma il valore esatto andrebbe verificato.",
        "Dimmi una curiosità" to "I polpi hanno tre cuori e il loro sangue è blu.",
        "Come funziona un motore a scoppio?" to "Miscela aria e carburante, la comprime e la accende con una scintilla: l'esplosione spinge un pistone che fa girare l'albero motore.",
        "Preferisci estate o inverno?" to "Non sento il caldo né il freddo, ma l'estate ha giornate più lunghe e questo è comodo.",
        "Riassumi in una frase" to "Il testo spiega come funziona la garanzia e quali sono i tempi per richiederla.",
        "E in Spagna?" to "In Spagna la lingua ufficiale è lo spagnolo, con catalano, basco e galiziano nelle rispettive regioni.",
        "Cos'è una VPN?" to "Una VPN crea un collegamento cifrato tra il tuo dispositivo e un server, nascondendo il traffico a chi sta in mezzo.",
        "Chi era Mozart?" to "Wolfgang Amadeus Mozart è stato un compositore austriaco del Settecento, autore di opere, sinfonie e musica da camera.",
        "Calcola il 15 per cento di 240" to "Il 15% di 240 è 36.",
        "Come si chiama il mio animale?" to "Nei tuoi appunti c'è Nebulix.",
        "Che giorno è oggi?" to "Oggi è giovedì 12 novembre.",
        "Dimmi di più" to "Certo: di quale argomento vuoi sapere di più?",
        "Cos'è un buco nero?" to "Una regione dello spazio dove la gravità è così forte che nemmeno la luce riesce a uscire.",
        "Mi senti?" to "Sì, ti sento bene.",
        "Che cos'è il cloud computing?" to "È l'uso di server remoti, via internet, per archiviare dati ed eseguire programmi invece di usare solo il tuo dispositivo.",
        "Elenca tre colori primari" to "Rosso, giallo e blu.",
        "Che differenza c'è tra clima e meteo?" to "Il meteo descrive il tempo di oggi o dei prossimi giorni; il clima è l'andamento medio di molti anni in una zona.",
        "Dimmi un dolce tipico" to "Il tiramisù, fatto con savoiardi, caffè, mascarpone e cacao.",
        "Che cos'è la crittografia?" to "È l'insieme di tecniche che rendono un messaggio illeggibile a chi non ha la chiave per decifrarlo.",
        "Quanto pesa un litro d'acqua?" to "Circa un chilo (1 kg).",
        "In che anno è caduto il muro di Berlino?" to "Nel 1989.",
        "Come stai?" to "Tutto a posto, grazie.",
        "Cosa mangiano i panda?" to "Quasi esclusivamente bambù, che mangiano per molte ore al giorno.",
        "Fammi un esempio di domanda" to "Per esempio: «Quanto dista la Luna dalla Terra?»",
        "Che cos'è il Bluetooth?" to "Una tecnologia wireless a corto raggio per collegare cuffie, tastiere e altri dispositivi vicini.",
        "Ok" to "Perfetto.",
    ).map { Item("good", it.first, it.second) }

    private val incomplete = listOf(
        "Che lingua si parla in Italia?" to "In Italia la lingua ufficiale è l'italiano, ma esistono anche",
        "Cos'è un SSD?" to "Un SSD è un tipo di memoria che usa chip flash invece dei dischi e",
        "Che cos'è la fotosintesi?" to "La fotosintesi è il processo con cui le piante trasformano la luce in",
        "Come posso studiare?" to "Ci sono diversi metodi utili:",
        "Come funziona un motore?" to "Il motore funziona in quattro tempi, cioè (",
        "Spiegami il Wi-Fi" to "Il Wi-Fi è una tecnologia che permette di collegare dispositivi senza cavi grazie a",
        "Dimmi un piatto" to "Un piatto tipico è la pasta al pomodoro, preparata con",
        "Cos'è un algoritmo?" to "Un algoritmo è una sequenza di istruzioni che serve a",
        "Confronta solare ed eolico" to "L'energia solare ha il vantaggio di essere silenziosa, mentre quella eolica,",
        "Dammi un consiglio" to "Potresti provare a fare una passeggiata e poi",
        "Cos'è un processore?" to "Il processore è il componente che esegue le istruzioni e coordina le",
        "Cos'è una VPN?" to "Una VPN è un servizio che ti permette di navigare in modo più",
        "Che differenza c'è?" to "La differenza principale riguarda il modo in cui",
        "Chi era Mozart?" to "Mozart è stato un compositore che ha scritto opere come «Il flauto magico",
        "Quanto è grande?" to "È molto più grande di quanto si pensi, soprattutto se si considera che",
    ).map { Item("incomplete", it.first, it.second) }

    private val echo = listOf(
        Item("echo", "Quanto è grande l'Asia?", prevLang, listOf(prevLang), listOf("Che lingua si parla in Italia?")),
        Item("echo", "Dimmi un piatto tipico italiano", prevSsd, listOf(prevLang, prevSsd), listOf("Che lingua si parla in Italia?", "Cos'è un SSD?")),
        Item("echo", "Che ore sono?", prevSsd, listOf(prevSsd), listOf("Cos'è un SSD?")),
        Item("echo", "Qual è la capitale della Spagna?", prevLang.replace("quasi tutta", "gran parte della"), listOf(prevLang), listOf("Che lingua si parla in Italia?")),
        Item("echo", "Cos'è un firmware?", prevSsd, listOf(prevLang, prevSsd), listOf("Che lingua si parla in Italia?", "Cos'è un SSD?")),
        Item("echo", "Dimmi una curiosità", prevLang, listOf(prevSsd, prevLang), listOf("Cos'è un SSD?", "Che lingua si parla in Italia?")),
    )

    private val degenerate = listOf("212121", "2121212121", "ciao ciao ciao ciao ciao ciao ciao", "000000", "....... ......",
        "il gatto dorme il gatto dorme il gatto dorme il gatto dorme").map { Item("degenerate", "domanda", it) }

    private val empty = listOf("", "   ", "\n").map { Item("empty", "domanda", it) }

    private fun flagsOf(i: Item) = AnswerQuality.evaluate(i.question, i.answer, i.earlier, i.earlierQ).flags
    private fun blocking(f: Set<Flag>) = f.any { it in setOf(Flag.EMPTY, Flag.INCOMPLETE, Flag.ECHO_OF_EARLIER_ANSWER, Flag.DEGENERATE) }

    @Test fun `validator precision and recall on the labelled synthetic bank`() {
        val expected = mapOf("incomplete" to Flag.INCOMPLETE, "echo" to Flag.ECHO_OF_EARLIER_ANSWER, "degenerate" to Flag.DEGENERATE, "empty" to Flag.EMPTY)
        val fp = good.filter { blocking(flagsOf(it)) }
        val lines = StringBuilder("# Output bank (SYNTHETIC) — validator behaviour\n\n| class | n | detected | recall |\n|---|---|---|---|\n")
        var allBadN = 0; var allBadHit = 0
        for ((label, items) in mapOf("incomplete" to incomplete, "echo" to echo, "degenerate" to degenerate, "empty" to empty)) {
            val hit = items.count { expected.getValue(label) in flagsOf(it) }
            lines.append("| $label | ${items.size} | $hit | ${"%.2f".format(hit.toDouble() / items.size)} |\n")
            allBadN += items.size; allBadHit += hit
        }
        lines.append("\nGood answers: ${good.size}; falsely flagged (blocking): ${fp.size}")
        if (fp.isNotEmpty()) lines.append(" -> " + fp.joinToString { "'" + it.answer.take(40) + "'" })
        lines.append("\nOverall recall on bad: ${"%.3f".format(allBadHit.toDouble() / allBadN)}; false-positive rate on good: ${"%.3f".format(fp.size.toDouble() / good.size)}\n")
        File("build/reports/quality").apply { mkdirs() }.resolve("answer_quality_bank.md").writeText(lines.toString())
        assertTrue(good.size >= 40 && incomplete.size >= 12 && echo.size >= 5)
        assertEquals(0, fp.size, "the validator must never block a correct answer in this bank: ${fp.map { it.answer }}")
        assertTrue(allBadHit.toDouble() / allBadN >= 0.90, "recall on the synthetic bad bank was ${allBadHit.toDouble() / allBadN}")
    }
}
