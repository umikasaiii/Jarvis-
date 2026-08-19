package com.simone.jarvismobile.document

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.simone.jarvismobile.core.document.Checksums
import com.simone.jarvismobile.core.document.DocumentChunker
import com.simone.jarvismobile.core.document.DocumentRecord
import com.simone.jarvismobile.core.document.DocumentRetrieval
import com.simone.jarvismobile.core.document.DocumentSource
import com.simone.jarvismobile.core.document.DocumentStatus
import com.simone.jarvismobile.core.document.DocumentSection
import com.simone.jarvismobile.core.document.HybridDocumentRetriever
import com.simone.jarvismobile.core.document.ParsedDocument
import com.simone.jarvismobile.core.document.ParsedMetadata
import com.simone.jarvismobile.core.document.ParserRegistry
import com.simone.jarvismobile.core.document.SafeFileName
import com.simone.jarvismobile.data.SettingsRepository
import com.simone.jarvismobile.memory.VaultRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Imports a document from a picked [Uri] into JARVIS: it copies the file to
 * app-private storage (and optionally into the Obsidian vault), hashes it for
 * duplicate detection, extracts and chunks its text, and indexes the chunks in
 * Room — all off the main thread, so the chat never blocks.
 *
 * This is the local-first, offline document pipeline. Nothing leaves the device.
 * Each import runs as its own cancellable [Job]; the chat keeps working while a
 * document indexes, and cancelling one import never disturbs another.
 *
 * It is also the document [com.simone.jarvismobile.core.document.Retriever]
 * source: [documentEvidence] runs BEFORE generation so the model answers from
 * cited passages, not from a half-remembered file.
 */
