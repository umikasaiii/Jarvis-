package com.simone.jarvismobile.core.knowledge.fts

import com.simone.jarvismobile.core.knowledge.zim.ByteSource
import com.simone.jarvismobile.core.knowledge.zim.FileSource
import com.simone.jarvismobile.core.knowledge.zim.TestDecompressor
import com.simone.jarvismobile.core.knowledge.zim.TestZimBuilder
import com.simone.jarvismobile.core.knowledge.zim.TitleLayout
import com.simone.jarvismobile.core.knowledge.zim.ZimArchive
import com.simone.jarvismobile.core.knowledge.zim.ZimDecompressor
import java.io.File
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.atomic.AtomicLong

/**
 * Benchmark support for the indexing path (JVM). The SAME harness runs against the baseline commit and the optimised code:
 * exact wall times of the read / decompress / SQLite calls come from wrappers around the three interfaces, and the split of
 * the rest (HTML→text, chunking, tokenisation, other) comes from a stack sampler, so no phase number depends on
 * instrumentation that only one version has.
 */
class TimingSource(private val inner: ByteSource) : ByteSource {
    val nanos = AtomicLong(); val calls = AtomicLong(); val bytes = AtomicLong()
    override val size: Long get() = inner.size
    override fun read(offset: Long, length: Int): ByteArray {
        val t = System.nanoTime(); val r = inner.read(offset, length)
        nanos.addAndGet(System.nanoTime() - t); calls.incrementAndGet(); bytes.addAndGet(r.size.toLong()); return r
    }
}

class TimingDecompressor(private val inner: ZimDecompressor) : ZimDecompressor {
    val nanos = AtomicLong(); val calls = AtomicLong(); val inBytes = AtomicLong(); val outBytes = AtomicLong()
    override fun decompress(type: Int, data: ByteArray, maxOutputBytes: Int): ByteArray {
        val t = System.nanoTime(); val r = inner.decompress(type, data, maxOutputBytes)
        nanos.addAndGet(System.nanoTime() - t); calls.incrementAndGet(); inBytes.addAndGet(data.size.toLong()); outBytes.addAndGet(r.size.toLong()); return r
    }
}

class TimingFtsDb(private val inner: FtsDb) : FtsDb {
    val nanos = AtomicLong(); val statements = AtomicLong(); val commits = AtomicLong()
    override fun exec(sql: String, vararg args: Any?) { val t = System.nanoTime(); inner.exec(sql, *args); nanos.addAndGet(System.nanoTime() - t); statements.incrementAndGet() }
    override fun <T> query(sql: String, vararg args: Any?, map: (FtsRow) -> T): List<T> {
        val t = System.nanoTime(); val r = inner.query(sql, *args, map = map); nanos.addAndGet(System.nanoTime() - t); statements.incrementAndGet(); return r
    }
    override fun <T> transaction(body: () -> T): T { commits.incrementAndGet(); return inner.transaction(body) }
}

/** Samples the indexing threads' stacks and attributes each sample to a phase. Statistical: shares, not exact times. */
class StackSampler(private val intervalMs: Long = 4, private val threadFilter: (Thread) -> Boolean) {
    val counts = java.util.concurrent.ConcurrentHashMap<String, AtomicLong>()
    @Volatile private var running = true
    private val th = Thread({
        while (running) {
            for ((t, st) in Thread.getAllStackTraces()) if (t !== Thread.currentThread() && threadFilter(t) && st.isNotEmpty()) counts.computeIfAbsent(classify(st)) { AtomicLong() }.incrementAndGet()
            try { Thread.sleep(intervalMs) } catch (_: InterruptedException) { return@Thread }
        }
    }, "bench-sampler").also { it.isDaemon = true }
    fun start() = th.start()
    fun stop(): Map<String, Long> { running = false; th.interrupt(); th.join(); return counts.mapValues { it.value.get() } }

    private fun classify(st: Array<StackTraceElement>): String {
        // innermost meaningful frame wins
        for (f in st) {
            val c = f.className
            when {
                c.contains("sqlite") || c.contains("JdbcFtsDb") -> return "sqlite"
                c.contains("zstd") || c.contains("Zstd") || c.contains("tukaani") || c.contains("TestDecompressor") -> return "decompress"
                c.contains("FileSource") || c.contains("RandomAccessFile") || c.contains("FileChannel") -> return "read"
                c.contains("ZimHtml") -> return "html"
                c.contains("KnowledgeIndexer") -> return "chunk"
                c.contains("SupportJudge") || c.contains("KnowledgeText") || c.contains("KnowledgeSearch") -> return "tokenize"
            }
        }
        return "other"
    }
}

