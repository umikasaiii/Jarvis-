package com.simone.jarvismobile.knowledge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.simone.jarvismobile.core.knowledge.Evidence
import com.simone.jarvismobile.core.knowledge.KnowledgeChunk
import com.simone.jarvismobile.core.knowledge.KnowledgeGatherer
import com.simone.jarvismobile.core.knowledge.KnowledgeIndexer
import com.simone.jarvismobile.core.knowledge.KnowledgeSearch
import com.simone.jarvismobile.core.knowledge.fts.PassageIn
import com.simone.jarvismobile.data.SettingsRepository
import com.simone.jarvismobile.knowledge.index.KnowledgeIndexManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The offline reference library: wiki exports, manuals and guides the user
 * imports, kept strictly apart from personal memory (docs/LOCAL_KNOWLEDGE.md).
 *
 * Access is read-only through the Storage Access Framework — the user picks a
 * folder with the system picker and nothing else on the device is reachable. No
 * `MANAGE_EXTERNAL_STORAGE`, no network: a "wiki" here is a folder of files that
 * are already on the phone.
 *
 * Retrieval runs BEFORE generation. The model only ever sees a handful of
 * passages plus their source, and when the library has no adequate evidence the
 * result says so instead of letting the model improvise.
 */
@Singleton
class KnowledgeRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val zim: com.simone.jarvismobile.knowledge.zim.ZimKnowledge,
    private val indexManager: KnowledgeIndexManager,
) {
    /** Observable state of the library, for the UI. */
    data class Status(
        val configured: Boolean = false,
        val indexing: Boolean = false,
        val documentCount: Int = 0,
        val passageCount: Int = 0,
        val lastError: String? = null,
    )

    private val _status = MutableStateFlow(Status())
    val status = _status.asStateFlow()

    private val search = KnowledgeSearch()
    private val gatherer = KnowledgeGatherer()
    private val mutex = Mutex()
    @Volatile private var synced = false

    suspend fun isConfigured(): Boolean = settings.knowledgeUri.first().isNotBlank()

    /** Something to search: a text library folder, an archive verified on this device, or anything already indexed. */
    suspend fun isAvailable(): Boolean = isConfigured() || zim.hasVerified() || withContext(Dispatchers.IO) { runCatching { indexManager.index.passageCount() > 0 }.getOrDefault(false) }

    /**
     * KNOWLEDGE ENGINE 2.0 — the one retrieval entry point for general knowledge: ZIM archives (articles whose TITLE the question
     * names, read on demand) plus the persistent full-text index (library files and the leads of archives the user chose to
     * index), judged by the SAME [com.simone.jarvismobile.core.knowledge.EvidenceAssembler] gate: relevance is not sufficiency.
     * Personal memory and the user's imported documents are NOT sources here: they have their own paths and never mix with
     * encyclopaedic text.
     */
    suspend fun gather(question: String, limit: Int = 3): Evidence = withContext(Dispatchers.Default) {
        val index = runCatching { indexManager.index }.getOrNull()
        gatherer.gather(question, zim.sources(), index, emptyList(), limit)
    }

    /** Persists the picked folder and takes a durable READ permission only. */
    suspend fun setLibrary(treeUri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        settings.setKnowledgeUri(treeUri.toString())
        rebuild()
    }

    suspend fun clearLibrary() {
        val uri = settings.knowledgeUri.first()
        if (uri.isNotBlank()) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(uri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        settings.setKnowledgeUri("")
        withContext(Dispatchers.IO) {
            runCatching { indexManager.index.sources().filter { it.kind == "doc" }.forEach { indexManager.index.removeSource(it.key) } }
        }
        synced = false
        indexManager.refresh()
        _status.value = Status(configured = false)
    }

    suspend fun libraryName(): String? {
        val uri = settings.knowledgeUri.first()
        if (uri.isBlank()) return null
        return runCatching { DocumentFile.fromTreeUri(context, Uri.parse(uri))?.name }.getOrNull()
    }

    /** Syncs the library into the index once per process (incrementally: unchanged files cost nothing). */
    suspend fun ensureBuilt() {
        if (synced || _status.value.indexing) return
        if (!isConfigured()) {
            _status.value = _status.value.copy(configured = false)
            return
        }
        rebuild()
    }

    /**
     * Brings the index in line with the library folder: new/changed files are (re)indexed, vanished files are removed,
     * unchanged files are skipped (signature = size + modification time). Nothing is held in memory afterwards and an
     * interruption resumes: a half-indexed file is redone, finished ones are kept.
     */
    suspend fun rebuild() = withContext(Dispatchers.IO) {
        mutex.withLock {
            _status.value = _status.value.copy(configured = true, indexing = true, lastError = null)
            try {
                val uri = settings.knowledgeUri.first()
                val tree = DocumentFile.fromTreeUri(context, Uri.parse(uri)) ?: throw IllegalStateException("no_tree")
                val files = ArrayList<Pair<String, DocumentFile>>()
                collect(tree, "", files)
                val index = indexManager.index
                val budget = indexManager.budgetBytes()
                val seen = HashSet<String>()
                for ((path, f) in files) {
                    seen += path
                    val signature = "${f.length()}|${f.lastModified()}"
                    if (!index.begin(path, "doc", path.substringAfterLast('/').substringBeforeLast('.'), "", "", signature)) continue
                    val text = runCatching { context.contentResolver.openInputStream(f.uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
                    if (text == null) { index.removeSource(path); continue }
                    val title = path.substringAfterLast('/').substringBeforeLast('.')
                    val passages = KnowledgeIndexer.chunk(path, title, text).mapIndexed { i, c -> PassageIn(path, title, c.section, c.text, lead = i == 0) }
                    val stored = index.addBatch(path, passages, "done", budget)
                    if (stored >= passages.size) index.finish(path)
                }
                // files that vanished from the folder
                index.sources().filter { it.kind == "doc" && it.key !in seen }.forEach { index.removeSource(it.key) }
                val docs = index.sources().filter { it.kind == "doc" }
                synced = true
                _status.value = Status(configured = true, indexing = false, documentCount = docs.size, passageCount = docs.sumOf { it.passages })
                Log.i(TAG, "knowledge_synced docs=${docs.size} passages=${docs.sumOf { it.passages }}")
            } catch (t: Throwable) {
                Log.w(TAG, "knowledge_index_failed ${t.javaClass.simpleName}")
                _status.value = _status.value.copy(indexing = false, lastError = t.javaClass.simpleName)
            }
            indexManager.refresh()
        }
    }

    /** Passages from the TEXT LIBRARY that support [question], or [Evidence.NoEvidence] (lexical gate only; see [gather] for the full judge). */
    fun find(question: String, limit: Int = 3): Evidence {
        val candidates = runCatching { indexManager.index.search(question, 24, setOf("doc")) }.getOrDefault(emptyList())
        return search.search(question, candidates, limit)
    }

    private fun collect(dir: DocumentFile, prefix: String, out: MutableList<Pair<String, DocumentFile>>) {
        if (out.size >= MAX_DOCUMENTS) return
        for (f in dir.listFiles()) {
            if (out.size >= MAX_DOCUMENTS) return
            val name = f.name ?: continue
            if (f.isDirectory) {
                collect(f, "$prefix$name/", out)
                continue
            }
            if (!SUPPORTED.any { name.endsWith(it, ignoreCase = true) }) continue
            if (f.length() > MAX_FILE_BYTES) continue
            out += "$prefix$name" to f
        }
    }

    private companion object {
        const val TAG = "JarvisKnowledge"

        /**
         * Plain-text formats only for now. PDF/EPUB need text extraction; a full Wikipedia dump stays a ZIM, read directly by
         * title and, opt-in, indexed by its article leads (docs/KNOWLEDGE_ENGINE_ZIM.md).
         */
        val SUPPORTED = listOf(".md", ".markdown", ".txt", ".text")

        /** Bounds so importing a huge folder cannot exhaust memory or space. */
        const val MAX_DOCUMENTS = 4_000
        const val MAX_FILE_BYTES = 4L * 1024 * 1024
    }
}
