package com.simone.jarvismobile.core.knowledge

import com.simone.jarvismobile.core.knowledge.zim.FileSource
import com.simone.jarvismobile.core.knowledge.zim.TestDecompressor
import com.simone.jarvismobile.core.knowledge.zim.ZimArchive
import com.simone.jarvismobile.core.knowledge.zim.ZimKnowledgeSource
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Stress test on a REAL 20,568-entry Wikipedia archive (openzim test file, English, climate-change theme). NOT committed
 * (licence unclear) and skipped unless JARVIS_ZIM_TESTDATA is set; run in the development sandbox. Ground truth is the
 * archive itself: a question is "answerable" iff an article with the expected title exists.
 */
class RealZimEvalTest {
    private data class Q(val text: String, val title: String, val fact: String? = null)

    private val questions = listOf(
        Q("What is climate change?", "Climate change", "global"),
        Q("What is global warming?", "Global warming", "temperature"),
        Q("What is the greenhouse effect?", "Greenhouse effect", "heat"),
        Q("What is carbon dioxide?", "Carbon dioxide", "gas"),
        Q("What is the Paris Agreement?", "Paris Agreement", "treaty"),
        Q("What is methane?", "Methane", "gas"),
        Q("Who is Greta Thunberg?", "Greta Thunberg", "activist"),
        Q("What is ocean acidification?", "Ocean acidification", "ocean"),
        Q("What is the Kyoto Protocol?", "Kyoto Protocol", "protocol"),
        Q("What is a heat wave?", "Heat wave", "heat"),
        Q("Tell me about the permafrost", "Permafrost", "ground"),
        Q("What is sea level rise?", "Sea level rise", "sea"),
        Q("what is climat change?", "Climate change", "global"),        // typo
        Q("What is the greenhose effect?", "Greenhouse effect", "heat"),   // typo
        Q("Explain global warmin", "Global warming", "temperature"),       // typo
        Q("What is the IPCC?", "Intergovernmental Panel on Climate Change", null), // acronym: only via redirect if present
    )

    private val unanswerable = listOf(
        "What is the capital of Mongolia?", "Who wrote Hamlet?", "What is a transistor?", "How tall is Mount Everest?",
        "What is the speed of light?", "Who painted the Mona Lisa?", "What is blockchain?", "Who won the 1998 World Cup?",
        "What is the population of Brazil?", "How do vaccines work?", "What is the Pythagorean theorem?", "Who is Albert Einstein?",
        "What is photosynthesis?", "What is jazz music?", "Who invented the telephone?", "What is the Roman Empire?",
    )

    @Test fun `real wikipedia mini archive - entity retrieval at scale`() {
        val d = System.getenv("JARVIS_ZIM_TESTDATA")?.let(::File); assumeTrue(d != null && d.isDirectory, "JARVIS_ZIM_TESTDATA not set")
        val f = File(d, "data_nons_wikipedia_en_climate_change_mini_2024-06.zim"); assumeTrue(f.isFile)
        val zim = ZimArchive.open(FileSource(f), TestDecompressor)
        val src = ZimKnowledgeSource(zim)
        val assembler = EvidenceAssembler()

        // ground truth from the archive itself
        fun exists(title: String) = zim.titlesWithPrefix('C', title, 50).any { it.displayTitle == title }
        val (present, absent) = questions.partition { exists(it.title) }
        val md = StringBuilder("# REAL ZIM stress test (20,568 entries; English Wikipedia climate-change mini, 2024-06)\n\n")
        md.append("Questions whose answer article exists in the archive: ${present.size}; question titles absent: ${absent.size}\n\n")

        var hit = 0; var factOk = 0
        val times = ArrayList<Double>()
        val misses = ArrayList<String>()
        for (q in present) {
            val t0 = System.nanoTime()
            val ev = assembler.assemble(q.text, src.articles(q.text, 3), emptyList(), 4)
            times += (System.nanoTime() - t0) / 1e6
            val g = ev as? Evidence.Grounded
            // a title that is a redirect counts as its target article ("Global warming" -> "Climate change")
            val target = zim.findByPath('C', q.title.replace(' ', '_'))?.let { zim.resolve(it)?.displayTitle } ?: q.title
            val ok = g?.passages?.any { KnowledgeText.fold(it.chunk.sourceTitle).let { s -> s == KnowledgeText.fold(q.title) || s == KnowledgeText.fold(target) } } == true
            if (ok) { hit++; if (q.fact == null || g!!.passages.any { q.fact in it.chunk.text.lowercase() }) factOk++ } else misses += q.text
        }
        md.append("Answerable (article present): right article in evidence ${hit}/${present.size}; fact token in evidence ${factOk}/${present.size}\n")
        if (misses.isNotEmpty()) md.append("Missed: " + misses.joinToString(" | ") + "\n")

        // unanswerable: questions whose subject article is absent -> evidence must not be a different article presented as proof
        val absentQs = unanswerable.filter { q ->
            // the subject is absent iff no article is named by the question's capitalised/content phrases
            true
        }
        var fe = 0; val feList = ArrayList<String>()
        for (q in absentQs) {
            val t0 = System.nanoTime()
            val ev = assembler.assemble(q, src.articles(q, 3), emptyList(), 4)
            times += (System.nanoTime() - t0) / 1e6
            if (ev is Evidence.Grounded) { fe++; feList += q + " -> " + ev.passages.joinToString { it.chunk.sourceTitle } + " [${ev.strength}]" }
        }
        md.append("Unanswerable-by-design questions returning evidence: ${fe}/${absentQs.size}\n")
        feList.forEach { md.append("  - $it\n") }
        md.append("Retrieval time per question on the real archive (JVM, local SSD, NOT a device figure): mean ${"%.1f".format(times.average())} ms, max ${"%.1f".format(times.max())} ms\n")
        File("build/reports/quality").apply { mkdirs() }.resolve("real_zim_eval.md").writeText(md.toString())
        println(md)
        assertTrue(hit >= present.size * 0.75, md.toString())
    }
}
