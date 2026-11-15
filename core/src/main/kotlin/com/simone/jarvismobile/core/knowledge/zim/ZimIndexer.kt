package com.simone.jarvismobile.core.knowledge.zim

import com.simone.jarvismobile.core.knowledge.KnowledgeIndexer
import com.simone.jarvismobile.core.knowledge.fts.IndexDecision
import com.simone.jarvismobile.core.knowledge.fts.IndexGovernor
import com.simone.jarvismobile.core.knowledge.fts.IndexProfiler
import com.simone.jarvismobile.core.knowledge.fts.IndexedSource
import com.simone.jarvismobile.core.knowledge.fts.KnowledgeIndex
import com.simone.jarvismobile.core.knowledge.fts.PassageIn
import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * KNOWLEDGE ENGINE 2.1 — OPT-IN, selective indexing of an archive's article LEADS into the persistent [KnowledgeIndex]. Never
 * automatic and never "the whole archive": the caller sets a space budget and an article cap.
 *
 * Why it is fast (each point was a measured bottleneck of 2.0, see docs/KNOWLEDGE_ENGINE_ZIM.md):
 *  1. CLUSTER ORDER. 2.0 walked the articles in TITLE order; consecutive titles live in unrelated clusters, so almost every
 *     article decompressed a whole cluster (tens of articles) to keep one blob. Now a plan — one sequential pass over the
 *     directory entries, large reads, no strings — groups the articles by cluster and every cluster is decompressed ONCE.
 *  2. PIPELINE. A few worker threads (bounded by the caller/[IndexGovernor]) read+decompress+convert+tokenise clusters while
 *     the calling thread alone writes to SQLite, strictly IN cluster order through a bounded reorder window: memory is bounded
 *     and the checkpoint cursor stays monotone.
 *  3. LEAD ONLY. HTML is converted from a prefix of the page ([ZimHtml.leadText]), not the whole 100 KB page.
 *  4. SQLITE. Checkpoints of ~1500 passages (or 1.5 s) instead of 40 articles, one prepared statement per batch, explicit ids
 *     (no `last_insert_rowid()` per row), progress counted in memory (no queries). Durability settings are untouched.
 *
 * Integrity: every checkpoint (passages + cursor) is ONE transaction; the cursor is the next cluster to process; a crash or a
 * stop loses at most the work since the last checkpoint and a rerun resumes from there. A cursor written by 2.0 (title order)
 * cannot be resumed in this order, so it restarts that archive from scratch (a COMPLETE 2.0 index is kept as is).
 */
