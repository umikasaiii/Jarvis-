package com.simone.jarvismobile.knowledge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.text.Html
import java.io.File
import java.io.ByteArrayOutputStream
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

    // Kept separate from personal Obsidian notes and chat history.
    private val archiveDir: File get() = File(context.filesDir, "offline_reference").apply { mkdirs() }

    suspend fun isConfigured(): Boolean =
        settings.knowledgeUri.first().isNotBlank() ||
            (archiveDir.listFiles()?.any { it.isFile } == true)

    /** SAF import: create an app-private, persistent copy of a reference text. */
    suspend fun importDocument(uri: Uri): String = withContext(Dispatchers.IO) {
        try {
            val display = DocumentFile.fromSingleUri(context, uri)?.name ?: "documento.txt"
            if (SUPPORTED.none { display.endsWith(it, ignoreCase = true) }) {
                return@withContext "Formato non supportato. Usa TXT, MD, HTML o CSV. PDF e ZIM richiedono un lettore dedicato."
            }
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val count = input.read(buf)
                    if (count < 0) break
                    if (output.size() + count > MAX_FILE_BYTES) {
                        throw IllegalArgumentException("file_too_large")
                    }
                    output.write(buf, 0, count)
                }
                output.toByteArray()
            } ?: return@withContext "File non leggibile."
            if (bytes.isEmpty()) return@withContext "Il file è vuoto."
            val safeName = display.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100)
                .ifBlank { "documento.txt" }
            File(archiveDir, System.currentTimeMillis().toString() + "_" + safeName).writeBytes(bytes)
            rebuild()
            "Importato nell'archivio offline: " + display
        } catch (t: Exception) {
            "Importazione non riuscita: " + t.javaClass.simpleName
        }
    }

    /** Copy/paste an article excerpt from a dedicated offline reader, e.g. Kiwix. */
    suspend fun saveExcerpt(title: String, article: String): String = withContext(Dispatchers.IO) {
        if (article.isBlank()) return@withContext "Incolla prima il testo dell'articolo."
        val safe = title.ifBlank { "estratto" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_").take(72)
            .ifBlank { "estratto" }
        File(archiveDir, System.currentTimeMillis().toString() + "_" + safe + ".txt")
            .writeText(article.take(100_000))
        rebuild()
        "Estratto salvato nell'archivio offline."
    }

    fun importedDocuments(): List<String> =
        archiveDir.listFiles()?.filter { it.isFile }?.map { it.name.substringAfter('_') }
            ?.sorted().orEmpty()


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
        rebuild()
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
                val docs = ArrayList<Pair<String, String>>()
                if (uri.isNotBlank()) {
                    val tree = DocumentFile.fromTreeUri(context, Uri.parse(uri))
                        ?: throw IllegalStateException("no_tree")
                    collect(tree, "", docs)
                }
                archiveDir.listFiles()?.filter { it.isFile }?.forEach { file ->
                    if (docs.size >= MAX_DOCUMENTS) return@forEach
                    if (file.length() > MAX_FILE_BYTES) return@forEach
                    if (SUPPORTED.none { file.name.endsWith(it, ignoreCase = true) }) return@forEach
                    val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
                    docs += ("Archivio/" + file.name.substringAfter('_')) to normalizeText(text, file.name)
                }
                val built = docs.flatMap { (path, text) ->
                    KnowledgeIndexer.chunk(
                        sourceId = path,
                        sourceTitle = path.substringAfterLast('/').substringBeforeLast('.'),
                        content = text,
                    )
                }
                chunks = built
                _status.value = Status(
                    configured = uri.isNotBlank() || docs.isNotEmpty(),
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
            out += "$prefix$name" to normalizeText(text, name)
        }
    }

    private fun normalizeText(text: String, name: String): String =
        if (name.endsWith(".html", true) || name.endsWith(".htm", true)) {
            Html.fromHtml(text, Html.FROM_HTML_MODE_COMPACT).toString()
        } else text

    private companion object {
        const val TAG = "JarvisKnowledge"

        /**
         * Plain-text formats only for now. PDF/EPUB need text extraction and a
         * full Wikipedia dump should be read as ZIM through a dedicated reader
         * rather than expanded into files — both are described in
         * docs/LOCAL_KNOWLEDGE.md and slot in behind this same interface.
         */
        val SUPPORTED = listOf(".md", ".markdown", ".txt", ".text", ".html", ".htm", ".csv")

        /** Bounds so importing a huge folder cannot exhaust memory. */
        const val MAX_DOCUMENTS = 4_000
        const val MAX_FILE_BYTES = 4L * 1024 * 1024
    }
}
