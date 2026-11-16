package com.simone.jarvismobile.core.knowledge.fts

import com.simone.jarvismobile.core.knowledge.KnowledgeChunk
import com.simone.jarvismobile.core.knowledge.KnowledgeSearch
import com.simone.jarvismobile.core.knowledge.KnowledgeText
import com.simone.jarvismobile.core.knowledge.RetrievalOptions
import com.simone.jarvismobile.core.knowledge.SupportJudge

/** The few SQLite operations the index needs — implemented over `SQLiteDatabase` on Android and JDBC in the JVM tests. */
interface FtsRow {
    fun string(i: Int): String
    fun long(i: Int): Long
    /** Raw bytes of a BLOB column (FTS4 `matchinfo`). Implementations that cannot provide it throw, and the index falls back to the legacy candidate selection. */
    fun blob(i: Int): ByteArray = throw UnsupportedOperationException("blob")
}
interface FtsDb {
    fun exec(sql: String, vararg args: Any?)
    fun <T> query(sql: String, vararg args: Any?, map: (FtsRow) -> T): List<T>
    fun <T> transaction(body: () -> T): T

    /** Runs [sql] once per row of [rows] with ONE prepared statement (compiled once, bound and executed per row). */
    fun execMany(sql: String, rows: List<Array<Any?>>) { for (r in rows) exec(sql, *r) }
}

/** One text document or archive known to the index. */
data class IndexedSource(
    val key: String, val kind: String, val title: String, val collection: String, val date: String,
    val signature: String, val state: State, val cursor: String, val passages: Int, val bytes: Long,
) {
    enum class State {
        /** Interrupted or running: resumable from [cursor] while the signature is unchanged. */
        INDEXING,
        /** Everything the source offers was indexed. */
        COMPLETE,
        /** Stopped by a limit (space budget or article cap); what is stored stays usable. */
        PARTIAL_BUDGET,
    }
}

/** A passage to store. [docKey] identifies the document inside the source (a file path, "zim:<uuid>/<path>"). */
data class PassageIn(
    val docKey: String, val title: String, val section: String, val text: String, val lead: Boolean,
    /** Space-joined search stems of title+section+text. Workers precompute it in parallel; null = computed by the index. */
    val stems: String? = null,
) {
    companion object {
        fun stemsOf(title: String, section: String, text: String): String = SupportJudge.stems("$title $section $text").joinToString(" ")
    }
}

/**
 * KNOWLEDGE ENGINE 2.0 — the persistent, incremental full-text index (SQLite FTS4) over the textual knowledge the user chose
 * to index. It is a REBUILDABLE CACHE: the files and archives remain the source of truth, nothing personal is stored here
 * (personal memory and imported documents have their own stores and are never copied in), and a schema change simply drops
 * and refills this cache — it never touches user data.
 *
 *  - selective: only what a caller adds (library files; the LEAD of each article of an archive the user opted to index);
 *  - bounded: [addBatch] refuses to cross the byte budget and marks the source PARTIAL_BUDGET;
 *  - incremental: [begin] compares a source signature (size/date/version) — unchanged sources are skipped, changed ones replaced;
 *  - interruption-safe: every batch and its cursor are committed in one transaction, so a crash resumes from the last batch;
 *  - searchable beyond titles: terms are stemmed like the retrieval layer, typos are repaired against the index's own
 *    vocabulary (fts4aux), and candidates are re-ranked by the same [KnowledgeSearch] used everywhere else.
 * Search returns CANDIDATE passages only: whether they are sufficient evidence is decided downstream by the same
 * [com.simone.jarvismobile.core.knowledge.EvidenceAssembler] / [SupportJudge] — the index proves nothing by itself.
 */
class KnowledgeIndex(private val db: FtsDb, var profiler: IndexProfiler? = null, var options: RetrievalOptions = RetrievalOptions()) {
    init { ensureSchema() }

