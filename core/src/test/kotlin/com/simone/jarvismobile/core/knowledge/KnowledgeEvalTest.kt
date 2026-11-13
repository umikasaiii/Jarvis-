package com.simone.jarvismobile.core.knowledge

import com.simone.jarvismobile.core.quality.AnswerQuality
import com.simone.jarvismobile.core.quality.AnswerQuality.Provenance
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * KNOWLEDGE ENGINE — retrieval and grounding evaluation over SIMULATED sources ([KnowledgeFixtures]).
 *
 * TEST KIND: pure code + SIMULATED fixture archives built from the public ZIM spec. It measures whether the engine finds
 * the right article, supports the fact, and — just as important — returns NO evidence when the sources do not contain
 * the answer. It does NOT measure any language model, and a fixture-corpus score says nothing about real Wikipedia
 * coverage. 107 questions; the `holdout` ones were not looked at while thresholds/heuristics were written.
 */
class KnowledgeEvalTest {
    private data class Q(
        val id: Int, val category: String, val text: String,
        val titles: Set<String> = emptySet(),   // all must appear among the evidence sources (empty = unanswerable here)
        val fact: String? = null,               // folded substring that must appear in the evidence
        val holdout: Boolean = false,
        val old: Boolean = false,               // also consult the old archive
        val expectConflict: Boolean = false,
    )

    private var n = 0
    private fun q(cat: String, text: String, vararg titles: String, fact: String? = null, h: Boolean = false, old: Boolean = false, conflict: Boolean = false) =
        Q(++n, cat, text, titles.toSet(), fact?.let(KnowledgeText::fold), h, old, conflict)

