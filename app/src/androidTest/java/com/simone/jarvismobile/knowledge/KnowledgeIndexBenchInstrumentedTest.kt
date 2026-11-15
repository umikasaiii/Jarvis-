package com.simone.jarvismobile.knowledge

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.simone.jarvismobile.core.knowledge.KnowledgeSearch
import com.simone.jarvismobile.core.knowledge.KnowledgeText
import com.simone.jarvismobile.core.knowledge.SupportJudge
import com.simone.jarvismobile.core.knowledge.fts.FtsDb
import com.simone.jarvismobile.core.knowledge.fts.IndexedSource
import com.simone.jarvismobile.core.knowledge.fts.KnowledgeIndex
import com.simone.jarvismobile.core.knowledge.fts.PassageIn
import com.simone.jarvismobile.core.knowledge.zim.ZimArchive
import com.simone.jarvismobile.core.knowledge.zim.ZimIndexer
import com.simone.jarvismobile.knowledge.index.AndroidFtsDb
import com.simone.jarvismobile.knowledge.zim.AndroidZimDecompressor
import com.simone.jarvismobile.knowledge.zim.SafByteSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.text.Normalizer
import java.util.Random

/**
 * KNOWLEDGE ENGINE 2.1 on the REAL Android SQLite / ART of the CI emulator: the indexing pipeline end to end on the committed
 * fixtures (workers, checkpoints, resume), and a before/after of the two things that run on Android only — the SQLite write path
 * and the tokenizer. Figures are logged under the tag JarvisBench and published by the workflow as INDICATIVE emulator numbers
 * (x86_64 on a shared CI runner): they are not the Honor 200's and no assertion depends on them.
 */