    /** Next free passage id (single writer: [addBatch] is serialised). Avoids a `last_insert_rowid()` query per row. */
    private var nextPassageId = -1L

    private fun ensureSchema() {
        val have = runCatching { db.query("SELECT value FROM kx_meta WHERE key='schema'") { it.string(0) }.firstOrNull() }.getOrNull()
        if (have != SCHEMA) {
            // derived cache only: dropping it loses no user data, the sources are re-read on demand
            for (t in listOf("kx_vocab", "kx_fts", "kx_passage", "kx_source", "kx_meta")) runCatching { db.exec("DROP TABLE IF EXISTS $t") }
            db.exec("CREATE TABLE kx_meta(key TEXT PRIMARY KEY, value TEXT)")
            db.exec("CREATE TABLE kx_source(id INTEGER PRIMARY KEY, skey TEXT UNIQUE NOT NULL, kind TEXT, title TEXT, collection TEXT, date TEXT, signature TEXT, cursor TEXT, state TEXT, passages INTEGER DEFAULT 0, bytes INTEGER DEFAULT 0, lexical INTEGER DEFAULT 0)")
            db.exec("CREATE TABLE kx_passage(id INTEGER PRIMARY KEY, sourceId INTEGER NOT NULL, docKey TEXT, title TEXT, section TEXT, text TEXT, lead INTEGER)")
            db.exec("CREATE INDEX kx_passage_source ON kx_passage(sourceId)")
            db.exec("CREATE INDEX kx_passage_doc ON kx_passage(sourceId, docKey)")
            db.exec("CREATE VIRTUAL TABLE kx_fts USING fts4(stems, tokenize=unicode61)")
            db.exec("CREATE VIRTUAL TABLE kx_vocab USING fts4aux(kx_fts)")
            db.exec("INSERT INTO kx_meta(key, value) VALUES('schema', ?)", SCHEMA)
        }
    }

    // ---- sources ---------------------------------------------------------------------------------------------

    fun sources(): List<IndexedSource> = db.query("SELECT skey, kind, title, collection, date, signature, cursor, state, passages, bytes FROM kx_source ORDER BY skey") {
        IndexedSource(it.string(0), it.string(1), it.string(2), it.string(3), it.string(4), it.string(5), stateOf(it.string(7)), it.string(6), it.long(8).toInt(), it.long(9))
    }

    fun source(key: String): IndexedSource? = sources().firstOrNull { it.key == key }

    fun totalBytes(): Long = db.query("SELECT COALESCE(SUM(bytes),0) FROM kx_source") { it.long(0) }.firstOrNull() ?: 0L

    fun passageCount(): Int = db.query("SELECT COUNT(*) FROM kx_passage") { it.long(0).toInt() }.firstOrNull() ?: 0

    /**
     * Declares a source about to be indexed. Returns false when it is already COMPLETE (or budget-stopped) with the SAME
     * signature — nothing to do; true when indexing must run (new, changed, or interrupted: then [cursor] says where to resume).
     */
    fun begin(key: String, kind: String, title: String, collection: String, date: String, signature: String, lexical: Boolean = false): Boolean = db.transaction {
        val cur = source(key)
        when {
            cur == null -> { insertSource(key, kind, title, collection, date, signature, lexical); true }
            cur.signature != signature -> { removeSourceRows(key); insertSource(key, kind, title, collection, date, signature, lexical); true }
            cur.state == IndexedSource.State.INDEXING -> true
            else -> false
        }
    }

    private fun insertSource(key: String, kind: String, title: String, collection: String, date: String, signature: String, lexical: Boolean) =
        db.exec("INSERT INTO kx_source(skey, kind, title, collection, date, signature, cursor, state, lexical) VALUES(?,?,?,?,?,?,?,?,?)", key, kind, title, collection, date, signature, "", IndexedSource.State.INDEXING.name, if (lexical) 1 else 0)

    fun cursor(key: String): String = source(key)?.cursor.orEmpty()

