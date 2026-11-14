package com.simone.jarvismobile.core.knowledge

import com.simone.jarvismobile.core.knowledge.zim.ZimKnowledgeSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * KNOWLEDGE ENGINE 2.0 — evaluation of RETRIEVAL + SUFFICIENCY on 120+ NEW questions over SIMULATED sources
 * (KnowledgeFixtures2). Not a measure of the real model nor of a real archive. The set was written before the second-round
 * tuning and is split: [Q.blind] questions were evaluated FIRST and are reported separately; only the other half was used to
 * fix anything.
 *
 * Expectations (what a pass means):
 *  - SUPPORTED: Grounded with a NON-related passage of the named article whose support window contains the fact;
 *  - RELATED_OK: the fact is in the retrieved article, but support may be only "related" (the attribute is worded differently);
 *  - UNSUPPORTABLE: the sources cannot answer (missing, relevant-but-insufficient, dictionary-only…): the engine must NOT
 *    establish support — Insufficient, NoEvidence, or Grounded with related-only passages are all correct.
 */
class GroundingEvalTest {
    enum class Env { PHONE_TODAY, WITH_ENCYCLOPEDIA, CONFLICT, ENCYCLOPEDIA_ONLY }
    enum class Exp { SUPPORTED, RELATED_OK, UNSUPPORTABLE, NO_FALSE_SUPPORT }

    data class Q(
        val id: Int, val cat: String, val text: String, val env: Env, val exp: Exp,
        val titles: Set<String> = emptySet(), val fact: String? = null, val blind: Boolean = false, val expectConflict: Boolean = false,
    )

    private var n = 0
    private fun q(cat: String, text: String, env: Env, exp: Exp, vararg titles: String, fact: String? = null, b: Boolean = false, conflict: Boolean = false) =
        Q(++n, cat, text, env, exp, titles.toSet(), fact?.let(KnowledgeText::fold), b, conflict)