class ZimIndexer(
    private val zim: ZimArchive,
    private val index: KnowledgeIndex,
    private val workers: Int = defaultWorkers(),
    val profiler: IndexProfiler = IndexProfiler(),
) {
    data class Progress(val position: Int, val total: Int, val storedPassages: Int, val bytes: Long)

    /** Why the last [run] stopped early on its own (governor), or null. */
    @Volatile var stopReason: String? = null
        private set
    @Volatile var lastArticles: Int = 0
        private set
    @Volatile var lastPassages: Int = 0
        private set

    val sourceKey: String get() = "zim:${zim.uuid}"

    private class Group(val cluster: Int, val entries: IntArray)
    private class Out(val passages: List<PassageIn>, val articles: Int)

    fun run(
        signature: String,
        maxArticles: Int,
        budgetBytes: Long,
        shouldContinue: () -> Boolean = { true },
        onProgress: (Progress) -> Unit = {},
        governor: IndexGovernor = IndexGovernor { IndexDecision(workers) },
        threadFactory: ThreadFactory = ThreadFactory { r -> Thread(r).apply { isDaemon = true } },
        checkpointPassages: Int = CHECKPOINT_PASSAGES,
    ): IndexedSource.State {
        stopReason = null; lastArticles = 0; lastPassages = 0
        profiler.startedAt = System.nanoTime()
        index.profiler = profiler
        try {
            val title = zim.metadata("Title") ?: "ZIM"
            val date = zim.metadata("Date").orEmpty()
            val lexical = ZimKnowledgeSource.isLexical(zim)
            var needed = index.begin(sourceKey, "zim", title, title, date, signature, lexical)
            if (!needed) return index.source(sourceKey)?.state ?: IndexedSource.State.COMPLETE
            var cursor = index.cursor(sourceKey)
            if (cursor.isNotEmpty() && !cursor.startsWith("c:")) { // a 2.0 cursor (title order): not resumable in cluster order
                index.removeSource(sourceKey)
                index.begin(sourceKey, "zim", title, title, date, signature, lexical)
                cursor = ""
            }
            val startCluster = cursor.removePrefix("c:").toIntOrNull() ?: 0

            val groups = profiler.time(IndexProfiler.Phase.PLAN) { plan(startCluster, maxArticles) }
            val totalPlanned = groups.sumOf { it.entries.size }
            val capped = totalPlanned < planTotalArticles // the article cap, not the end of the archive, limits this run
            lastArticles = 0
            var articlesDone = 0
            var passagesStored = 0
            var usedBytes = index.totalBytes()
            var state: IndexedSource.State? = null

            val stop = AtomicBoolean(false)
            val failure = AtomicReference<Throwable?>(null)
            val results = ConcurrentHashMap<Int, Out>()
            val window = Semaphore(workers * 2 + 2)
            val next = AtomicInteger(0)
            val allowed = AtomicInteger(workers)
            val pauseMs = java.util.concurrent.atomic.AtomicLong(0)
            val lock = Object()

            val pool = ArrayList<Thread>()
            for (w in 0 until workers) {
                val th = threadFactory.newThread {
                    try {
                        val timing = ZimArchive.ClusterTiming()
                        while (!stop.get()) {
                            if (w >= allowed.get()) { Thread.sleep(100); continue } // throttled: this worker yields entirely
                            window.acquire()
                            val gi = next.getAndIncrement()
                            if (gi >= groups.size || stop.get()) { window.release(); break }
                            val out = processGroup(groups[gi], timing)
                            val pm = pauseMs.get()
                            if (pm > 0) { val t = System.nanoTime(); Thread.sleep(pm); profiler.add(IndexProfiler.Phase.THROTTLE, System.nanoTime() - t) }
                            results[gi] = out
                            synchronized(lock) { lock.notifyAll() }
                        }
                    } catch (_: InterruptedException) {
                    } catch (t: Throwable) {
                        failure.compareAndSet(null, t); stop.set(true); synchronized(lock) { lock.notifyAll() }
                    }
                }
                th.name = "kx-index-$w"
                pool += th
            }
            pool.forEach { it.start() }

            val batch = ArrayList<PassageIn>()
            var lastCluster = startCluster - 1
            var lastCheckpoint = System.nanoTime()
            fun checkpoint(): Boolean {
                if (batch.isEmpty() && lastCluster < startCluster) return true
                val stored = index.addBatch(sourceKey, batch, "c:${lastCluster + 1}", budgetBytes)
                passagesStored += stored
                val full = stored < batch.size
                batch.clear()
                lastCheckpoint = System.nanoTime()
                profiler.time(IndexProfiler.Phase.PROGRESS) {
                    usedBytes = index.totalBytes()
                    onProgress(Progress(articlesDone, totalPlanned, passagesStored, usedBytes))
                    val d = governor.decide()
                    allowed.set(d.workers.coerceIn(1, workers)); pauseMs.set(d.pauseMillis)
                    if (d.stopReason != null) stopReason = d.stopReason
                }
                if (full) state = IndexedSource.State.PARTIAL_BUDGET
                return !full && stopReason == null
            }

            var expected = 0
            try {
                while (expected < groups.size && state == null) {
                    if (!shouldContinue()) { state = IndexedSource.State.INDEXING; break }
                    var out = results.remove(expected)
                    while (out == null) {
                        if (failure.get() != null) throw failure.get()!!
                        if (!shouldContinue()) { state = IndexedSource.State.INDEXING; break }
                        synchronized(lock) { out = results.remove(expected); if (out == null) lock.wait(100) }
                    }
                    if (state != null) break
                    window.release()
                    batch += out!!.passages
                    articlesDone += out!!.articles
                    lastCluster = groups[expected].cluster
                    expected++
                    val due = batch.size >= checkpointPassages || System.nanoTime() - lastCheckpoint >= CHECKPOINT_NANOS
                    if (due && !checkpoint()) { state = if (state == IndexedSource.State.PARTIAL_BUDGET) state else IndexedSource.State.INDEXING; break }
                }
                if (state == null || state == IndexedSource.State.PARTIAL_BUDGET) { /* flush what is pending below */ }
            } finally {
                stop.set(true)
                pool.forEach { it.interrupt() }
                pool.forEach { it.join(10_000) }
            }
            failure.get()?.let { throw it }

            lastArticles = articlesDone
            if (state == IndexedSource.State.INDEXING) {
                // stopped by the caller or the governor: keep everything fully processed so far
                if (batch.isNotEmpty()) { val s = index.addBatch(sourceKey, batch, "c:${lastCluster + 1}", budgetBytes); passagesStored += s }
                lastPassages = passagesStored
                return IndexedSource.State.INDEXING
            }
            if (state == IndexedSource.State.PARTIAL_BUDGET) { lastPassages = passagesStored; return state!! }
            if (batch.isNotEmpty() || lastCluster >= startCluster) {
                val s = index.addBatch(sourceKey, batch, "c:${lastCluster + 1}", budgetBytes)
                passagesStored += s
                if (s < batch.size) { lastPassages = passagesStored; return IndexedSource.State.PARTIAL_BUDGET }
            }
            profiler.time(IndexProfiler.Phase.PROGRESS) { onProgress(Progress(articlesDone, totalPlanned, passagesStored, index.totalBytes())) }
            lastPassages = passagesStored
            if (capped) { index.markPartial(sourceKey); return IndexedSource.State.PARTIAL_BUDGET }
            index.finish(sourceKey)
            return IndexedSource.State.COMPLETE
        } finally {
            index.profiler = null
        }
    }

    // ---- plan --------------------------------------------------------------------------------------------------

    private var planTotalArticles = 0

    /**
     * Articles of the archive grouped by cluster, clusters ascending, starting at [startCluster], truncated to [maxArticles]
     * (counted in cluster order). One sequential pass over the title listing (a bitset of real articles) and over the directory.
     */
    private fun plan(startCluster: Int, maxArticles: Int): List<Group> {
        val html = BooleanArray(zim.mimeTypes.size) { zim.mimeTypes[it].startsWith("text/html") }
        val ns = zim.articleNamespace
        val front: BitSet? = if (zim.titleListIsArticlesOnly) BitSet(zim.entryCount).also { bs -> zim.forEachTitleEntryIndex { buf, n -> for (k in 0 until n) if (buf[k] in 0 until zim.entryCount) bs.set(buf[k]) } } else null
        var cl = IntArray(1024); var en = IntArray(1024); var n = 0
        zim.scanEntries { idx, entryNs, redirect, mime, cluster, _ ->
            if (redirect || entryNs != ns || mime !in html.indices || !html[mime] || cluster < 0) return@scanEntries
            if (front != null && !front.get(idx)) return@scanEntries
            if (n == cl.size) { cl = cl.copyOf(n * 2); en = en.copyOf(n * 2) }
            cl[n] = cluster; en[n] = idx; n++
        }
        val order = LongArray(n) { (cl[it].toLong() shl 32) or it.toLong() }
        order.sort()
        val groups = ArrayList<Group>()
        var taken = 0
        var available = 0
        var i = 0
        while (i < n) {
            val c = (order[i] ushr 32).toInt()
            var j = i
            while (j < n && (order[j] ushr 32).toInt() == c) j++
            if (c >= startCluster) {
                available += j - i
                val take = minOf(j - i, maxArticles - taken)
                if (take > 0) {
                    val base = i
                    groups += Group(c, IntArray(take) { en[(order[base + it] and 0xFFFFFFFFL).toInt()] })
                    taken += take
                }
            }
            i = j
        }
        planTotalArticles = available
        return groups
    }

    // ---- work (runs on a worker thread) -----------------------------------------------------------------------

    private fun processGroup(g: Group, timing: ZimArchive.ClusterTiming): Out {
        val before = longArrayOf(timing.readNanos, timing.decompressNanos)
        val cb = zim.readClusterOnce(g.cluster, timing)
        profiler.add(IndexProfiler.Phase.READ, timing.readNanos - before[0])
        profiler.add(IndexProfiler.Phase.DECOMPRESS, timing.decompressNanos - before[1])
        val out = ArrayList<PassageIn>(g.entries.size * 2)
        for (idx in g.entries) {
            val t0 = System.nanoTime()
            val e = try { zim.entry(idx) } catch (_: ZimFormatException) { null }
            profiler.add(IndexProfiler.Phase.READ, System.nanoTime() - t0)
            if (e == null || e.isRedirect || e.clusterNumber != g.cluster) continue
            val r = cb.range(e.blobNumber)
            if (r < 0) continue
            val start = (r ushr 32).toInt(); val end = (r and 0xFFFFFFFFL).toInt()
            val t1 = System.nanoTime()
            val len = minOf(end - start, MAX_HTML_BYTES)
            val lead = ZimHtml.leadText(NEEDED_CHARS, len) { n -> String(cb.data, start, minOf(n, len), Charsets.UTF_8) }
            if (lead.text.isBlank()) { profiler.add(IndexProfiler.Phase.EXTRACT, System.nanoTime() - t1); continue }
            val title = e.displayTitle.ifBlank { lead.title.orEmpty() }
            val docKey = "zim:${zim.uuid}/${e.path}"
            val chunks = KnowledgeIndexer.chunk(docKey, title, lead.text).take(LEAD_CHUNKS)
            profiler.add(IndexProfiler.Phase.EXTRACT, System.nanoTime() - t1)
            val t2 = System.nanoTime()
            chunks.forEachIndexed { i, c -> out += PassageIn(docKey, title, c.section, c.text, lead = i == 0, stems = PassageIn.stemsOf(title, c.section, c.text)) }
            profiler.add(IndexProfiler.Phase.TOKENIZE, System.nanoTime() - t2)
        }
        profiler.count("clusters")
        return Out(out, g.entries.size)
    }

    companion object {
        fun defaultWorkers(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 3)
        const val CHECKPOINT_PASSAGES = 1500
        const val CHECKPOINT_NANOS = 1_500_000_000L
        private const val LEAD_CHUNKS = 2
        private const val NEEDED_CHARS = 3600
        private const val MAX_HTML_BYTES = 300_000
    }
}
