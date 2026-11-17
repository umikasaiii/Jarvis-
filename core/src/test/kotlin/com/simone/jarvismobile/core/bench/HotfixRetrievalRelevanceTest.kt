package com.simone.jarvismobile.core.bench

import com.simone.jarvismobile.core.knowledge.Evidence
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Hotfix post-M4 / H2 — device report: "È vero che l'insulina viene prodotta dal fegato?" listed an article on heated tobacco products, "Quale organo produce
 * l'insulina…" listed "Organo (anatomia)", a Python question listed "Variable Length Subnet Masking". Real production retrieval (synthetic ZIM + FTS4 + gatherer)
 * over synthetic articles; the examples are independent of each other (no insulin-specific code exists).
 */
class HotfixRetrievalRelevanceTest {
    private fun art(id: String, title: String, lead: String, vararg secs: Pair<String, String>) =
        FixtureArticle(id, title, "Fixture Med", "2026-01-01", listOf(FixtureSection("", lead)) + secs.map { FixtureSection(it.first, it.second) })

    private val fx = BenchFixtures("h2", articles = listOf(
        art("a.organo", "Organo (anatomia)", "In anatomia un organo è una struttura composta da più tessuti che svolge una funzione specifica."),
        art("a.tabacco", "Prodotto a tabacco riscaldato non bruciato", "Un prodotto a tabacco riscaldato è un prodotto che riscalda il tabacco senza bruciarlo."),
        art("a.fegato", "Fegato", "Il fegato è il più grande organo ghiandolare del corpo umano. Produce la bile.", "Funzioni" to "Il fegato produce bile e proteine plasmatiche."),
        art("a.pancreas", "Pancreas", "Il pancreas è una ghiandola che produce insulina e glucagone.", "Funzione endocrina" to "Le cellule beta del pancreas producono l'insulina."),
        art("a.vlsm", "Variable Length Subnet Masking", "Il VLSM è una tecnica che suddivide una rete in sottoreti di dimensioni diverse."),
        art("a.tiroide", "Tiroide", "La tiroide è una ghiandola endocrina del collo che produce gli ormoni tiroxina e triiodotironina."),
        art("a.prodotto", "Prodotto interno lordo", "Il prodotto interno lordo misura il valore dei beni e dei servizi finali prodotti da un paese."),
        art("a.struttura", "Struttura dati", "Una struttura dati è un modo di organizzare i dati in un programma."),
    ))
    private val h = BenchHarness(fx)

    private fun ask(q: String) = runBlocking {
        val c = BenchCase(id = "x", category = "c", turns = listOf(q), source = "s", sourceVersion = "1", criterion = "KNOWLEDGE_ANSWER", split = "DEV")
        h.retrieve()(c).let { r -> r.evidence to ((r.evidence as? Evidence.Grounded)?.passages.orEmpty().map { r.passageId(it) } + ((r.evidence as? Evidence.Insufficient)?.consulted.orEmpty().map { it.sourceId })) }
    }

    @Test fun aFrameWordSharedWithATitleIsNotARelevantSource() {
        val (_, ids) = ask("È vero che l'insulina viene prodotta dal fegato?")
        assertTrue(ids.none { "tabacco" in it }, "tobacco article offered: $ids")
        // the question is not SUPPORTED by the liver article (it says nothing about insulin): at best it is a related candidate
        val (ev, _) = ask("È vero che l'insulina viene prodotta dal fegato?")
        assertTrue(ev !is Evidence.Grounded || !ev.supportEstablished, "an unsupported claim was treated as established")
    }

    @Test fun theAnswerBearingArticleIsFoundAndTheGenericCategoryArticleIsNot() {
        val (ev, ids) = ask("Quale organo produce l'insulina e quale funzione svolge?")
        assertTrue(ev is Evidence.Grounded && ev.supportEstablished, "no support: $ev")
        assertTrue(ids.any { "pancreas" in it }, "the pancreas passage was not retrieved: $ids")
        assertTrue(ids.none { "organo" in it }, "the generic 'Organo' article was used: $ids")
    }

    @Test fun anotherQuestionOfTheSameShapeBehavesTheSame() {
        val (ev, ids) = ask("Quale organo produce la tiroxina?")
        assertTrue(ev is Evidence.Grounded && ev.supportEstablished && ids.any { "tiroide" in it }, "$ev $ids")
        assertTrue(ids.none { "organo" in it || "prodotto" in it }, "$ids")
    }

    @Test fun aGenericWordAloneDoesNotDragInLookAlikeArticles() {
        val (_, ids) = ask("Che cosa produce un paese di vino bianco?")
        assertTrue(ids.none { "tabacco" in it }, "$ids")
    }

    @Test fun aQuestionMadeOnlyOfFrameWordsStillWorks() {
        val (ev, ids) = ask("Quale funzione svolge il fegato?")
        assertTrue(ev is Evidence.Grounded && ids.any { "fegato" in it }, "$ev $ids")
    }

    @Test fun aFuzzyTitleGuessIsNotListedAsAConsultedSource() {
        val (_, ids) = ask("Spiegami cos'è una variabile in Python, con un esempio pratico.")
        assertEquals(emptyList(), ids.filter { "vlsm" in it }, "the unrelated networking article was listed: $ids")
    }
}
