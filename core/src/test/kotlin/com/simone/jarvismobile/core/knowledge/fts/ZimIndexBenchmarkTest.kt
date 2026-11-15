package com.simone.jarvismobile.core.knowledge.fts

import com.simone.jarvismobile.core.knowledge.zim.FileSource
import com.simone.jarvismobile.core.knowledge.zim.TestDecompressor
import com.simone.jarvismobile.core.knowledge.zim.ZimArchive
import com.simone.jarvismobile.core.knowledge.zim.ZimIndexer
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test

/**
 * INDEXING BENCHMARK (JVM). Runs only when JARVIS_BENCH names an output directory. The same file is executed on the baseline
 * commit and on the optimised code (JARVIS_BENCH_LABEL = before | after), with the same archives, budget and article cap. JVM
 * figures: they say nothing absolute about the Honor 200 (different CPU, flash and SQLite build).
 */
class ZimIndexBenchmarkTest {
    private val out = System.getenv("JARVIS_BENCH")?.let(::File)
    private val label = System.getenv("JARVIS_BENCH_LABEL") ?: "run"

    private fun archives(): List<Pair<String, File>> {
        val list = ArrayList<Pair<String, File>>()
        val synth = File(System.getenv("JARVIS_BENCH_CACHE") ?: out!!.path, "synthetic_${System.getenv("JARVIS_BENCH_ARTICLES") ?: "5000"}.zim")
        if (!synth.isFile) { synth.parentFile.mkdirs(); SyntheticWiki.build(synth, (System.getenv("JARVIS_BENCH_ARTICLES") ?: "5000").toInt()) }
        list += "synthetic" to synth
        // long pages (≈ 90 KB of HTML, like real Wikipedia articles): where reading only the lead pays most
        val long = File(synth.parentFile, "synthetic_long_${System.getenv("JARVIS_BENCH_LONG") ?: "1200"}.zim")
        if (!long.isFile) SyntheticWiki.build(long, (System.getenv("JARVIS_BENCH_LONG") ?: "1200").toInt(), seed = 7, perCluster = 25, sections = 30, refs = 120)
        list += "synthetic_long" to long
        System.getenv("JARVIS_ZIM_TESTDATA")?.let { File(it, "data_nons_wikipedia_en_climate_change_mini_2024-06.zim") }?.takeIf { it.isFile }?.let { list += "real_wikipedia_mini" to it }
        return list
    }

    @Test fun benchmark() {
        assumeTrue(out != null, "JARVIS_BENCH not set"); out!!.mkdirs()
        val runs = (System.getenv("JARVIS_BENCH_RUNS") ?: "2").toInt()
        for ((name, file) in archives()) for (run in 1..runs) {
            val dbFile = File.createTempFile("kxbench", ".db").also { it.deleteOnExit() }
            val timingSrc = TimingSource(FileSource(file)); val timingDec = TimingDecompressor(TestDecompressor)
            val zim = ZimArchive.open(timingSrc, timingDec)
            val timingDb = TimingFtsDb(JdbcFtsDb(dbFile))
            val index = KnowledgeIndex(timingDb)
            System.gc(); Thread.sleep(200)
            val base = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
            var peak = base
            val heap = Thread({ try { while (true) { val u = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }; if (u > peak) peak = u; Thread.sleep(25) } } catch (_: InterruptedException) {} }, "bench-heap").also { it.isDaemon = true; it.start() }
            val sampler = StackSampler { it.name.startsWith("Test worker") || it.name.startsWith("kx-") }.also { it.start() }
            val osBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
            val cpu0 = osBean.processCpuTime
            val t0 = System.nanoTime()
            val state = ZimIndexer(zim, index).run(signature = "bench", maxArticles = Int.MAX_VALUE, budgetBytes = 2_000_000_000L)
            val wall = (System.nanoTime() - t0) / 1e6
            val cpuMs = (osBean.processCpuTime - cpu0) / 1e6
            val phases = sampler.stop(); heap.interrupt()
            val passages = index.passageCount()
            val total = phases.values.sum().coerceAtLeast(1)
            val sb = StringBuilder()
            sb.appendLine("label=$label archive=$name run=$run state=$state")
            sb.appendLine("entries=${zim.entryCount} titleOrder=${zim.titleOrderCount} passages=$passages")
            sb.appendLine("wall_ms=%.0f articles_per_s=%.1f passages_per_s=%.1f".format(wall, zim.titleOrderCount / (wall / 1000), passages / (wall / 1000)))
            sb.appendLine("read_ms=%.0f read_calls=%d read_MB=%.1f".format(timingSrc.nanos.get() / 1e6, timingSrc.calls.get(), timingSrc.bytes.get() / 1048576.0))
            sb.appendLine("decompress_ms=%.0f decompress_calls=%d in_MB=%.1f out_MB=%.1f".format(timingDec.nanos.get() / 1e6, timingDec.calls.get(), timingDec.inBytes.get() / 1048576.0, timingDec.outBytes.get() / 1048576.0))
            sb.appendLine("sqlite_ms=%.0f statements=%d commits=%d".format(timingDb.nanos.get() / 1e6, timingDb.statements.get(), timingDb.commits.get()))
            sb.appendLine("sampled_share=" + phases.entries.sortedByDescending { it.value }.joinToString(",") { "${it.key}:%.0f%%".format(100.0 * it.value / total) })
            sb.appendLine("cpu_ms=%.0f cpu_per_wall=%.2f".format(cpuMs, cpuMs / wall))
            sb.appendLine("heap_peak_delta_MB=%.0f db_MB=%.1f".format((peak - base) / 1048576.0, dbFile.length() / 1048576.0))
            sb.appendLine("fingerprint=${fingerprint(JdbcFtsDb(dbFile))}")
            File(out, "${label}_${name}_run$run.txt").writeText(sb.toString())
            println("BENCH\n$sb")
        }
    }
}