    /**
     * Stores [passages] and the new [cursor] atomically. Returns how many were stored; fewer than given (or 0) means the
     * budget was reached — the source is then marked PARTIAL_BUDGET and the caller must stop.
     */
    fun addBatch(key: String, passages: List<PassageIn>, cursor: String, budgetBytes: Long): Int = synchronized(this) {
        val wall0 = System.nanoTime()
        var tokenizeNs = 0L
        val stored = db.transaction {
            val sid = sourceId(key) ?: return@transaction 0
            var used = totalBytes()
            // tokenisation outside SQLite's critical path when the caller did not precompute it
            val prof = profiler
            val rows = ArrayList<PassageIn>(passages.size)
            val stems = ArrayList<String>(passages.size)
            val sizes = ArrayList<Long>(passages.size)
            var stored = 0
            var added = 0L
            var budgetHit = false
            for (p in passages) {
                val st = p.stems ?: run {
                    val t = System.nanoTime(); val s = PassageIn.stemsOf(p.title, p.section, p.text); tokenizeNs += System.nanoTime() - t; s
                }
                val size = cost(p.text.length + p.title.length + p.section.length, st.length)
                if (used + size > budgetBytes) { budgetHit = true; break }
                rows += p; stems += st; sizes += size; used += size; added += size; stored++
            }
            if (rows.isNotEmpty()) {
                if (nextPassageId < 0) nextPassageId = (db.query("SELECT COALESCE(MAX(id),0) FROM kx_passage") { it.long(0) }.first()) + 1
                val first = nextPassageId
                nextPassageId += rows.size
                db.execMany(
                    "INSERT INTO kx_passage(id, sourceId, docKey, title, section, text, lead) VALUES(?,?,?,?,?,?,?)",
                    rows.mapIndexed { i, p -> arrayOf<Any?>(first + i, sid, p.docKey, p.title, p.section, p.text, if (p.lead) 1 else 0) },
                )
                db.execMany("INSERT INTO kx_fts(docid, stems) VALUES(?,?)", stems.mapIndexed { i, s -> arrayOf<Any?>(first + i, s) })
            }
            if (budgetHit) db.exec("UPDATE kx_source SET state=? WHERE id=?", IndexedSource.State.PARTIAL_BUDGET.name, sid)
            db.exec("UPDATE kx_source SET passages = passages + ?, bytes = bytes + ?, cursor = ? WHERE id = ?", stored, added, cursor, sid)
            stored
        }
        // SQLITE = the whole transaction (statements AND the durable commit), minus the tokenisation done inside it
        profiler?.let { it.add(IndexProfiler.Phase.TOKENIZE, tokenizeNs); it.add(IndexProfiler.Phase.SQLITE, System.nanoTime() - wall0 - tokenizeNs) }
        stored
    }

    /** Marks a source as stopped by a limit (space or article cap): usable, but not everything was indexed. */
    fun markPartial(key: String) = db.exec("UPDATE kx_source SET state=? WHERE skey=?", IndexedSource.State.PARTIAL_BUDGET.name, key)

    /** Marks a source finished (COMPLETE) unless the budget already stopped it. */
    fun finish(key: String) {
        db.exec("UPDATE kx_source SET state=? WHERE skey=? AND state=?", IndexedSource.State.COMPLETE.name, key, IndexedSource.State.INDEXING.name)
    }

    /** Removes the passages of ONE document inside a source (a file that changed or was deleted) and re-adds nothing. */
    fun removeDocument(key: String, docKey: String) = db.transaction {
        val sid = sourceId(key) ?: return@transaction
        deletePassages("sourceId = ? AND docKey = ?", sid, docKey)
        recount(sid)
    }

    fun removeSource(key: String) = db.transaction { removeSourceRows(key) }

    /** Drops EVERYTHING indexed. The sources are untouched. */
    fun clear() = db.transaction {
        db.exec("DELETE FROM kx_fts"); db.exec("DELETE FROM kx_passage"); db.exec("DELETE FROM kx_source")
    }