    private val questions: List<Q> = buildList {
        val W = Env.WITH_ENCYCLOPEDIA; val P = Env.PHONE_TODAY; val E = Env.ENCYCLOPEDIA_ONLY; val C = Env.CONFLICT
        val S = Exp.SUPPORTED; val R = Exp.RELATED_OK; val U = Exp.UNSUPPORTABLE; val N = Exp.NO_FALSE_SUPPORT
        // A — capitals (supported explicitly)
        val a = "capitali"
        add(q(a, "Qual è la capitale della Polonia?", W, S, "Polonia", fact = "Varsavia"))
        add(q(a, "Dimmi la capitale dell'Ungheria", W, S, "Ungheria", fact = "Budapest", b = true))
        add(q(a, "Qual è la capitale della Svezia?", W, S, "Svezia", fact = "Stoccolma"))
        add(q(a, "Capitale della Norvegia?", W, S, "Norvegia", fact = "Oslo", b = true))
        add(q(a, "Qual è la capitale della Turchia?", W, S, "Turchia", fact = "Ankara"))
        add(q(a, "Qual è la capitale dell'Argentina?", W, S, "Argentina", fact = "Buenos Aires", b = true))
        add(q(a, "Mi sai dire la capitale del Messico?", W, S, "Messico", fact = "Città del Messico"))
        add(q(a, "Qual è la capitale dell'India?", W, S, "India", fact = "Nuova Delhi", b = true))
        add(q(a, "Qual è la capitale della Cina?", W, S, "Cina", fact = "Pechino"))
        add(q(a, "Qual è la capitale del Marocco?", W, S, "Marocco", fact = "Rabat", b = true))
        add(q(a, "Qual è la capitale della Georgia?", W, S, "Georgia", fact = "Tbilisi", b = true))
        // B — surface / language (supported explicitly)
        val bb = "superficie_lingua"
        add(q(bb, "Quanto è grande la Polonia come superficie?", W, S, "Polonia", fact = "312.696"))
        add(q(bb, "Qual è la superficie dell'Ungheria?", W, S, "Ungheria", fact = "93.030", b = true))
        add(q(bb, "Che lingua si parla in Svezia?", W, S, "Svezia", fact = "svedese"))
        add(q(bb, "Quale lingua si parla in Norvegia?", W, S, "Norvegia", fact = "norvegese", b = true))
        add(q(bb, "Quanto è estesa la Turchia?", W, R, "Turchia", fact = "783.562"))
        add(q(bb, "Che lingue ufficiali ha l'India?", W, S, "India", fact = "hindi", b = true))
        add(q(bb, "Qual è la lingua ufficiale dell'Argentina?", W, S, "Argentina", fact = "spagnolo"))
        add(q(bb, "Che lingue si parlano in Marocco?", W, S, "Marocco", fact = "berbero", b = true))
        // C — numbers (supported or related)
        val c = "numeri_geografia"
        add(q(c, "Quanto è lungo il Danubio?", W, R, "Danubio", fact = "2.850"))
        add(q(c, "Quanto è lungo il Reno?", W, R, "Reno", fact = "1.230", b = true))
        add(q(c, "Quanti chilometri è lungo il Tamigi?", W, S, "Tamigi", fact = "346"))
        add(q(c, "Quanto è alto l'Everest?", W, R, "Everest", fact = "8.849", b = true))
        add(q(c, "Quanto è alto il K2?", W, R, "K2", fact = "8.611"))
        add(q(c, "Quanto è alto il Cervino?", W, R, "Cervino", fact = "4.478", b = true))
        add(q(c, "Dove si trova il Cervino?", W, S, "Cervino", fact = "Svizzera"))
        add(q(c, "In quale mare sfocia il Danubio?", W, R, "Danubio", fact = "mar Nero", b = true))
        // D — history dates
        val d = "storia"
        add(q(d, "In che anno ci fu la Rivoluzione russa?", W, R, "Rivoluzione russa", fact = "1917"))
        add(q(d, "Quando fu conquistata Costantinopoli?", W, R, "Caduta di Costantinopoli", fact = "1453", b = true))
        add(q(d, "Quando si combatté la battaglia di Waterloo?", W, S, "Battaglia di Waterloo", fact = "1815"))
        add(q(d, "Quando l'uomo sbarcò sulla Luna?", W, R, "Sbarco sulla Luna", fact = "1969", b = true))
        add(q(d, "Chi era sulla Luna con Apollo 11?", W, S, "Sbarco sulla Luna", fact = "Armstrong", b = true))
        add(q(d, "Chi fu sconfitto a Waterloo?", W, S, "Battaglia di Waterloo", fact = "Napoleone"))
        // E — science, technology, medicine
        val e = "scienza_tecnica_medicina"
        add(q(e, "Chi scoprì la penicillina?", W, S, "Penicillina", fact = "Fleming"))
        add(q(e, "Cosa produce il mitocondrio?", W, S, "Mitocondrio", fact = "ATP", b = true))
        add(q(e, "Quanti elementi ci sono nella tavola periodica?", W, S, "Tavola periodica", fact = "118"))
        add(q(e, "In che anno fu inventato il transistor?", W, R, "Transistor", fact = "1947", b = true))
        add(q(e, "Quando fu realizzato il primo laser?", W, S, "Laser", fact = "1960"))
        add(q(e, "A che quota orbitano i satelliti del GPS?", W, R, "GPS", fact = "20.200", b = true))
        add(q(e, "Qual è il numero atomico del mercurio?", W, S, "Mercurio", fact = "80", b = true))
        add(q(e, "In quanti giorni Mercurio compie un'orbita?", W, S, "Mercurio", fact = "88"))
        add(q(e, "Che cos'è il diabete?", P, S, "Diabete", fact = "glucosio"))
        add(q(e, "Quando si parla di ipertensione?", P, S, "Ipertensione", fact = "140/90", b = true))
        add(q(e, "Quali sono i sintomi dell'asma?", P, S, "Asma", fact = "sibilante"))
        add(q(e, "Quali sono i sintomi dell'anemia?", P, S, "Anemia", fact = "pallore", b = true))
        add(q(e, "Quale organo produce l'insulina?", P, S, "Pancreas", fact = "pancreas", b = true))
        add(q(e, "Che cos'è l'ibuprofene?", P, S, "Ibuprofene", fact = "antinfiammatorio"))
        add(q(e, "Cos'è il paracetamolo?", P, S, "Paracetamolo", fact = "antipiretico", b = true))
        add(q(e, "Cosa serve per fare il pane?", P, S, "Fare il pane", fact = "lievito", b = true))
        add(q(e, "Come si crea una variabile in Python?", P, S, "Introduzione a Python", fact = "uguale"))
        add(q(e, "Come si cambia la camera d'aria di una bicicletta?", P, S, "Cambiare una gomma della bicicletta", fact = "copertone", b = true))
        // F — typos (ideal: supported; acceptable: nothing — never a false support)
        val f = "refusi"
        add(q(f, "Qual è la capitale della Polnia?", W, N, b = true))
        add(q(f, "Qual è la capitle della Svezia?", W, N))
        add(q(f, "Qual è la capitale dell'Ungheia?", W, N, b = true))
        add(q(f, "Che lingua si parla in Norvegai?", W, N))
        add(q(f, "Quanto è lungo il Danubbio?", W, N, b = true))
        add(q(f, "Chi scopri la penicilina?", W, N))
        add(q(f, "Quando fu realizato il primo laser?", W, N, b = true))
        add(q(f, "Cos'è il diabette?", P, N))
        add(q(f, "Quali sono i sintomi dell'anemmia?", P, N, b = true))
        add(q(f, "Quale organo produce l'insulna?", P, N))
        // G — the phone TODAY: dictionary + medicine + how-to, NO encyclopedia. Capitals / facts about countries cannot be answered.
        val g = "telefono_oggi_senza_enciclopedia"
        add(q(g, "Qual è la capitale del Portogallo?", P, U))
        add(q(g, "Qual è la capitale della Polonia?", P, U, b = true))
        add(q(g, "Qual è la capitale della Turchia?", P, U))
        add(q(g, "Dimmi la capitale del Portogallo", P, U, b = true))
        add(q(g, "Capitale del Portogallo?", P, U))
        add(q(g, "Quanto è lungo il Danubio?", P, U, b = true))
        add(q(g, "Quanti abitanti ha la Turchia?", P, U))
        add(q(g, "Qual è la lingua ufficiale del Portogallo?", P, U, b = true))
        add(q(g, "Quanto è alto l'Everest?", P, U))
        add(q(g, "Quando fu la battaglia di Waterloo?", P, U, b = true))
        add(q(g, "Che moneta si usa in Polonia?", P, U))
        add(q(g, "In quale città sul Tago si trova il governo portoghese?", P, N, b = true))
        // H — lexical questions the dictionary DOES answer (defined by the entry's own title)
        val h = "lessicali_dizionario"
        add(q(h, "Che cosa significa capitale?", P, S, "capitale", fact = "governo"))
        add(q(h, "Cosa vuol dire altezza?", P, S, "altezza", fact = "verticale", b = true))
        add(q(h, "Significato di moneta", P, S, "moneta", fact = "pagamento"))
        add(q(h, "Cos'è un fiume?", P, S, "fiume", fact = "corso d'acqua", b = true))
        add(q(h, "Che cosa significa popolazione?", P, S, "popolazione", fact = "abitanti"))
        // I — relevant sources exist, the attribute asked is not in them (must not be supported)
        val i = "rilevanti_ma_insufficienti"
        add(q(i, "Qual è la moneta della Polonia?", W, U, b = true))
        add(q(i, "Quanti abitanti ha l'Ungheria?", W, U))
        add(q(i, "Qual è la capitale del Danubio?", W, U, b = true))
        add(q(i, "Chi ha costruito il Cervino?", W, U))
        add(q(i, "Quanto pesa l'Everest?", W, U, b = true))
        add(q(i, "Che temperatura ha Mercurio sulla superficie?", W, U))
        add(q(i, "Qual è la causa dell'asma?", P, U, b = true))
        add(q(i, "Qual è la dose massima di paracetamolo?", P, U))
        add(q(i, "Quanto costa l'ibuprofene?", P, U, b = true))
        add(q(i, "Chi ha inventato il laser?", W, U))
        add(q(i, "Quanto dura la lievitazione del pane in frigorifero?", P, U, b = true))
        // J — missing subjects
        val j = "fonti_mancanti"
        add(q(j, "Qual è la capitale della Mongolia?", W, U))
        add(q(j, "Qual è la capitale del Kenya?", W, U, b = true))
        add(q(j, "Quanti abitanti ha Tokyo?", W, U))
        add(q(j, "Chi ha dipinto la Gioconda?", W, U, b = true))
        add(q(j, "Quanto è alto il Monte Rosa?", W, U))
        add(q(j, "Quando cadde il Muro di Berlino?", W, U, b = true))
        add(q(j, "Qual è la formula dell'acqua?", W, U))
        add(q(j, "Che cos'è la malaria?", P, U, b = true))
        add(q(j, "Quali sono i sintomi del morbillo?", P, U))
        add(q(j, "Come si fa la pizza napoletana?", P, U, b = true))
        // K — similar titles
        val k = "titoli_simili"
        add(q(k, "Qual è la distanza media di Mercurio dal Sole?", W, S, "Mercurio", fact = "57,9"))
        add(q(k, "Che simbolo chimico ha il mercurio?", W, S, "Mercurio", fact = "Hg", b = true))
        add(q(k, "Qual è la capitale della Georgia negli Stati Uniti?", W, N, b = true))
        // L — personal / non-encyclopaedic
        val l = "personali"
        add(q(l, "Qual è la mia targa?", W, U))
        add(q(l, "Dove ho parcheggiato la moto?", W, U, b = true))
        add(q(l, "Che cosa ho in agenda domani?", W, U))
        add(q(l, "Come si chiama mia sorella?", W, U, b = true))
        add(q(l, "Qual è il mio numero di telefono?", W, U))
        add(q(l, "Ricordami di comprare il pane", W, U, b = true))
        // M — articulated reasoning
        val m = "articolate"
        add(q(m, "Confronta la Polonia e l'Ungheria per superficie", W, S, "Polonia", "Ungheria", fact = "93.030"))
        add(q(m, "Quale tra Danubio e Reno è più lungo?", W, R, "Danubio", "Reno", fact = "1.230", b = true))
        add(q(m, "Qual è la differenza tra diabete di tipo 1 e di tipo 2?", P, S, "Diabete", fact = "carenza", b = true))
        add(q(m, "Chi ha scoperto la penicillina e in che anno?", W, S, "Penicillina", fact = "1928"))
        add(q(m, "Quali elementi servono per il pane e quanto deve lievitare?", P, S, "Fare il pane", fact = "due ore", b = true))
        add(q(m, "Dimmi capitale e lingua della Svezia", W, S, "Svezia", fact = "svedese"))
        add(q(m, "Cosa sono ibuprofene e paracetamolo?", P, R, "Ibuprofene", "Paracetamolo", fact = "analgesic", b = true))
        // N — contradictory / obsolete sources
        val o = "fonti_contraddittorie"
        add(q(o, "Quanti abitanti ha la Polonia?", C, S, "Polonia", fact = "37,6", conflict = true))
        add(q(o, "Quanti abitanti ha la Turchia?", C, S, "Turchia", fact = "85", b = true, conflict = true))
        add(q(o, "Qual è la capitale della Turchia?", C, S, "Turchia", fact = "Ankara", b = true))
        // P — enc only: attribute absent (the encyclopedia article is about the subject but lacks the attribute)
        val p = "solo_enciclopedia_attributo_assente"
        add(q(p, "Che moneta si usa in Polonia?", E, U, b = true))
        add(q(p, "Qual è l'inno nazionale della Svezia?", E, U))
        add(q(p, "Quanto è profondo il Danubio?", E, U, b = true))
    }