    private val questions: List<Q> = buildList {
        val geo = "capitali_geografia"
        add(q(geo, "Qual è la capitale della Francia?", "Francia", fact = "Parigi"))
        add(q(geo, "Mi dici la capitale della Spagna?", "Spagna", fact = "Madrid", h = true))
        add(q(geo, "Capitale del Portogallo?", "Portogallo", fact = "Lisbona"))
        add(q(geo, "Che lingua si parla in Germania?", "Germania", fact = "tedesco"))
        add(q(geo, "In quale città ha sede il governo del Regno Unito, qual è la capitale?", "Regno Unito", fact = "Londra", h = true))
        add(q(geo, "Qual è la capitale del Giappone", "Giappone", fact = "Tokyo"))
        add(q(geo, "Capitale dell'Egitto", "Egitto", fact = "Cairo"))
        add(q(geo, "Qual è la capitale del Brasile?", "Brasile", fact = "Brasilia", h = true))
        add(q(geo, "Qual e la capitale del Canada", "Canada", fact = "Ottawa"))
        add(q(geo, "Qual è la capitale dell'Australia?", "Australia", fact = "Canberra", h = true))
        add(q(geo, "Che lingua si parla in Italia?", "Italia", fact = "italiano"))
        add(q(geo, "Quanto è lungo il Nilo?", "Nilo", fact = "6.650", h = true))
        add(q(geo, "Quanto è alto il Monte Bianco?", "Monte Bianco", fact = "4.806"))
        add(q(geo, "Quanto è grande l'Asia?", "Asia", fact = "44,58"))
        add(q(geo, "Quanto è lungo il Po?", "Po", fact = "652", h = true))
        add(q(geo, "Quanto è esteso il Sahara?", "Sahara", fact = "9,2"))
        add(q(geo, "Quanto è alto il Vesuvio?", "Vesuvio", fact = "1.281", h = true))
        add(q(geo, "Dimmi la superficie del Giappone", "Giappone", fact = "377.975"))
        add(q(geo, "Che lingua si parla in Brasile?", "Brasile", fact = "portoghese", h = true))
        add(q(geo, "Quanto è grande l'oceano Pacifico?", "Oceano Pacifico", fact = "165"))
        add(q(geo, "Dove si trova il Monte Bianco?", "Monte Bianco", fact = "Alpi", h = true))
        add(q(geo, "Che lingue si parlano in Canada?", "Canada", fact = "francese", h = true))

        val sto = "storia_scienze"
        add(q(sto, "In che anno è caduto il muro di Berlino?", "Muro di Berlino", fact = "1989"))
        add(q(sto, "Quando è iniziata la Rivoluzione francese?", "Rivoluzione francese", fact = "1789"))
        add(q(sto, "Quando è finita la prima guerra mondiale?", "Prima guerra mondiale", fact = "1918", h = true))
        add(q(sto, "Quando è iniziata la seconda guerra mondiale?", "Seconda guerra mondiale", fact = "1939"))
        add(q(sto, "In che anno è stata proclamata l'Unità d'Italia?", "Unità d'Italia", fact = "1861", h = true))
        add(q(sto, "Quando visse Leonardo da Vinci?", "Leonardo da Vinci", fact = "1452", h = true))
        add(q(sto, "Cos'è la fotosintesi?", "Fotosintesi", fact = "luce"))
        add(q(sto, "Spiegami la fotosintesi", "Fotosintesi", fact = "ossigeno", h = true))
        add(q(sto, "Cos'è la gravità?", "Gravità", fact = "9,81"))
        add(q(sto, "Che cos'è il DNA?", "DNA", fact = "doppia elica", h = true))
        add(q(sto, "Quanti pianeti ha il sistema solare?", "Sistema solare", fact = "otto"))
        add(q(sto, "Quanto dista la Luna dalla Terra?", "Luna", fact = "384.400", h = true))
        add(q(sto, "Qual è la formula dell'acqua?", "Acqua", fact = "H2O"))
        add(q(sto, "A che temperatura bolle l'acqua?", "Acqua", fact = "100", h = true))
        add(q(sto, "Cos'è un vulcano?", "Vulcano", fact = "magma"))
        add(q(sto, "Chi era Leonardo da Vinci?", "Leonardo da Vinci", fact = "Gioconda", h = true))
        add(q(sto, "Cosa è successo nel 79 d.C. al Vesuvio?", "Vesuvio", fact = "Pompei"))
        add(q(sto, "Cos'è l'Impero romano?", "Impero romano", fact = "Augusto", h = true))
        add(q(sto, "Quando cadde l'Impero romano d'Occidente?", "Impero romano", fact = "476"))
        add(q(sto, "Dove nacque Cristoforo Colombo?", "Cristoforo Colombo", fact = "Genova", h = true))
        add(q(sto, "Cos'è una catena montuosa come le Alpi?", "Alpi", fact = "Europa"))
        add(q(sto, "Quanto è alto il Monte Bianco rispetto alle Alpi?", "Alpi", "Monte Bianco", fact = "4.806", h = true))

        val tec = "definizioni_tecniche"
        add(q(tec, "Cos'è un SSD?", "SSD", fact = "flash"))
        add(q(tec, "Che cos'è la RAM?", "RAM", fact = "volatile", h = true))
        add(q(tec, "Cos'è una CPU?", "CPU", fact = "istruzioni"))
        add(q(tec, "Cos'è il Wi-Fi?", "Wi-Fi", fact = "802.11", h = true))
        add(q(tec, "Che cos'è una VPN?", "VPN", fact = "cifrato"))
        add(q(tec, "Cos'è il Bluetooth?", "Bluetooth", fact = "2,4", h = true))
        add(q(tec, "Cos'è un algoritmo?", "Algoritmo", fact = "passi"))
        add(q(tec, "Che cos'è la crittografia?", "Crittografia", fact = "chiave", h = true))
        add(q(tec, "Cos'è un firmware?", "Firmware", fact = "hardware"))
        add(q(tec, "Che cos'è il cloud computing?", "Cloud computing", fact = "remoti", h = true))

        val typ = "refusi_ortografici"
        add(q(typ, "Qual è la capitale della Francai?", "Francia", fact = "Parigi"))
        add(q(typ, "Qual è la capitale della Spagan?", "Spagna", fact = "Madrid", h = true))
        add(q(typ, "Capitale del Portogalo", "Portogallo", fact = "Lisbona"))
        add(q(typ, "Che lingua si parla in Germani?", "Germania", fact = "tedesco", h = true))
        add(q(typ, "Quanto è alto il Monte Bianko?", "Monte Bianco", fact = "4.806"))
        add(q(typ, "Cos'è la fotosinteci?", "Fotosintesi", fact = "luce", h = true))
        add(q(typ, "Che cos'è la crittografa?", "Crittografia", fact = "chiave"))
        add(q(typ, "Cos'è un algortimo?", "Algoritmo", fact = "passi"))
        add(q(typ, "Quanti pianeti ha il sistema solre?", "Sistema solare", fact = "otto", h = true))
        add(q(typ, "Qual è la capitale del Giapone?", "Giappone", fact = "Tokyo"))
        add(q(typ, "Qual è la capitale dell'Egito?", "Egitto", fact = "Cairo", h = true))
        add(q(typ, "Che cos'è la crttografia?", "Crittografia", fact = "chiave", h = true))

        val par = "parafrasi_nomi_alternativi"
        add(q(par, "Cosa sono le unità a stato solido?", "SSD", fact = "flash"))
        add(q(par, "Spiegami la memoria ad accesso casuale", "RAM", fact = "volatile", h = true))
        add(q(par, "Dimmi di più sulla rete privata virtuale", "VPN", fact = "cifrato", h = true))
        add(q(par, "Che cos'è l'acido desossiribonucleico?", "DNA", fact = "elica"))
        add(q(par, "Quanto è alto il Mont Blanc?", "Monte Bianco", fact = "4.806", h = true))
        add(q(par, "Spiegami come funziona il Wi-Fi e il Bluetooth", "Wi-Fi", "Bluetooth", fact = "802.11", h = true))

        val art = "richieste_articolate"
        add(q(art, "Confronta la Francia e la Spagna per superficie", "Francia", "Spagna", fact = "505.990"))
        add(q(art, "Qual è la differenza tra RAM e SSD?", "RAM", "SSD", fact = "volatile"))
        add(q(art, "Che differenza c'è tra Brasile e Portogallo come lingua?", "Brasile", "Portogallo", fact = "portoghese"))
        add(q(art, "Chi tra Francia e Italia ha la superficie maggiore?", "Francia", "Italia", fact = "301.340", h = true))
        add(q(art, "Quale tra Po e Nilo è più lungo?", "Po", "Nilo", fact = "6.650", h = true))
        add(q(art, "Il Vesuvio e le Alpi sono vulcani?", "Vesuvio", "Alpi", fact = "vulcano"))

        val miss = "fonti_mancanti"
        add(q(miss, "Qual è la capitale del Madagascar?"))
        add(q(miss, "Quanto è alto l'Everest?"))
        add(q(miss, "Chi ha scritto la Divina Commedia?"))
        add(q(miss, "Qual è la capitale della Mongolia?", h = true))
        add(q(miss, "Cos'è un transistor?"))
        add(q(miss, "Quanti abitanti ha la Cina?"))
        add(q(miss, "Quando è nato Mozart?", h = true))
        add(q(miss, "Cos'è la blockchain?"))
        add(q(miss, "Quanto è lungo il Rodano?"))
        add(q(miss, "Chi era Giulio Cesare?", h = true))
        add(q(miss, "Qual è il prezzo del petrolio oggi?"))
        add(q(miss, "Che tempo farà domani a Roma?", h = true))
        add(q(miss, "Chi ha vinto la partita di ieri sera?"))

        val ent = "nome_non_citato"
        add(q(ent, "Chi è partito da Genova nel 1492?"))
        add(q(ent, "Cosa è successo nel 1789?", h = true))
        add(q(ent, "Perché il cielo è blu?"))
        add(q(ent, "Qual è lo Stato europeo con capitale Roma?", h = true))

        val per = "personale_non_enciclopedica"
        add(q(per, "Come si chiama il mio cane?"))
        add(q(per, "Qual è il mio cibo preferito?"))
        add(q(per, "Cosa ho in agenda domani?", h = true))
        add(q(per, "Dove ho parcheggiato l'auto?"))
        add(q(per, "Ricordi la mia targa?", h = true))
        add(q(per, "Come si chiama mia sorella?"))
        add(q(per, "Quando è il mio compleanno?", h = true))
        add(q(per, "Qual è il mio colore preferito?"))

        val con = "fonti_contraddittorie_obsolete"
        add(q(con, "Quanti abitanti ha l'Italia?", "Italia", fact = "58,9", old = true, conflict = true))
        add(q(con, "Qual è la popolazione dell'Italia?", "Italia", fact = "59,4", h = true, old = true, conflict = true))
        add(q(con, "Quanti abitanti ha il Belgio?", "Belgio", fact = "11 milioni", old = true))
        add(q(con, "Qual è la capitale del Belgio?", "Belgio", fact = "Bruxelles", h = true, old = true))
    }