    private fun sourceId(key: String): Long? = db.query("SELECT id FROM kx_source WHERE skey=?", key) { it.long(0) }.firstOrNull()

    private fun removeSourceRows(key: String) {
        val sid = sourceId(key) ?: return
        deletePassages("sourceId = ?", sid)
        db.exec("DELETE FROM kx_source WHERE id = ?", sid)
    }

    private fun deletePassages(where: String, vararg args: Any?) {
        db.exec("DELETE FROM kx_fts WHERE docid IN (SELECT id FROM kx_passage WHERE $where)", *args)
        db.exec("DELETE FROM kx_passage WHERE $where", *args)
    }

    /** Accounted bytes of one passage: its text plus the stems index, with the measured SQLite overhead (~30%) folded in. */
    private fun cost(textChars: Int, stemChars: Int): Long = ((textChars + stemChars) * OVERHEAD_FACTOR).toLong() + OVERHEAD

    private fun recount(sid: Long) {
        db.exec(
            "UPDATE kx_source SET passages = (SELECT COUNT(*) FROM kx_passage WHERE sourceId = ?), " +
                "bytes = (SELECT CAST(COALESCE(SUM((LENGTH(text) + LENGTH(title) + LENGTH(section)) * 1.7 * $OVERHEAD_FACTOR + $OVERHEAD),0) AS INTEGER) FROM kx_passage WHERE sourceId = ?) WHERE id = ?",
            sid, sid, sid,
        )
    }

    private fun stateOf(s: String) = IndexedSource.State.entries.firstOrNull { it.name == s } ?: IndexedSource.State.INDEXING

    // ---- search ----------------------------------------------------------------------------------------------

    /** Document frequency of a stem in the index (0 = unknown word). */
    private fun df(term: String): Int = db.query("SELECT documents FROM kx_vocab WHERE term=? AND col='*'", term) { it.long(0).toInt() }.firstOrNull() ?: 0

    /** A near-miss of an unknown word among the index's own vocabulary (same first 2 letters, bounded edit distance). */
    private fun repair(term: String): String? {
        if (term.length < 6) return null
        val head = term.take(2)
        val max = if (term.length >= 9) 2 else 1
        val candidates = db.query("SELECT term, documents FROM kx_vocab WHERE col='*' AND term >= ? AND term < ? LIMIT 4000", head, head.dropLast(1) + (head.last() + 1)) { it.string(0) to it.long(1).toInt() }
        return candidates.filter { (t, _) -> kotlin.math.abs(t.length - term.length) <= max && KnowledgeText.editDistance(term, t, max) in 1..max }
            .maxByOrNull { it.second }?.first
    }