object SyntheticWiki {
    private val syll = listOf("ba", "ca", "da", "fa", "ga", "la", "ma", "na", "pa", "ra", "sa", "ta", "va", "ze", "re", "te", "ne", "le", "se", "ci", "di", "li", "ni", "ri", "si", "ti", "co", "do", "lo", "mo", "no", "po", "ro", "so", "to", "cu", "du", "lu", "mu", "nu", "tr", "st", "nt", "rt", "sc", "gl")

    private fun vocabulary(rnd: Random, n: Int): List<String> = List(n) { i ->
        val len = 2 + (i % 3) + rnd.nextInt(2)
        buildString { repeat(len) { append(syll[rnd.nextInt(syll.size)]) }; if (rnd.nextInt(5) == 0) append("à") }
    }.distinct()

    /** A modern-layout archive of [articles] realistic Wikipedia-sized pages (≈ 15–25 KB of HTML each), clusters shuffled like mwoffliner's. */
    fun build(file: File, articles: Int, seed: Long = 42, perCluster: Int = 40, sections: Int = 6, refs: Int = 22) {
        val rnd = Random(seed)
        val vocab = vocabulary(rnd, 6000)
        fun word() = vocab[(Math.abs(rnd.nextGaussian()) * 900).toInt().coerceAtMost(vocab.size - 1)]
        fun sentence(n: Int) = (1..n).joinToString(" ") { word() }.replaceFirstChar { it.uppercase() } + "."
        fun para(s: Int) = (1..s).joinToString(" ") { sentence(8 + rnd.nextInt(10)) }
        val b = TestZimBuilder().apply { titleLayout = TitleLayout.MODERN; compression = 5; entriesPerCluster = perCluster; shuffleSeed = seed }
        b.metadata("Title", "Sintetica").metadata("Date", "2026-07-01").metadata("Language", "ita").metadata("Name", "wikipedia_it_synthetic")
        for (i in 0 until articles) {
            val title = "${word().replaceFirstChar { it.uppercase() }} ${word()} $i"
            val html = buildString {
                append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>").append(title).append("</title>")
                repeat(3) { append("<link rel=\"stylesheet\" href=\"../-/s/style$it.css\">") }
                append("<script>var RLCONF={\"wgPageName\":\"").append(title).append("\"};</script></head><body>")
                append("<table class=\"infobox\">"); repeat(14) { append("<tr><th>").append(word()).append("</th><td>").append(word()).append(' ').append(rnd.nextInt(5000)).append("</td></tr>") }; append("</table>")
                append("<h1>").append(title).append("</h1><p>").append(para(5)).append("</p><p>").append(para(3)).append("</p>")
                repeat(sections) { s -> append("<h2>").append(word()).append("</h2>"); repeat(3) { append("<p>").append(para(4)).append("<sup>[").append(s * 3 + it + 1).append("]</sup></p>") } }
                append("<h2>Note</h2><ol>"); repeat(refs) { append("<li><a href=\"#c$it\">").append(sentence(9)).append("</a> <span class=\"ref\">").append(sentence(6)).append("</span></li>") }; append("</ol>")
                append("<table class=\"navbox\"><tr>"); repeat(35) { append("<td><a href=\"./").append(word()).append("\">").append(word()).append("</a></td>") }; append("</tr></table></body></html>")
            }
            b.article("A_%06d".format(i), title, html)
        }
        file.writeBytes(b.build())
    }
}

/** Order-independent fingerprint of what an index holds: (docKey, section, text, lead) of every passage. */
fun fingerprint(db: FtsDb): String {
    val md = MessageDigest.getInstance("SHA-256")
    db.query("SELECT docKey, section, text, lead FROM kx_passage ORDER BY docKey, lead DESC, section, text") { "${it.string(0)}|${it.string(1)}|${it.string(2)}|${it.long(3)}" }
        .forEach { md.update(it.toByteArray()) }
    return md.digest().joinToString("") { "%02x".format(it) }
}