    private data class Outcome(val q: Q, val titlesOk: Boolean, val factOk: Boolean, val falseEvidence: Boolean, val conflict: Boolean, val ms: Double)

    private fun run(q: Q, sources: List<com.simone.jarvismobile.core.knowledge.zim.ZimKnowledgeSource>, assembler: EvidenceAssembler): Outcome {
        val t0 = System.nanoTime()
        val hits = sources.flatMap { it.articles(q.text, 3) }
        val ev = assembler.assemble(q.text, hits, emptyList(), 4)
        val ms = (System.nanoTime() - t0) / 1e6
        val g = ev as? Evidence.Grounded
        val titles = g?.passages?.map { KnowledgeText.fold(it.chunk.sourceTitle) }?.toSet().orEmpty()
        val text = g?.passages?.joinToString(" ") { KnowledgeText.fold(it.chunk.text) }.orEmpty()
        val titlesOk = q.titles.all { KnowledgeText.fold(it) in titles }
        val factOk = q.fact == null || q.fact in text
        return Outcome(q, titlesOk, factOk, falseEvidence = q.titles.isEmpty() && g != null, conflict = g?.conflict == true, ms = ms)
    }

    @Test fun `retrieval and grounding on 100+ questions over simulated sources`() {
        assertTrue(questions.size >= 100, "need at least 100 questions, have ${questions.size}")
        val current = KnowledgeFixtures.current()
        val oldSrc = KnowledgeFixtures.old()
        val assembler = EvidenceAssembler()
        val results = questions.map { run(it, if (it.old) listOf(current, oldSrc) else listOf(current), assembler) }

        val answerable = results.filter { it.q.titles.isNotEmpty() }
        val unanswerable = results.filter { it.q.titles.isEmpty() }
        fun pct(a: Int, b: Int) = if (b == 0) "n/a" else "%.1f%% (%d/%d)".format(100.0 * a / b, a, b)
        val md = StringBuilder("# Knowledge engine — retrieval & grounding on SIMULATED sources (${questions.size} questions)\n\n")
        md.append("| split | answerable | right article(s) | fact in evidence | unanswerable | no evidence (correct) | false evidence |\n|---|---|---|---|---|---|---|\n")
        for ((label, pred) in listOf<Pair<String, (Outcome) -> Boolean>>("dev" to { !it.q.holdout }, "holdout" to { it.q.holdout }, "all" to { true })) {
            val a = answerable.filter(pred); val u = unanswerable.filter(pred)
            md.append("| $label | ${a.size} | ${pct(a.count { it.titlesOk }, a.size)} | ${pct(a.count { it.titlesOk && it.factOk }, a.size)} | ${u.size} | ${pct(u.count { !it.falseEvidence }, u.size)} | ${pct(u.count { it.falseEvidence }, u.size)} |\n")
        }
        md.append("\n| category | n | right article(s) + fact | no-evidence correct |\n|---|---|---|---|\n")
        for ((cat, rs) in results.groupBy { it.q.category }) {
            val a = rs.filter { it.q.titles.isNotEmpty() }; val u = rs.filter { it.q.titles.isEmpty() }
            md.append("| $cat | ${rs.size} | ${if (a.isEmpty()) "-" else pct(a.count { it.titlesOk && it.factOk }, a.size)} | ${if (u.isEmpty()) "-" else pct(u.count { !it.falseEvidence }, u.size)} |\n")
        }
        val conflicts = results.filter { it.q.expectConflict }
        md.append("\nConflicting sources detected: ${pct(conflicts.count { it.conflict }, conflicts.size)}\n")
        md.append("Mean retrieval time per question (JVM, fixture archive, NOT a device figure): ${"%.2f".format(results.map { it.ms }.average())} ms; max ${"%.1f".format(results.maxOf { it.ms })} ms\n")
        val misses = answerable.filterNot { it.titlesOk && it.factOk }
        if (misses.isNotEmpty()) md.append("\nMissed answerable (${misses.size}):\n" + misses.joinToString("\n") { "- [${it.q.category}${if (it.q.holdout) ",holdout" else ""}] ${it.q.text}" } + "\n")
        val fe = unanswerable.filter { it.falseEvidence }
        if (fe.isNotEmpty()) md.append("\nFalse evidence (${fe.size}):\n" + fe.joinToString("\n") { "- [${it.q.category}] ${it.q.text}" } + "\n")
        File("build/reports/quality").apply { mkdirs() }.resolve("knowledge_eval.md").writeText(md.toString())
        println(md)

        // Design-intent guards (set before looking at the numbers): the engine must not fabricate evidence, and must find named subjects.
        assertTrue(unanswerable.count { it.falseEvidence }.toDouble() / unanswerable.size <= 0.10, "false-evidence rate too high:\n$md")
        assertTrue(answerable.count { it.titlesOk && it.factOk }.toDouble() / answerable.size >= 0.80, "retrieval too weak:\n$md")
        assertEquals(0, results.filter { it.q.category == "personale_non_enciclopedica" }.count { it.falseEvidence }, "personal questions must never produce encyclopaedic evidence")
    }