@Singleton
class DocumentImportManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: DocumentDao,
    private val vault: VaultRepository,
    private val settings: SettingsRepository,
    private val categoryClassifier: com.simone.jarvismobile.memory.MemoryCategoryClassifier,
) {
    /** A duplicate the user must resolve before it is (re-)imported. */
    data class DuplicatePrompt(
        val existing: DocumentRecord,
        val uri: String,
        val saveToVault: Boolean,
        val displayName: String,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()

    private val _documents = MutableStateFlow<List<DocumentRecord>>(emptyList())
    /** Every known document, newest first, for the chat cards and the archive UI. */
    val documents: StateFlow<List<DocumentRecord>> = _documents.asStateFlow()

    private val _duplicate = MutableStateFlow<DuplicatePrompt?>(null)
    val duplicate: StateFlow<DuplicatePrompt?> = _duplicate.asStateFlow()

    private val textRegistry = ParserRegistry.textOnly()

    // A warm retriever over every READY chunk, rebuilt when the corpus changes.
    @Volatile private var retriever: HybridDocumentRetriever? = null

    /** Loads the persisted documents into the UI state at startup. */
    suspend fun refresh() {
        _documents.value = runCatching { dao.allDocuments().map { it.toRecord() } }.getOrDefault(emptyList())
    }

    fun dismissDuplicate() { _duplicate.value = null }

    /**
     * Starts importing [uri]. [saveToVault] copies the file into the vault and
     * marks it a permanent [DocumentSource.VAULT_IMPORT]; otherwise it is a
     * conversation-only attachment. Returns immediately — progress shows through
     * [documents]. When [force] is false and the same bytes are already indexed,
     * it raises a [duplicate] prompt instead of importing again.
     */
    fun import(uri: Uri, saveToVault: Boolean, force: Boolean = false) {
        val id = UUID.randomUUID().toString()
        val job = scope.launch {
            runImport(id, uri, saveToVault, force)
        }
        jobs[id] = job
        job.invokeOnCompletion { jobs.remove(id) }
    }

    /** Uses the already-indexed copy instead of importing the duplicate again. */
    fun useExisting() { _duplicate.value = null }

    /** Cancels an in-flight import and removes its partial record. */
    fun cancel(id: String) {
        jobs.remove(id)?.cancel()
        scope.launch {
            runCatching {
                dao.deleteChunks(id)
                dao.deleteDocument(id)
            }
            rebuildRetriever()
            refresh()
        }
    }

    /** Removes a document and its passages entirely (the original file is left). */
    fun remove(id: String) {
        jobs.remove(id)?.cancel()
        scope.launch {
            runCatching {
                dao.deleteChunks(id)
                dao.deleteDocument(id)
            }
            rebuildRetriever()
            refresh()
        }
    }

    private suspend fun runImport(id: String, uri: Uri, saveToVault: Boolean, force: Boolean) {
        val meta = queryMeta(uri)
        val displayName = meta.first
        val declaredSize = meta.second
        val mime = context.contentResolver.getType(uri) ?: guessMime(displayName)

        // Draft record so the card appears immediately as SELECTED.
        var record = DocumentRecord(
            id = id,
            fileName = SafeFileName.of(displayName),
            displayName = displayName,
            originalUri = uri.toString(),
            vaultPath = null,
            mimeType = mime,
            fileSize = declaredSize,
            checksumSha256 = "",
            importedAt = System.currentTimeMillis(),
            modifiedAt = System.currentTimeMillis(),
            parserVersion = DocumentTextExtractors.PARSER_VERSION,
            indexVersion = INDEX_VERSION,
            status = DocumentStatus.SELECTED,
            source = if (saveToVault) DocumentSource.VAULT_IMPORT else DocumentSource.CHAT_ATTACHMENT,
        )
        persist(record)

        try {
            // COPYING: read the bytes once (bounded) and copy to app-private store.
            record = record.copy(status = DocumentStatus.COPYING); persist(record)
            val bytes = readBytes(uri)
            if (bytes == null || bytes.size > MAX_FILE_BYTES) {
                fail(record, if (bytes == null) "lettura non riuscita" else "file troppo grande")
                return
            }
            coroutineContext.ensureActive()
            val checksum = Checksums.sha256(bytes)

            // Duplicate detection before doing the expensive work (if enabled).
            if (!force && settings.docDedup.first()) {
                val existing = dao.findByChecksum(checksum)
                if (existing != null && existing.status == DocumentStatus.READY.name) {
                    dao.deleteDocument(id) // drop the draft
                    refresh()
                    _duplicate.value = DuplicatePrompt(
                        existing = existing.toRecord(),
                        uri = uri.toString(),
                        saveToVault = saveToVault,
                        displayName = displayName,
                    )
                    return
                }
            }

            val safeName = SafeFileName.uniqueOf(displayName, checksum)
            copyToPrivate(safeName, bytes)

            // Optional permanent copy into the vault (never modifies the original).
            var vaultPath: String? = null
            if (saveToVault) {
                vaultPath = vault.writeVaultDocument(VAULT_IMPORT_DIRS, safeName, bytes, mime)
                if (vaultPath == null) Log.w(TAG, "vault_copy_unavailable id=$id")
            }
            record = record.copy(
                fileName = safeName,
                checksumSha256 = checksum,
                fileSize = bytes.size.toLong(),
                vaultPath = vaultPath,
            )

            // Images: OCR only if the user has opted in; otherwise the picture is
            // kept as an attachment (no text) and marked ready directly.
            val ocrText = if (mime.startsWith("image/")) {
                if (settings.docOcrImages.first()) {
                    record = record.copy(status = DocumentStatus.PARSING); persist(record)
                    withContext(Dispatchers.Default) { ImageOcr.recognize(bytes) }
                } else {
                    record = record.copy(status = DocumentStatus.READY, modifiedAt = System.currentTimeMillis())
                    persist(record)
                    return
                }
            } else {
                null
            }

            // PARSING — images that already produced OCR text skip the file parser.
            val parsed = if (ocrText != null) {
                if (ocrText.isBlank()) {
                    record = record.copy(status = DocumentStatus.READY, modifiedAt = System.currentTimeMillis())
                    persist(record)
                    return
                }
                ParsedDocument(
                    documentId = id,
                    text = ocrText,
                    sections = listOf(DocumentSection("", null, ocrText)),
                    metadata = ParsedMetadata(title = displayName.substringBeforeLast('.')),
                )
            } else {
                record = record.copy(status = DocumentStatus.PARSING); persist(record)
                coroutineContext.ensureActive()
                withContext(Dispatchers.Default) {
                    when {
                        // PDF/DOCX need platform code (PDFBox / DOCX zip) — Android side.
                        DocumentTextExtractors.isBinary(mime, safeName) ->
                            DocumentTextExtractors.extract(context, id, safeName, mime, bytes)
                        // EPUB/XLSX are ZIP+XML: pure-Kotlin, handled in :core.
                        com.simone.jarvismobile.core.document.OfficeExtractors.isContainer(mime, safeName) ->
                            com.simone.jarvismobile.core.document.OfficeExtractors.extract(id, safeName, mime, bytes)
                        // Everything text-native (txt/md/html/json/csv) → the registry.
                        else -> {
                            val text = bytes.toString(Charsets.UTF_8)
                            val parser = textRegistry.parserFor(mime, displayName)
                                ?: textRegistry.parserFor("text/plain", "x.txt")!!
                            parser.parse(id, safeName, text)
                        }
                    }
                }
            }
            if (parsed.text.isBlank()) {
                fail(record, "nessun testo estraibile")
                return
            }
            record = record.copy(
                title = parsed.metadata.title,
                author = parsed.metadata.author,
                pageCount = parsed.metadata.pageCount,
                language = parsed.metadata.language,
            )

            // Sort the document into the same AI macro-categories as memory, from
            // its title + a sample of the (possibly OCR'd) text, so the archive
            // groups files, photos and documents like notes. Stored as a "cat:" tag
            // — no schema change — and best-effort (empty when no model is loaded).
            val category = runCatching {
                val sample = listOfNotNull(parsed.metadata.title, parsed.text.take(600))
                    .joinToString(". ")
                categoryClassifier.classify(sample)
            }.getOrDefault("")
            if (category.isNotBlank()) {
                record = record.copy(
                    tags = (record.tags.filterNot { it.startsWith(DOCUMENT_CATEGORY_TAG) } + "$DOCUMENT_CATEGORY_TAG$category")
                        .distinct(),
                )
            }

            // Auto-index unless the user turned it off: then it stays a ready
            // attachment (kept and citable by name, but not searched).
            if (!settings.docAutoIndex.first()) {
                record = record.copy(status = DocumentStatus.READY, modifiedAt = System.currentTimeMillis())
                persist(record)
                return
            }

            // INDEXING
            record = record.copy(status = DocumentStatus.INDEXING); persist(record)
            coroutineContext.ensureActive()
            val chunks = withContext(Dispatchers.Default) { DocumentChunker.chunk(parsed, safeName) }
            dao.deleteChunks(id)
            dao.upsertChunks(chunks.map { it.toEntity() })

            // READY
            record = record.copy(status = DocumentStatus.READY, modifiedAt = System.currentTimeMillis())
            persist(record)
            rebuildRetriever()
            Log.i(TAG, "document_ready id=$id chunks=${chunks.size} vault=${vaultPath != null}")
        } catch (t: Throwable) {
            if (t is CancellationException) {
                runCatching { dao.deleteChunks(id); dao.deleteDocument(id) }
                refresh()
                throw t
            }
            Log.w(TAG, "import_failed id=$id ${t.javaClass.simpleName}")
            fail(record, t.javaClass.simpleName)
        }
    }

    /**
     * Passages from the imported documents that support [question], formatted with
     * their citation, or null when nothing is relevant enough. Mirrors the offline
     * library's contract so the coordinator can merge both grounding sources.
     */
    suspend fun documentEvidence(question: String, maxContext: Int = DocumentRetrieval.MAX_CONTEXT): String? {
        val r = retriever ?: rebuildRetriever().let { retriever } ?: return null
        val hits = DocumentRetrieval.select(question, r, maxContext = maxContext)
        if (hits.isEmpty()) return null
        return hits.joinToString("\n") { p ->
            "- [" + p.citation + "] " + p.chunk.text.replace('\n', ' ').trim().take(EVIDENCE_CHARS)
        }
    }

    /** True if at least one document is ready to be queried. */
    suspend fun hasReadyDocuments(): Boolean =
        (retriever ?: rebuildRetriever().let { retriever }) != null

    /**
     * Every chunk of the one READY document whose name matches [needle], joined
     * in order — a specific-attachment lookup ("cosa c'è scritto nel PDF X?"),
     * as opposed to [documentEvidence]'s cross-document relevance search. Fails
     * closed (null) on no match or on ambiguity, same rule as every other
     * by-name lookup in this app (see [com.simone.jarvismobile.memory.MemoryIndex.deleteByText]).
     */
    suspend fun contextFor(needle: String, maxChars: Int = 1_500): Pair<DocumentRecord, String>? {
        val n = needle.trim()
        if (n.length < 2) return null
        val match = _documents.value.filter {
            it.status == DocumentStatus.READY &&
                (it.displayName.contains(n, ignoreCase = true) || it.title?.contains(n, ignoreCase = true) == true)
        }
        val doc = match.singleOrNull() ?: return null
        val chunks = runCatching { dao.chunksFor(listOf(doc.id)) }.getOrDefault(emptyList())
        if (chunks.isEmpty()) return null
        val text = chunks.sortedBy { it.chunkIndex }.joinToString("\n") { it.text }.take(maxChars)
        return doc to text
    }

    /**
     * Photos/scans only (mimeType starting with "image/"), matched by title/tag/OCR text
     * (spec §8/§9: "search_images" as a distinct, narrower search than
     * [documentEvidence]'s cross-document relevance ranking — a "trova la
     * ricevuta delle gomme" should not also surface a PDF that happens to
     * mention gomme). Lexical only, same as the rest of this pragmatic photo
     * feature; no visual embedding model exists to rank by yet.
     */
    suspend fun searchImages(query: String, limit: Int = 5): List<DocumentRecord> {
        val n = query.trim()
        if (n.length < 2) return emptyList()
        val images = _documents.value.filter { it.status == DocumentStatus.READY && it.mimeType.startsWith("image/") }
        if (images.isEmpty()) return emptyList()
        val byMetadata = images.filter {
            it.displayName.contains(n, ignoreCase = true) ||
                it.title?.contains(n, ignoreCase = true) == true ||
                it.tags.any { tag -> tag.contains(n, ignoreCase = true) }
        }
        val chunks = runCatching { dao.chunksFor(images.map { it.id }) }.getOrDefault(emptyList())
        val byOcr = chunks.filter { it.text.contains(n, ignoreCase = true) }
            .mapNotNull { chunk -> images.firstOrNull { it.id == chunk.documentId } }
        return (byMetadata + byOcr).distinctBy { it.id }.take(limit)
    }

    private suspend fun rebuildRetriever(): Unit = withContext(Dispatchers.Default) {
        val chunks = runCatching { dao.readyChunks().map { it.toChunk() } }.getOrDefault(emptyList())
        retriever = if (chunks.isEmpty()) null else HybridDocumentRetriever(chunks)
    }

    private suspend fun persist(record: DocumentRecord) {
        runCatching { dao.upsertDocument(record.toEntity()) }
        refresh()
    }

    private suspend fun fail(record: DocumentRecord, reason: String) {
        Log.w(TAG, "import_failed_reason ${record.id} $reason")
        persist(record.copy(status = DocumentStatus.FAILED, modifiedAt = System.currentTimeMillis()))
    }

    private fun copyToPrivate(safeName: String, bytes: ByteArray) {
        runCatching {
            val dir = java.io.File(context.filesDir, PRIVATE_DIR).apply { mkdirs() }
            java.io.File(dir, safeName).writeBytes(bytes)
        }.onFailure { Log.w(TAG, "private_copy_failed ${it.javaClass.simpleName}") }
    }

    private fun readBytes(uri: Uri): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }.getOrNull()

    private fun queryMeta(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "documento"
        var size = 0L
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        }
        return name to size
    }

    private fun guessMime(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "md", "markdown" -> "text/markdown"
        "json" -> "application/json"
        "csv" -> "text/csv"
        else -> "text/plain"
    }

    private companion object {
        const val TAG = "JarvisDocImport"
        const val INDEX_VERSION = 1
        const val PRIVATE_DIR = "documents"
        const val EVIDENCE_CHARS = 600
        const val MAX_FILE_BYTES = 25L * 1024 * 1024
        val VAULT_IMPORT_DIRS = listOf("20_KNOWLEDGE", "Documents")
    }
}

/** Tag prefix under which a document's AI macro-category is stored. */
const val DOCUMENT_CATEGORY_TAG = "cat:"

/** The AI macro-category of a document, or "Senza categoria" when unclassified. */
fun DocumentRecord.category(): String =
    tags.firstOrNull { it.startsWith(DOCUMENT_CATEGORY_TAG) }
        ?.removePrefix(DOCUMENT_CATEGORY_TAG)
        ?.takeIf { it.isNotBlank() }
        ?: "Senza categoria"
