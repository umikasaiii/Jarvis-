package com.simone.jarvismobile.core.knowledge

import com.simone.jarvismobile.core.knowledge.fts.FtsDb
import com.simone.jarvismobile.core.knowledge.fts.FtsRow
import com.simone.jarvismobile.core.knowledge.fts.JdbcFtsDb
import com.simone.jarvismobile.core.knowledge.fts.KnowledgeIndex
import com.simone.jarvismobile.core.knowledge.zim.CountingSource
import com.simone.jarvismobile.core.knowledge.zim.TestDecompressor
import com.simone.jarvismobile.core.knowledge.zim.TestZimBuilder
import com.simone.jarvismobile.core.knowledge.zim.TitleLayout
import com.simone.jarvismobile.core.knowledge.zim.ZimArchive
import com.simone.jarvismobile.core.knowledge.zim.ZimIndexer
import com.simone.jarvismobile.core.knowledge.zim.ZimKnowledgeSource
import java.io.File

/**
 * M2 — a reproducible retrieval benchmark over a SYNTHETIC archive (invented entities: nothing in the product or the corpus contains the answer
 * to a real-world question, so a result cannot come from memorised facts). It measures RETRIEVAL (is the passage that holds the fact among the
 * returned evidence, and where), not language-model quality, and says nothing about the real Wikipedia. Questions are tagged dev / holdout; the
 * holdout set was written before any M2 code and is not used to choose parameters.
 */
object RetrievalBench {
    enum class Cat { LEAD_TITLE, SECTION_TITLE, LONG_ARTICLE_SECTION, INDEX_LEAD, DEEP_SECTION, TYPO_TITLE, TYPO_CONTENT, SIMILAR, CANDIDATE_LIMIT, NUMBER, ABSENT }

    class Q(val cat: Cat, val text: String, val fact: String?, val holdout: Boolean)

    /** counts every statement the index runs, delegating to a real SQLite */
    class CountingDb(private val inner: JdbcFtsDb) : FtsDb, AutoCloseable {
        var queries = 0; var execs = 0
        override fun exec(sql: String, vararg args: Any?) { execs++; inner.exec(sql, *args) }
        override fun <T> query(sql: String, vararg args: Any?, map: (FtsRow) -> T): List<T> { queries++; return inner.query(sql, *args, map = map) }
        override fun <T> transaction(body: () -> T): T = inner.transaction(body)
        override fun execMany(sql: String, rows: List<Array<Any?>>) { execs++; inner.execMany(sql, rows) }
        override fun close() = inner.close()
    }

    class Corpus(val source: ZimKnowledgeSource, val reads: CountingSource, val questions: List<Q>)

    private val countries = listOf(
        // name, capital, lead extra, population (millions), river
        listOf("Valdoria", "Merenza", "3,2", "Lisara"), listOf("Karnelia", "Torvina", "7,8", "Dovane"), listOf("Ostrovia", "Belmarre", "1,9", "Sunelle"),
        listOf("Quillandia", "Arvenna", "5,5", "Orbena"), listOf("Zeferia", "Lunaris", "12,4", "Calidra"), listOf("Brontavia", "Sparnoca", "2,7", "Verlana"),
        listOf("Fandoria", "Eldrimo", "9,1", "Tamiro"), listOf("Gelmeria", "Ostenza", "4,3", "Rilova"),
    )

    private fun html(title: String, lead: String, sections: List<Pair<String, String>> = emptyList()): String = buildString {
        append("<html><head><title>").append(title).append("</title></head><body><h1>").append(title).append("</h1><p>").append(lead).append("</p>")
        for ((h, t) in sections) append("<h2>").append(h).append("</h2><p>").append(t).append("</p>")
        append("</body></html>")
    }

