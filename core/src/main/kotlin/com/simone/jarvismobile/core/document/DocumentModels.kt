package com.simone.jarvismobile.core.document

/**
 * The lifecycle of an imported document, mirrored in the chat attachment card.
 * The chat never blocks on any of these: work happens off the main thread and
 * the card advances SELECTED → COPYING → PARSING → INDEXING → READY (or FAILED).
 */
enum class DocumentStatus { SELECTED, COPYING, PARSING, INDEXING, READY, FAILED }

/**
 * Where a document lives. A [CHAT_ATTACHMENT] is only bound to the conversation
 * (a temporary attachment); a [VAULT_IMPORT] has been copied into the Obsidian
 * vault and is indexed permanently.
 */
enum class DocumentSource { CHAT_ATTACHMENT, VAULT_IMPORT }

/**
 * The record kept for every document JARVIS has seen. It is the metadata index
 * entry: identity ([checksumSha256] for duplicate detection), provenance
 * ([originalUri]/[vaultPath]/[source]), and processing state ([status] plus the
 * [parserVersion]/[indexVersion] that let a later build know a re-parse is due).
 *
 * The original user file is never modified; [originalUri] points at it and
 * [vaultPath], when set, is a *copy* made with a safe file name.
 */
data class DocumentRecord(
    val id: String,
    val fileName: String,
    val displayName: String,
    val originalUri: String,
    val vaultPath: String? = null,
    val mimeType: String,
    val fileSize: Long,
    val checksumSha256: String,
    val importedAt: Long,
    val modifiedAt: Long,
    val parserVersion: Int,
    val indexVersion: Int,
    val status: DocumentStatus,
    val tags: List<String> = emptyList(),
    val source: DocumentSource = DocumentSource.CHAT_ATTACHMENT,
    val pageCount: Int? = null,
    val language: String? = null,
    val title: String? = null,
    val author: String? = null,
) {
    /** True once the document is searchable. */
    val isReady: Boolean get() = status == DocumentStatus.READY

    /** True while the document is only attached to the conversation, not saved. */
    val isTemporary: Boolean get() = source == DocumentSource.CHAT_ATTACHMENT && vaultPath.isNullOrBlank()
}

/** One logical part of a parsed document: a heading's body, or a page. */
data class DocumentSection(
    val title: String,
    val page: Int? = null,
    val text: String,
)

/** Document-level facts a parser could recover; all optional. */
data class ParsedMetadata(
    val title: String? = null,
    val author: String? = null,
    val pageCount: Int? = null,
    val language: String? = null,
    val extra: Map<String, String> = emptyMap(),
)

/**
 * The text a [DocumentParser] produced, kept structured so chunking can carry
 * the heading/page each passage came from into the citation.
 */
data class ParsedDocument(
    val documentId: String,
    val text: String,
    val sections: List<DocumentSection>,
    val metadata: ParsedMetadata = ParsedMetadata(),
)

/**
 * One retrievable passage of a document. [tokenEstimate] is an approximation
 * (~4 chars/token) used to keep the assembled context inside the model window.
 * [documentTitle]/[fileName]/[section]/[page] travel with the chunk so a hit can
 * always be cited back to its source.
 */
data class DocumentChunk(
    val id: String,
    val documentId: String,
    val text: String,
    val section: String = "",
    val page: Int? = null,
    val chunkIndex: Int,
    val tokenEstimate: Int,
    val documentTitle: String = "",
    val fileName: String = "",
) {
    /** How JARVIS names the source of this passage, e.g. "manuale.pdf — pagina 42". */
    val citation: String
        get() {
            val name = fileName.ifBlank { documentTitle }.ifBlank { documentId }
            return when {
                page != null -> "$name — pagina $page"
                section.isNotBlank() -> "$name — sezione $section"
                else -> name
            }
        }
}