    private fun sources(env: Env): List<ZimKnowledgeSource> = when (env) {
        Env.PHONE_TODAY -> KnowledgeFixtures2.phoneToday()
        Env.WITH_ENCYCLOPEDIA -> KnowledgeFixtures2.withEncyclopedia()
        Env.CONFLICT -> listOf(KnowledgeFixtures2.encyclopedia(), KnowledgeFixtures2.oldEncyclopedia())
        Env.ENCYCLOPEDIA_ONLY -> listOf(KnowledgeFixtures2.encyclopedia())
    }

    private data class Out(val q: Q, val ev: Evidence, val pass: Boolean, val supported: Boolean, val falseSupport: Boolean, val note: String, val falseStrong: Boolean = false)

    private fun judge(q: Q, ev: Evidence): Out {
        val g = ev as? Evidence.Grounded
        val proof = g?.passages?.filter { !it.related }.orEmpty()
        val titles = proof.map { KnowledgeText.fold(it.chunk.sourceTitle).substringBefore(" (") }.toSet()
        val supportedHere = g != null && g.supportEstablished
        fun titlesIn(passages: List<ScoredChunk>) = q.titles.all { t -> passages.any { KnowledgeText.fold(it.chunk.sourceTitle).substringBefore(" (") == KnowledgeText.fold(t).substringBefore(" (") } }
        val supportedOk = q.titles.isNotEmpty() && proof.isNotEmpty() && titlesIn(proof) && (q.fact == null || proof.any { q.fact in KnowledgeText.fold(it.supportWindow) })
        val relatedOk = g != null && g.passages.isNotEmpty() && titlesIn(g.passages) && (q.fact == null || g.passages.any { q.fact in KnowledgeText.fold(it.supportWindow + " " + it.chunk.text) })
        return when (q.exp) {
            Exp.SUPPORTED -> Out(q, ev, supportedOk, supportedOk, false, "")
            Exp.RELATED_OK -> Out(q, ev, supportedOk || relatedOk, supportedOk, false, "")
            Exp.UNSUPPORTABLE -> Out(q, ev, !supportedHere, supportedHere, supportedHere, if (supportedHere) "falsely supported by " + proof.map { it.chunk.sourceTitle } + " (" + (ev as Evidence.Grounded).strength + ")" else "",
                falseStrong = supportedHere && (ev as Evidence.Grounded).strength == EvidenceStrength.STRONG)
            // typos / ambiguous: either a correct support or no support; a support for a WRONG fact would be the failure
            Exp.NO_FALSE_SUPPORT -> {
                val wrong = supportedHere && proof.none { KnowledgeText.fold(it.chunk.sourceTitle) in listOf("polonia", "svezia", "ungheria", "norvegia", "danubio", "penicillina", "laser", "diabete", "anemia", "insulina", "pancreas", "georgia (stato)", "georgia (stati uniti)", "georgia (stati uniti)") }
                Out(q, ev, !wrong, supportedHere, wrong, if (wrong) "support for unrelated article ${proof.map { it.chunk.sourceTitle }}" else "")
            }
        }
    }