    /**
     * Candidate passages for [question], best first (at most [limit]). Terms unknown to the index are repaired against its
     * vocabulary when a close word exists; the query ANDs the question's terms and relaxes toward its rarest ones.
     */
    fun search(question: String, limit: Int = 12, kinds: Set<String>? = null): List<KnowledgeChunk> {
        val raw = SupportJudge.stems(question).distinct().take(MAX_TERMS)
        if (raw.isEmpty() || passageCount() == 0) return emptyList()
        val known = ArrayList<Pair<String, Int>>()
        for (t in raw) {
            val d = df(t)
            if (d > 0) known += t to d else repair(t)?.let { r -> df(r).takeIf { it > 0 }?.let { known += r to it } }
        }
        if (known.isEmpty()) return emptyList()
        val byRarity = known.distinctBy { it.first }.sortedBy { it.second }.map { it.first }

        val ids = LinkedHashSet<Long>()
        // BM25 mode: docid -> best score over the queries below; the candidates are then the TOP of that scan, not the first rows in docid order
        val scored = HashMap<Long, Double>()
        // AND of all terms first, then every subset that drops one, then two terms (a rare word in the question may simply not
        // occur in the answer-bearing passage). A single common word is never a query: it would drag in unrelated passages.
        var queries = 0
        var bm25Works = options.bm25
        for (k in byRarity.size downTo maxOf(1, byRarity.size - 2)) {
            for (subset in subsets(byRarity, k)) {
                if ((ids.size >= CANDIDATES && !bm25Works) || queries >= MAX_QUERIES) break
                if (subset.size == 1 && known.first { it.first == subset[0] }.second > SINGLE_TERM_MAX_DF && raw.size > 1) continue
                val expr = subset.joinToString(" ") { it.filter { c -> c.isLetterOrDigit() } }.trim()
                if (expr.isEmpty()) continue
                queries++
                if (bm25Works) {
                    val hits = runCatching { bm25Scan(expr, options.scanCap) }.getOrNull()
                    if (hits != null) { for ((id, sc) in hits) scored.merge(id, sc, ::maxOf); continue }
                    bm25Works = false // matchinfo not usable on this build/adapter: legacy selection from here on
                }
                db.query("SELECT docid FROM kx_fts WHERE kx_fts MATCH ? LIMIT ?", expr, CANDIDATES) { it.long(0) }.forEach { ids += it }
            }
            if (!bm25Works && ids.size >= limit * 2) break
            // the drop-one level always runs: the best passage may lack the one generic word of the question that every look-alike passage has
            if (bm25Works && scored.size >= limit * 2 && k <= byRarity.size - 1) break
        }
        if (scored.isNotEmpty()) {
            // scores of different expressions are not comparable: the union is re-scored by ONE disjunctive expression over exactly those rows
            val pool = scored.entries.sortedByDescending { it.value }.take(RESCORE_POOL).map { it.key }
            val any = byRarity.joinToString(" OR ") { it.filter { c -> c.isLetterOrDigit() } }
            val uniform = runCatching { bm25Rescore(any, pool) }.getOrNull()
            val finalScores = uniform?.takeIf { it.isNotEmpty() } ?: scored.entries.map { it.key to it.value }
            finalScores.sortedByDescending { it.second }.take(options.rerank).forEach { ids += it.first }
        }
        if (ids.isEmpty()) return emptyList()
        val rows = ids.toList().take(maxOf(CANDIDATES, options.rerank)).chunked(400).flatMap { part ->
            db.query(
                "SELECT p.docKey, p.title, p.section, p.text, p.lead, s.kind, s.collection, s.date, s.lexical, p.id FROM kx_passage p JOIN kx_source s ON s.id = p.sourceId WHERE p.id IN (${part.joinToString(",") { "?" }})",
                *part.toTypedArray(),
            ) { Row(it.long(9), it.string(0), it.string(1), it.string(2), it.string(3), it.long(4) == 1L, it.string(5), it.string(6), it.string(7), it.long(8) == 1L) }
        }.filter { kinds == null || it.kind in kinds }
        val bmRank = HashMap<Long, Int>().also { m -> ids.forEachIndexed { i, id -> m[id] = i } }
        val chunks = rows.map { KnowledgeChunk(it.docKey, it.title, it.section, it.text, it.kind, it.collection, it.date, it.lead, it.lexical) }
        val ranked = (KnowledgeSearch(minScore = 0.0).search(question, chunks, chunks.size) as? com.simone.jarvismobile.core.knowledge.Evidence.Grounded)?.passages.orEmpty()
        if (!options.bm25 || scored.isEmpty()) return ranked.map { it.chunk }.take(limit)
        // Reciprocal-rank fusion of the two orders (BM25 of the index, TF-IDF + headings of KnowledgeSearch): neither alone decides, the answer
        // that one order buries and the other lifts is not lost to the final cut.
        val idOf = rows.associateBy({ r -> r.docKey + "\u0000" + r.section + "\u0000" + r.text.take(80) }, { it.id })
        return ranked.mapIndexed { i, sc ->
            val id = idOf[sc.chunk.sourceId + "\u0000" + sc.chunk.section + "\u0000" + sc.chunk.text.take(80)]
            val bm = id?.let { bmRank[it] } ?: ids.size
            sc.chunk to (1.0 / (RRF_K + i) + 1.0 / (RRF_K + bm))
        }.sortedByDescending { it.second }.map { it.first }.take(limit)
    }