    fun build(): Corpus {
        val b = TestZimBuilder().apply { titleLayout = TitleLayout.MODERN; entriesPerCluster = 20; compression = 5; uuidSeed = 7 }
        b.metadata("Title", "Enciclopedia sintetica").metadata("Date", "2026-01-01").metadata("Language", "ita")
        val qs = ArrayList<Q>()
        fun q(cat: Cat, text: String, fact: String?, holdout: Boolean = false) { qs += Q(cat, text, fact?.let(KnowledgeText::fold), holdout) }

        // 1. LEAD_TITLE — the lead holds the fact and the title is named
        countries.forEachIndexed { i, (name, capital, pop, river) ->
            b.article(name, name, html(name, "$name è uno Stato immaginario. La capitale è $capital. La popolazione è di circa $pop milioni di abitanti. Il fiume principale è il $river."))
            if (i < 5) q(Cat.LEAD_TITLE, "Qual è la capitale di $name?", capital, holdout = i % 2 == 1)
            else q(Cat.NUMBER, "Quanti abitanti ha $name?", "$pop milioni", holdout = i % 2 == 0)
        }
        q(Cat.LEAD_TITLE, "Qual è il fiume principale della Valdoria?", "Lisara", true)

        // 2. SECTION_TITLE — fact in a section of the named article, lead generic
        val cities = listOf(
            listOf("Marenza", "1347", "la peste", "il Ponte dei Sospiri"), listOf("Torreselva", "1102", "un incendio", "la Torre Mozza"),
            listOf("Pontecalda", "1580", "una frana", "le Terme Alte"), listOf("Rivalba", "1261", "un'alluvione", "il Mulino Vecchio"),
            listOf("Selvarossa", "1499", "un assedio", "la Porta Grande"), listOf("Campodoro", "1633", "un terremoto", "la Fontana Rossa"),
        )
        cities.forEachIndexed { i, (name, year, event, landmark) ->
            b.article(name, name, html(name, "$name è una città immaginaria con un centro storico.", listOf(
                "Geografia" to "La città sorge in una valle ampia e ventosa.", "Storia" to "La città fu fondata nel $year e fu quasi distrutta da $event.",
                "Monumenti" to "Il monumento più visitato è $landmark.",
            )))
            q(Cat.SECTION_TITLE, "Quando fu fondata la città di $name?", year, holdout = i % 2 == 1)
            if (i < 3) q(Cat.SECTION_TITLE, "Qual è il monumento più visitato di $name?", landmark, holdout = i == 1)
        }

        // 3. LONG_ARTICLE_SECTION — answer deep inside a very long article (beyond the chunk caps of the title read)
        for (k in 0 until 4) {
            val name = listOf("Vulcano Orvella", "Fiume Dornava", "Catena Selmira", "Lago Perlane")[k]
            val fact = listOf("2.140 metri", "318 chilometri", "il monte Aldara", "ottantadue metri")[k]
            val sections = (0 until 120).map { s -> "Sezione $s" to "Questa sezione numero $s descrive aspetti generici e ripetitivi senza dati notevoli, con parole comuni." } +
                listOf("Dati principali" to "Il dato fondamentale riportato dalle misurazioni è ${listOf("l'altezza di 2.140 metri", "la lunghezza di 318 chilometri", "la vetta più alta, il monte Aldara", "la profondità massima di ottantadue metri")[k]}.")
            b.article(name.replace(' ', '_'), name, html(name, "$name è un elemento geografico immaginario molto esteso.", sections))
            q(Cat.LONG_ARTICLE_SECTION, listOf("Quanto è alto il Vulcano Orvella?", "Quanto è lungo il Fiume Dornava?", "Qual è la vetta più alta della Catena Selmira?", "Quanto è profondo il Lago Perlane?")[k], fact, holdout = k % 2 == 1)
        }

        // 4. INDEX_LEAD — the article is NOT named; its lead holds the answer and is in the index
        val things = listOf(
            listOf("Brillanite", "Quale minerale è usato per rendere brillanti le vernici?", "minerale"), listOf("Orpella", "Quale uccello migra ogni anno verso le isole del nord?", "uccello migratore"),
            listOf("Zirconella", "Quale metallo rosa viene usato nei contatti elettrici?", "metallo rosa"), listOf("Tarvisia", "Quale pianta cresce soltanto sopra i duemila metri?", "pianta alpina"),
            listOf("Moldavite Nera", "Quale pietra scura si forma dopo l'impatto di un meteorite?", "pietra scura"), listOf("Felcerba", "Quale erba medicinale cura la tosse secca?", "erba medicinale"),
        )
        things.forEachIndexed { i, (title, question, what) ->
            val lead = when (i) {
                0 -> "La brillanite è un minerale usato per rendere brillanti le vernici industriali."
                1 -> "L'orpella è un uccello migratore che ogni anno migra verso le isole del nord."
                2 -> "La zirconella è un metallo rosa usato nei contatti elettrici."
                3 -> "La tarvisia è una pianta alpina che cresce soltanto sopra i duemila metri."
                4 -> "La moldavite nera è una pietra scura che si forma dopo l'impatto di un meteorite."
                else -> "La felcerba è un'erba medicinale che cura la tosse secca."
            }
            b.article(title.replace(' ', '_'), title, html(title, lead))
            q(Cat.INDEX_LEAD, question, what, holdout = i % 2 == 0)
        }

        // 5. DEEP_SECTION — found through the index (its lead shares the question's words), the answer is in a SECTION
        val deep = listOf(
            listOf("Pancrelia", "Quale organo produce la teloxina?", "produce la teloxina nelle isole centrali", "La pancrelia è un organo ghiandolare dell'addome legato alla teloxina."),
            listOf("Cardonia", "Quale organo regola il ritmo della pulsazione veloce?", "regola il ritmo con il nodo sinuale", "La cardonia è un organo muscolare legato al ritmo della pulsazione veloce."),
            listOf("Epatrice", "Quale organo trasforma la sostanza amarilla?", "trasforma la sostanza amarilla in bilirubina", "L'epatrice è un organo ghiandolare che interviene sulla sostanza amarilla."),
            listOf("Nefrone Alto", "Quale struttura filtra il liquido sanguigno?", "filtra il liquido sanguigno nei glomeruli", "Il nefrone alto è una struttura che riguarda il liquido sanguigno."),
            listOf("Timpania", "Quale membrana trasmette le vibrazioni sonore?", "trasmette le vibrazioni sonore agli ossicini", "La timpania è una membrana collegata alle vibrazioni sonore."),
            listOf("Glossaria", "Quale struttura percepisce il sapore amaro?", "percepisce il sapore amaro alla base", "La glossaria è una struttura connessa al sapore amaro."),
        )
        deep.forEachIndexed { i, (title, question, fact, lead) ->
            b.article(title.replace(' ', '_'), title, html(title, lead, listOf("Anatomia" to "Si trova in una cavità profonda e protetta.", "Funzione" to "In condizioni normali ${fact}.")))
            q(Cat.DEEP_SECTION, question, fact, holdout = i % 2 == 1)
        }

        // 6. TYPO_TITLE / TYPO_CONTENT
        q(Cat.TYPO_TITLE, "Qual è la capitale della Karnelya?", "Torvina")
        q(Cat.TYPO_TITLE, "Qual è la capitale di Osrtovia?", "Belmarre", true)
        q(Cat.TYPO_TITLE, "Quando fu fondata la città di Marenzza?", "1347")
        q(Cat.TYPO_TITLE, "Qual è la capitale di Zeferria?", "Lunaris", true)
        q(Cat.TYPO_CONTENT, "Quale minerale è usato per rendere brilanti le vernici?", "minerale", true)
        q(Cat.TYPO_CONTENT, "Quale uccello migra verso le isole del nrod?", "uccello migratore")

        // 7. SIMILAR — two similar articles, different facts
        b.article("Marenzo", "Marenzo", html("Marenzo", "Marenzo è una città immaginaria diversa da Marenza.", listOf("Storia" to "La città di Marenzo fu fondata nel 1721.")))
        b.article("Selvarosa", "Selvarosa", html("Selvarosa", "Selvarosa è un villaggio immaginario vicino a Selvarossa.", listOf("Storia" to "Il villaggio di Selvarosa fu fondato nel 1844.")))
        q(Cat.SIMILAR, "Quando fu fondata la città di Marenzo?", "1721"); q(Cat.SIMILAR, "Quando fu fondato il villaggio di Selvarosa?", "1844", true)
        q(Cat.SIMILAR, "Quando fu fondata la città di Selvarossa?", "1499"); q(Cat.SIMILAR, "Quando fu fondata la città di Marenza?", "1347", true)

        // 8. CANDIDATE_LIMIT — 300 filler leads contain every term of the question; the answering lead is shorter and inserted LAST
        val fillerNames = (0 until 300)
        fillerNames.forEach { n -> b.article("Comune_%03d".format(n), "Comune %03d".format(n), html("Comune %03d".format(n), "Il comune di Frazione%03d conserva un ponte romano antico di cui non si conosce l'anno in cui fu costruito.".format(n))) }
        b.article("Zeta_Arcobaleno", "Zeta Arcobaleno", html("Zeta Arcobaleno", "Il ponte romano antico di Arcobaleno fu costruito nell'anno 27 a.C."))
        q(Cat.CANDIDATE_LIMIT, "In che anno fu costruito il ponte romano antico del comune?", "27 a.c")
        q(Cat.CANDIDATE_LIMIT, "Quando fu costruito il ponte romano antico?", "27 a.c", true)

        // 9. ABSENT — nothing in the corpus answers
        q(Cat.ABSENT, "Qual è la capitale di Nemoria?", null); q(Cat.ABSENT, "Quale organo produce l'ormone vexorina?", null, true)
        q(Cat.ABSENT, "Quanti abitanti ha la città di Pietrafalsa?", null); q(Cat.ABSENT, "Qual è la profondità del Lago Inesistente?", null, true)

        val bytes = b.build()
        val counting = CountingSource(bytes)
        val zim = ZimArchive.open(counting, TestDecompressor)
        return Corpus(ZimKnowledgeSource(zim), counting, qs)
    }

    class Result(
        val name: String, val recall3: Double, val recall5: Double, val precisionAt3: Double, val mrr: Double, val sectionRecall5: Double,
        val falseSupportOnAbsent: Int, val absent: Int, val relatedPromotions: Int, val p50ms: Double, val p95ms: Double,
        val sqlitePerQuery: Double, val zimReadsPerQuery: Double, val failures: List<String>, val byCat: Map<Cat, Pair<Int, Int>>,
    )

    /** Runs [gather] over the questions (dev + holdout, reported separately by the caller through [filter]). */
    fun evaluate(
        name: String, corpus: Corpus, db: CountingDb, filter: (Q) -> Boolean = { true },
        gather: (String) -> Evidence,
    ): Result {
        val qs = corpus.questions.filter(filter)
        val answerable = qs.filter { it.fact != null }
        var r3 = 0; var r5 = 0; var rr = 0.0; var p3 = 0.0; var sec = 0; var secTotal = 0
        val lat = ArrayList<Double>(); val failures = ArrayList<String>()
        var absentFalse = 0; var related = 0
        val cats = HashMap<Cat, IntArray>()
        val q0 = db.queries; val z0 = corpus.reads.reads.get()
        for (q in qs) {
            val t0 = System.nanoTime()
            val ev = gather(q.text)
            lat += (System.nanoTime() - t0) / 1e6
            val passages = (ev as? Evidence.Grounded)?.passages.orEmpty()
            if (ev is Evidence.Grounded && ev.strength == EvidenceStrength.STRONG && !ev.supportEstablished) related++
            if (q.fact == null) {
                val supported = ev is Evidence.Grounded && ev.supportEstablished
                if (supported) { absentFalse++; failures += "ABSENT supported: ${q.text}" }
                cats.getOrPut(q.cat) { IntArray(2) }.also { it[1]++; if (!supported) it[0]++ }
                continue
            }
            val rank = passages.indexOfFirst { KnowledgeText.fold(it.chunk.text + " " + it.supportWindow).contains(q.fact) } + 1
            if (rank in 1..3) r3++
            if (rank in 1..5) r5++
            if (rank > 0) rr += 1.0 / rank
            p3 += passages.take(3).count { KnowledgeText.fold(it.chunk.text + " " + it.supportWindow).contains(q.fact) }.toDouble() / 3.0
            if (q.cat in setOf(Cat.SECTION_TITLE, Cat.LONG_ARTICLE_SECTION, Cat.DEEP_SECTION)) { secTotal++; if (rank in 1..5) sec++ }
            cats.getOrPut(q.cat) { IntArray(2) }.also { it[1]++; if (rank in 1..5) it[0]++ }
            if (rank !in 1..5) failures += "MISS@5 [${q.cat}] ${q.text}"
        }
        lat.sort()
        fun pct(p: Double) = if (lat.isEmpty()) 0.0 else lat[((lat.size - 1) * p).toInt()]
        val n = answerable.size.coerceAtLeast(1)
        return Result(
            name, r3.toDouble() / n, r5.toDouble() / n, p3 / n, rr / n, if (secTotal == 0) 0.0 else sec.toDouble() / secTotal,
            absentFalse, qs.count { it.fact == null }, related, pct(0.5), pct(0.95),
            (db.queries - q0).toDouble() / qs.size.coerceAtLeast(1), (corpus.reads.reads.get() - z0).toDouble() / qs.size.coerceAtLeast(1),
            failures, cats.mapValues { it.value[0] to it.value[1] },
        )
    }

    fun indexed(corpus: Corpus, file: File): CountingDb {
        val db = CountingDb(JdbcFtsDb(file))
        val ix = KnowledgeIndex(db)
        ZimIndexer(corpus.source.zim, ix).run("bench", maxArticles = 100_000, budgetBytes = 200_000_000)
        return db
    }
}