    /** [withIndex]: also consult the persistent FTS index of the leads of every archive (opt-in indexing, as in the app). */
    private fun evaluate(withIndex: Boolean): List<Out> {
        val cache = HashMap<Env, Pair<List<ZimKnowledgeSource>, com.simone.jarvismobile.core.knowledge.fts.KnowledgeIndex?>>()
        val gatherer = KnowledgeGatherer()
        return questions.map { q ->
            val (srcs, ix) = cache.getOrPut(q.env) {
                val s = sources(q.env)
                val index = if (withIndex) {
                    val f = File.createTempFile("kxeval", ".db").also { it.deleteOnExit() }
                    com.simone.jarvismobile.core.knowledge.fts.KnowledgeIndex(com.simone.jarvismobile.core.knowledge.fts.JdbcFtsDb(f)).also { ix ->
                        s.forEach { src -> com.simone.jarvismobile.core.knowledge.zim.ZimIndexer(src.zim, ix).run("sig", 100_000, 500_000_000) }
                    }
                } else null
                s to index
            }
            judge(q, gatherer.gather(q.text, srcs, ix, limit = 4))
        }
    }

    private fun pct(a: Int, b: Int) = if (b == 0) "n/a" else "%.1f%% (%d/%d)".format(100.0 * a / b, a, b)