    /**
     * BM25 (k1 = 1.2, b = 0.75) of every row matching [expr], from FTS4 `matchinfo(kx_fts,'pcnalx')` — no FTS5, no custom SQL function. At most [cap] rows are
     * scanned (a bounded cost: only the small matchinfo blob is read, never the passage text). Layout of the blob (32-bit native-endian integers):
     * `p` phrases, `c` columns, `n` rows in the table, `a` average tokens per column, `l` tokens of this row's column, then per phrase and column `x`:
     * hits in this row, hits in all rows, rows with hits.
     */
    fun bm25Scan(expr: String, cap: Int): List<Pair<Long, Double>> =
        db.query("SELECT docid, matchinfo(kx_fts, 'pcnalx') FROM kx_fts WHERE kx_fts MATCH ? LIMIT ?", expr, cap) { row ->
            row.long(0) to bm25(row.blob(1))
        }

    /** BM25 of exactly the rows [pool] under [expr] (one query per 400 ids, no scan beyond them). */
    fun bm25Rescore(expr: String, pool: List<Long>): List<Pair<Long, Double>> = pool.chunked(400).flatMap { part ->
        db.query("SELECT docid, matchinfo(kx_fts, 'pcnalx') FROM kx_fts WHERE kx_fts MATCH ? AND docid IN (${part.joinToString(",") { "?" }})", expr, *part.toTypedArray()) { row ->
            row.long(0) to bm25(row.blob(1))
        }
    }

    private fun bm25(blob: ByteArray): Double {
        if (blob.size < 12) return 0.0
        val bb = java.nio.ByteBuffer.wrap(blob).order(java.nio.ByteOrder.nativeOrder())
        fun i(n: Int) = bb.getInt(n * 4).toLong() and 0xFFFFFFFFL
        val phrases = i(0).toInt(); val cols = i(1).toInt(); val rows = i(2).toDouble()
        val avg = i(3).toDouble().coerceAtLeast(1.0)
        val len = i(3 + cols).toDouble()
        var score = 0.0
        for (p in 0 until phrases) {
            val base = 3 + 2 * cols + 3 * (p * cols)
            val tf = i(base).toDouble(); val docsWith = i(base + 2).toDouble()
            if (tf <= 0.0) continue
            val idf = Math.log(1.0 + (rows - docsWith + 0.5) / (docsWith + 0.5))
            score += idf * tf * (K1 + 1.0) / (tf + K1 * (1.0 - B + B * len / avg))
        }
        return score
    }

    /** All k-element subsets of [items], keeping the original order (rarest first). */
    private fun subsets(items: List<String>, k: Int): List<List<String>> {
        if (k >= items.size) return listOf(items)
        val out = ArrayList<List<String>>()
        fun rec(start: Int, cur: ArrayList<String>) {
            if (cur.size == k) { out += cur.toList(); return }
            for (i in start until items.size) { cur += items[i]; rec(i + 1, cur); cur.removeAt(cur.size - 1) }
        }
        rec(0, ArrayList())
        return out
    }

    private data class Row(val id: Long, val docKey: String, val title: String, val section: String, val text: String, val lead: Boolean, val kind: String, val collection: String, val date: String, val lexical: Boolean)

    companion object {
        const val SCHEMA = "2"
        private const val OVERHEAD = 64
        private const val OVERHEAD_FACTOR = 1.3
        private const val MAX_TERMS = 8
        private const val CANDIDATES = 150
        private const val MAX_QUERIES = 40
        private const val RESCORE_POOL = 800
        private const val RRF_K = 20
        private const val SINGLE_TERM_MAX_DF = 300
        private const val K1 = 1.2
        private const val B = 0.75
    }
}
