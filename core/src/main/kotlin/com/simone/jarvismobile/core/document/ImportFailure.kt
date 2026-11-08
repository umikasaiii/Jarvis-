package com.simone.jarvismobile.core.document

/**
 * Why a document could not be made searchable (BETA 4). Stored as an `err:CODE` tag on the
 * [DocumentRecord] (no Room schema change) so the archive can say it out loud instead of
 * showing a bare "FAILED".
 */
enum class ImportFailure(val message: String) {
    EMPTY("File vuoto: non contiene nulla da leggere."),
    TOO_LARGE("File troppo grande: il limite è ${DocumentFormatPolicy.LIMIT_LABEL} per file."),
    UNSUPPORTED("Formato non supportato. Formati: PDF, DOCX, EPUB, XLSX, TXT, Markdown, HTML, CSV, JSON e immagini."),
    UNREADABLE("Impossibile leggere il file (accesso negato o file spostato)."),
    CORRUPT("File danneggiato o non corrispondente al formato indicato."),
    SCANNED_PDF(
        "PDF scansionato: contiene solo immagini e serve l'OCR, che per i PDF non è disponibile. " +
            "Il file è conservato ma non è ricercabile.",
    ),
    NO_TEXT("Nessun testo estraibile da questo file."),
    INTERRUPTED("Importazione interrotta prima della fine. Importa di nuovo il file."),
    OUT_OF_MEMORY("Memoria insufficiente per elaborare questo file: prova con un file più piccolo."),
    ;

    val tag: String get() = TAG_PREFIX + name

    companion object {
        const val TAG_PREFIX = "err:"

        fun fromTags(tags: List<String>): ImportFailure? =
            tags.firstOrNull { it.startsWith(TAG_PREFIX) }
                ?.removePrefix(TAG_PREFIX)
                ?.let { code -> entries.firstOrNull { it.name == code } }

        /** [tags] with any previous failure reason replaced by [failure] (or removed when null). */
        fun withReason(tags: List<String>, failure: ImportFailure?): List<String> =
            tags.filterNot { it.startsWith(TAG_PREFIX) } + listOfNotNull(failure?.tag)
    }
}

/** After a crash or process kill an import can be left half-way; those records must not stay "in progress" forever. */
object ImportRecovery {
    private val inProgress = setOf(DocumentStatus.SELECTED, DocumentStatus.COPYING, DocumentStatus.PARSING, DocumentStatus.INDEXING)

    /** Records that claim to be importing but have no running job: they are now FAILED/INTERRUPTED. */
    fun interrupted(records: List<DocumentRecord>, activeIds: Set<String>): List<DocumentRecord> =
        records.filter { it.status in inProgress && it.id !in activeIds }.map {
            it.copy(status = DocumentStatus.FAILED, tags = ImportFailure.withReason(it.tags, ImportFailure.INTERRUPTED))
        }
}
