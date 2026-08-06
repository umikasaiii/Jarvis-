package com.simone.jarvismobile.knowledge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.simone.jarvismobile.core.knowledge.Evidence
import com.simone.jarvismobile.core.knowledge.KnowledgeChunk
import com.simone.jarvismobile.core.knowledge.KnowledgeIndexer
import com.simone.jarvismobile.core.knowledge.KnowledgeSearch
import com.simone.jarvismobile.data.SettingsRepository
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

    @Volatile private var chunks: List<KnowledgeChunk> = emptyList()
    private val search = KnowledgeSearch()
    private val mutex = Mutex()

    suspend fun isConfigured(): Boolean = settings.knowledgeUri.first().isNotBlank()

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
        chunks = emptyList()
        _status.value = Status(configured = false)
    }

    suspend fun libraryName(): String? {
        val uri = settings.knowledgeUri.first()
        if (uri.isBlank()) return null
        return runCatching { DocumentFile.fromTreeUri(context, Uri.parse(uri))?.name }.getOrNull()
    }

    /** Indexes only if a library is set and nothing has been indexed yet. */
    suspend fun ensureBuilt() {
        if (chunks.isNotEmpty() || _status.value.indexing) return
        if (!isConfigured()) {
            _status.value = _status.value.copy(configured = false)
            return
        }
        rebuild()
    }

    /** Re-reads the whole library folder. */
    suspend fun rebuild() = withContext(Dispatchers.Default) {
        mutex.withLock {
            _status.value = _status.value.copy(configured = true, indexing = true, lastError = null)
            try {
                val uri = settings.knowledgeUri.first()
                val tree = DocumentFile.fromTreeUri(context, Uri.parse(uri))
                    ?: throw IllegalStateException("no_tree")
                val docs = ArrayList<Pair<String, String>>()
                collect(tree, "", docs)
                val built = docs.flatMap { (path, text) ->
                    KnowledgeIndexer.chunk(
                        sourceId = path,
                        sourceTitle = path.substringAfterLast('/').substringBeforeLast('.'),
                        content = text,
                    )
                }
                chunks = built
                _status.value = Status(
                    configured = true,
                    indexing = false,
                    documentCount = docs.size,
                    passageCount = built.size,
                )
                Log.i(TAG, "knowledge_indexed docs=${docs.size} passages=${built.size}")
            } catch (t: Throwable) {
                Log.w(TAG, "knowledge_index_failed ${t.javaClass.simpleName}")
                _status.value = _status.value.copy(indexing = false, lastError = t.javaClass.simpleName)
            }
        }
    }

    /** Passages that actually support [question], or [Evidence.NoEvidence]. */
    fun find(question: String, limit: Int = 3): Evidence = search.search(question, chunks, limit)

    private fun collect(dir: DocumentFile, prefix: String, out: MutableList<Pair<String, String>>) {
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
            val text = runCatching {
                context.contentResolver.openInputStream(f.uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull() ?: continue
            out += "$prefix$name" to text
        }
    }

    private companion object {
        const val TAG = "JarvisKnowledge"

        /**
         * Plain-text formats only for now. PDF/EPUB need text extraction and a
         * full Wikipedia dump should be read as ZIM through a dedicated reader
         * rather than expanded into files — both are described in
         * docs/LOCAL_KNOWLEDGE.md and slot in behind this same interface.
         */
        val SUPPORTED = listOf(".md", ".markdown", ".txt", ".text")

        /** Bounds so importing a huge folder cannot exhaust memory. */
        const val MAX_DOCUMENTS = 4_000
        const val MAX_FILE_BYTES = 4L * 1024 * 1024
    }
}