    private fun report(title: String, results: List<Out>): String {
        val md = StringBuilder("## $title\n\n")
        md.append("| split | answerable (S/R) | passed | unsupportable | passed (no support established) | typos/ambiguous | passed |\n|---|---|---|---|---|---|---|\n")
        for ((label, pred) in listOf<Pair<String, (Out) -> Boolean>>("DEV (used for tuning)" to { !it.q.blind }, "BLIND (evaluated first)" to { it.q.blind }, "all" to { true })) {
            val rs = results.filter(pred)
            val ans = rs.filter { it.q.exp == Exp.SUPPORTED || it.q.exp == Exp.RELATED_OK }
            val un = rs.filter { it.q.exp == Exp.UNSUPPORTABLE }
            val ty = rs.filter { it.q.exp == Exp.NO_FALSE_SUPPORT }
            md.append("| $label | ${ans.size} | ${pct(ans.count { it.pass }, ans.size)} | ${un.size} | ${pct(un.count { it.pass }, un.size)} | ${ty.size} | ${pct(ty.count { it.pass }, ty.size)} |\n")
        }
        md.append("\n| category | n | passed |\n|---|---|---|\n")
        for ((cat, rs) in results.groupBy { it.q.cat }) md.append("| $cat | ${rs.size} | ${pct(rs.count { it.pass }, rs.size)} |\n")
        val strict = results.filter { it.q.exp == Exp.SUPPORTED }
        md.append("\nExplicitly supported questions answered with an ESTABLISHED support window: ${pct(strict.count { it.pass }, strict.size)}\n")
        md.append("False support (a source established as sufficient where the sources cannot answer): ${results.count { it.falseSupport }} of ${results.count { it.q.exp != Exp.SUPPORTED && it.q.exp != Exp.RELATED_OK }}; of which STRONG: ${results.count { it.falseStrong }}\n")
        val conflicts = results.filter { it.q.expectConflict }
        md.append("Conflicting sources flagged: ${pct(conflicts.count { (it.ev as? Evidence.Grounded)?.conflict == true }, conflicts.size)}\n")
        val failed = results.filterNot { it.pass }
        if (failed.isNotEmpty()) md.append("\nFailed (${failed.size}):\n" + failed.joinToString("\n") { "- [${it.q.cat}${if (it.q.blind) ",BLIND" else ""}/${it.q.exp}] ${it.q.text} ${it.note}" } + "\n")
        return md.toString()
    }