    // ---- claim support (SIMULATED model outputs) ---------------------------------------------------------------

    @Test fun `answers that contradict or go beyond the sources are flagged, faithful ones are not`() {
        val current = KnowledgeFixtures.current()
        val assembler = EvidenceAssembler()
        fun evidence(q: String): Evidence.Grounded = assembler.assemble(q, current.articles(q, 3), emptyList(), 3) as Evidence.Grounded
        fun text(e: Evidence.Grounded) = e.passages.joinToString("\n") { it.chunk.text }

        val q1 = "Quanto è grande l'Asia?"
        val e1 = evidence(q1)
        val faithful = AnswerQuality.evaluate(q1, "L'Asia è il continente più esteso, con circa 44,58 milioni di km².", emptyList(), emptyList(), text(e1))
        assertTrue(faithful.flags.none { it == AnswerQuality.Flag.UNSUPPORTED_NUMBERS || it == AnswerQuality.Flag.UNSUPPORTED_ENTITIES }, faithful.toString())
        val wrongNumber = AnswerQuality.evaluate(q1, "L'Asia misura circa 31 milioni di km².", emptyList(), emptyList(), text(e1))
        assertTrue(AnswerQuality.Flag.UNSUPPORTED_NUMBERS in wrongNumber.flags)
        assertTrue(AnswerQuality.present("L'Asia misura circa 31 milioni di km².", Provenance.SOURCE, wrongNumber).endsWith(AnswerQuality.NOTE_NOT_IN_SOURCES))

        val q2 = "Qual è la capitale della Francia?"
        val e2 = evidence(q2)
        val inventedEntity = AnswerQuality.evaluate(q2, "La capitale della Francia è Lione, sul fiume Rodano.", emptyList(), emptyList(), text(e2))
        assertTrue(AnswerQuality.Flag.UNSUPPORTED_ENTITIES in inventedEntity.flags, inventedEntity.toString())
        val supported = AnswerQuality.evaluate(q2, "La capitale della Francia è Parigi.", emptyList(), emptyList(), text(e2))
        assertTrue(supported.flags.isEmpty(), supported.toString())

        // No evidence at all: a confident entity/number answer from memory carries the honest note.
        val general = AnswerQuality.evaluate("Qual è la capitale della Mongolia?", "La capitale della Mongolia è Ulan Bator.", emptyList(), emptyList(), null)
        assertTrue(general.hasCheckableClaims)
        assertTrue(AnswerQuality.present("La capitale della Mongolia è Ulan Bator.", Provenance.MODEL_GENERAL, general).endsWith(AnswerQuality.NOTE_UNVERIFIED))
        // ...but ordinary conversation/explanations without checkable claims are left alone.
        val chat = AnswerQuality.evaluate("Come stai?", "Tutto bene, grazie, e tu?", emptyList(), emptyList(), null)
        assertTrue(!chat.hasCheckableClaims)
    }

    // ---- conflicting / obsolete sources ----------------------------------------------------------------------------

    @Test fun `conflicting sources are reported newest first and stale ones are named as such`() {
        val sources = listOf(KnowledgeFixtures.current(), KnowledgeFixtures.old())
        val q = "Quanti abitanti ha l'Italia?"
        val ev = EvidenceAssembler().assemble(q, sources.flatMap { it.articles(q, 3) }, emptyList(), 4) as Evidence.Grounded
        assertTrue(ev.conflict, "two archives with different Italian population figures must be flagged")
        assertEquals("2024-06-18", ev.passages.first().chunk.date, "the newest source comes first")
        val footer = SourceFooter.build(ev, currentYear = 2026)
        assertTrue("non concordano" in footer && "2012" in footer && "superata" in footer, footer)
        assertTrue("Mini enciclopedia" in footer)

        val only = KnowledgeFixtures.old()
        val q2 = "Quanti abitanti ha il Belgio?"
        val ev2 = EvidenceAssembler().assemble(q2, only.articles(q2, 3), emptyList(), 3) as Evidence.Grounded
        val footer2 = SourceFooter.build(ev2, 2026)
        assertTrue("2012" in footer2 && "superata" in footer2 && "non concordano" !in footer2, footer2)
    }
}