@RunWith(AndroidJUnit4::class)
class KnowledgeIndexBenchInstrumentedTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val opened = ArrayList<AndroidFtsDb>()

    private fun db(name: String): AndroidFtsDb { ctx.deleteDatabase(name); return AndroidFtsDb(ctx, name).also { opened += it } }
    @After fun tearDown() { opened.forEach { it.close() }; listOf("kxb_old.db", "kxb_new.db", "kxb_pipe.db").forEach { ctx.deleteDatabase(it) } }

    private fun fixture(name: String): Uri {
        val f = File(ctx.cacheDir, name)
        InstrumentationRegistry.getInstrumentation().context.assets.open("zim/$name").use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        return Uri.fromFile(f)
    }

    private fun log(s: String) { Log.i("JarvisBench", s) }

    // ---- 2.0 reference implementations (verbatim behaviour), used only here to measure "before" -------------------------

    private fun oldStems(text: String): List<String> =
        Regex("""[^\p{L}\p{Nd}]+""").split(Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }.replace(Regex("""(?<=\p{L})[-‐‑](?=\p{L})"""), ""))
            .filter { (it.length >= 3 || (it.length == 2 && it.any(Char::isDigit))) && !KnowledgeText.isStopword(it) }
            .map { KnowledgeSearch.stemWord(it) }

    /** The 2.0 write path: 80-passage transactions, one INSERT + one `last_insert_rowid()` + one FTS INSERT per passage, queries per batch. */
    private fun oldWrite(db: FtsDb, passages: List<PassageIn>, stems: List<String>) {
        db.exec("CREATE TABLE IF NOT EXISTS kx_source(id INTEGER PRIMARY KEY, skey TEXT UNIQUE NOT NULL, kind TEXT, title TEXT, collection TEXT, date TEXT, signature TEXT, cursor TEXT, state TEXT, passages INTEGER DEFAULT 0, bytes INTEGER DEFAULT 0, lexical INTEGER DEFAULT 0)")
        db.exec("CREATE TABLE IF NOT EXISTS kx_passage(id INTEGER PRIMARY KEY, sourceId INTEGER NOT NULL, docKey TEXT, title TEXT, section TEXT, text TEXT, lead INTEGER)")
        db.exec("CREATE VIRTUAL TABLE IF NOT EXISTS kx_fts USING fts4(stems, tokenize=unicode61)")
        db.exec("INSERT INTO kx_source(skey, kind, title, state) VALUES('old','zim','Old','INDEXING')")
        val sid = db.query("SELECT id FROM kx_source WHERE skey='old'") { it.long(0) }.first()
        passages.indices.chunked(80).forEach { batch ->
            db.transaction {
                db.query("SELECT id FROM kx_source WHERE skey=?", "old") { it.long(0) }
                db.query("SELECT COALESCE(SUM(bytes),0) FROM kx_source") { it.long(0) }
                for (i in batch) {
                    val p = passages[i]
                    db.exec("INSERT INTO kx_passage(sourceId, docKey, title, section, text, lead) VALUES(?,?,?,?,?,?)", sid, p.docKey, p.title, p.section, p.text, if (p.lead) 1 else 0)
                    val id = db.query("SELECT last_insert_rowid()") { it.long(0) }.first()
                    db.exec("INSERT INTO kx_fts(docid, stems) VALUES(?,?)", id, stems[i])
                }
                db.exec("UPDATE kx_source SET passages = passages + ?, bytes = bytes + ?, cursor = ? WHERE id = ?", batch.size, 1L, batch.last().toString(), sid)
            }
        }
    }

    private fun corpus(n: Int): List<PassageIn> {
        val rnd = Random(3)
        val words = (0 until 3000).map { i -> "parola${i}" + listOf("à", "e", "o", "ità", "zione", "mente")[i % 6] }
        return List(n) { i ->
            val text = (1..60).joinToString(" ") { words[(Math.abs(rnd.nextGaussian()) * 700).toInt().coerceAtMost(2999)] } + ". Il Wi-Fi è l'università di città."
            PassageIn("zim:x/A_$i", "Voce $i", if (i % 3 == 0) "Storia" else "", text, lead = true)
        }
    }

    @Test fun tokenizerAndSqliteWritePathBeforeAfter() {
        val ps = corpus(12_000)
        // tokenizer
        var t = System.nanoTime(); val oldStemsList = ps.map { oldStems(it.title + " " + it.section + " " + it.text).joinToString(" ") }; val tokOld = (System.nanoTime() - t) / 1e6
        t = System.nanoTime(); val newStemsList = ps.map { PassageIn.stemsOf(it.title, it.section, it.text) }; val tokNew = (System.nanoTime() - t) / 1e6
        assertEquals("the new tokenizer must produce exactly the old stems", oldStemsList, newStemsList)
        // write path (same stems for both)
        val oldDb = db("kxb_old.db"); t = System.nanoTime(); oldWrite(oldDb, ps, newStemsList); val wOld = (System.nanoTime() - t) / 1e6
        val newDb = db("kxb_new.db"); val ix = KnowledgeIndex(newDb)
        ix.begin("new", "zim", "New", "", "", "1")
        val withStems = ps.mapIndexed { i, p -> p.copy(stems = newStemsList[i]) }
        t = System.nanoTime(); withStems.chunked(1500).forEach { ix.addBatch("new", it, "x", 1_000_000_000L) }; val wNew = (System.nanoTime() - t) / 1e6
        assertEquals(ps.size, ix.passageCount())
        assertEquals(ps.size.toLong(), oldDb.query("SELECT COUNT(*) FROM kx_passage") { it.long(0) }.first())
        assertTrue(ix.search("parola12 università").isNotEmpty())
        log("EMULATOR tokenizer ${ps.size} passages: before(regex) %.0f ms, after(single pass) %.0f ms".format(tokOld, tokNew))
        log("EMULATOR sqlite write path ${ps.size} passages: before(80/tx, per-row last_insert_rowid) %.0f ms, after(1500/tx, prepared statements, explicit ids) %.0f ms".format(wOld, wNew))
    }

    @Test fun pipelineOnFixturesResumesAndMatchesAnyWorkerCount() {
        val uri = fixture("modern_listing_v1_zstd.zim")
        SafByteSource(ctx, uri).use { src ->
            val zim = ZimArchive.open(src, AndroidZimDecompressor)
            val a = KnowledgeIndex(db("kxb_pipe.db")); a.clear()
            assertEquals(IndexedSource.State.COMPLETE, ZimIndexer(zim, a, workers = 3).run("s", 1000, 5_000_000))
            assertEquals("Madrid", a.search("qual è la capitale della Spagna").first().sourceTitle)
            val passages = a.passageCount()
            // interrupted at the first checkpoint, then resumed: same passages, no duplicates
            a.clear()
            var cp = 0
            val s1 = ZimIndexer(zim, a, workers = 2).run("s", 1000, 5_000_000, shouldContinue = { cp < 1 }, onProgress = { cp++ }, checkpointPassages = 1)
            assertTrue(s1 == IndexedSource.State.INDEXING || s1 == IndexedSource.State.COMPLETE)
            assertEquals(IndexedSource.State.COMPLETE, ZimIndexer(zim, a, workers = 1).run("s", 1000, 5_000_000))
            assertEquals(passages, a.passageCount())
            a.clear()
        }
    }
}