    @Test fun `120+ new questions - sufficiency, not mere relevance`() {
        assertTrue(questions.size >= 120, "need at least 120 questions, have ${questions.size}")
        val titles = evaluate(withIndex = false)
        val indexed = evaluate(withIndex = true)
        val md = "# Knowledge Engine 2.0 — retrieval + sufficiency on SIMULATED sources (${questions.size} NEW questions)\n\n" +
            "Environments: PHONE_TODAY = Wikizionario-like + Wikimed-like + Wikibooks-like (no encyclopedia); WITH_ENCYCLOPEDIA adds a Wikipedia-like archive.\n" +
            "JVM + simulated sources: says nothing about the real model or the real archives.\n\n" +
            report("A. Direct reading by TITLE only", titles) + "\n" + report("B. Title reading + persistent FTS index of the archive leads", indexed)
        File("build/reports/quality").apply { mkdirs() }.resolve("grounding_eval.md").writeText(md)
        println(md)

        for ((name, rs) in listOf("titles" to titles, "titles+index" to indexed)) {
            assertEquals(0, rs.count { it.falseStrong }, "[$name] a lexical overlap was classified STRONG for a question the sources cannot answer:\n$md")
            val unsupportable = rs.count { it.q.exp == Exp.UNSUPPORTABLE }
            assertTrue(rs.count { it.falseSupport }.toDouble() / unsupportable <= 0.05, "[$name] too many sources presented as sufficient:\n$md")
        }
        val strict = { rs: List<Out> -> rs.filter { it.q.exp == Exp.SUPPORTED }.let { s -> s.count { it.pass }.toDouble() / s.size } }
        assertTrue(strict(indexed) >= strict(titles), "the index must not lose recall")
        assertTrue(strict(titles) >= 0.75, "[titles] supported recall too low:\n$md")
    }

    @Test fun `the reported Portugal case - dictionary entries named by one word each are not evidence`() {
        val srcs = KnowledgeFixtures2.phoneToday()
        for (qText in listOf("Qual è la capitale del Portogallo?", "capitale del portogallo", "Dimmi qual è la capitale del Portogallo")) {
            val hits = srcs.flatMap { it.articles(qText, 3) }
            assertTrue(hits.any { it.chunks.first().sourceTitle == "capitale" } && hits.any { it.chunks.first().sourceTitle == "Portogallo" }, "both dictionary entries ARE retrieved (relevance) for: $qText")
            val ev = EvidenceAssembler().assemble(qText, hits, emptyList(), 4)
            assertTrue(ev is Evidence.Insufficient, "relevant but insufficient, never STRONG: $qText -> $ev")
            val consulted = (ev as Evidence.Insufficient).consulted.map { it.sourceTitle }
            assertTrue("capitale" in consulted && "Portogallo" in consulted, consulted.toString())
            val footer = SourceFooter.build(GroundingVerifier.verify(qText, "La capitale del Portogallo è Lisbona.", ev), ev, 2026)
            assertTrue(footer.startsWith(SourceFooter.INSUFFICIENT_PREFIX) && !footer.startsWith(SourceFooter.SUPPORTED_PREFIX), footer)
        }
        // the same question WITH an encyclopedia article that states it: supported, STRONG, cited
        val withEnc = KnowledgeFixtures2.withEncyclopedia()
        val q = "Qual è la capitale della Polonia?"
        val ev = EvidenceAssembler().assemble(q, withEnc.flatMap { it.articles(q, 3) }, emptyList(), 4) as Evidence.Grounded
        assertTrue(ev.supportEstablished)
        val v = GroundingVerifier.verify(q, "La capitale della Polonia è Varsavia.", ev)
        assertEquals(GroundingState.CLAIM_SUPPORTED, v.state)
        assertEquals(listOf("Polonia"), v.supporting.map { it.chunk.sourceTitle }, "only the passage that supports the answer is cited, not the dictionary entries")
    }
}
