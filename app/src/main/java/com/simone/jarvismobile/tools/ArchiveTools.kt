package com.simone.jarvismobile.tools

import com.simone.jarvismobile.archive.ArchiveRepository
import com.simone.jarvismobile.core.archive.ArchiveKind
import com.simone.jarvismobile.core.archive.ArchiveStatus
import com.simone.jarvismobile.core.tools.SensitivityLevel
import com.simone.jarvismobile.core.tools.Tool
import com.simone.jarvismobile.core.tools.ToolPolicy
import com.simone.jarvismobile.core.tools.ToolResult
import com.simone.jarvismobile.document.DocumentImportManager
import com.simone.jarvismobile.memory.MemoryIndex
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Modalità Pro's tool layer for the personal archive (notes/to-watch),
 * personal memory search, and document search/attachment lookup. Same shape
 * as every other tool file in this package (see [KnowledgeTools], [AgendaTools]):
 * a thin, validated wrapper around an existing repository. `search_wiki` has
 * no separate tool here — [SearchKnowledgeTool] ("search_knowledge") already
 * does exactly that job; registering the same operation under two names would
 * be the duplication the task explicitly asks to avoid. TODO tools are not
 * here either: `add_task`/`list_agenda`/`complete_agenda`/`delete_agenda`
 * already cover create/read/update/delete for a Google-Tasks-style item.
 */

private fun okJson(vararg pairs: Pair<String, String>): ToolResult =
    ToolResult.Success(JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) }))

private fun JsonObject.text(key: String): String? =
    runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it.isNotBlank() }

private fun JsonObject.tagList(key: String): List<String> =
    text(key)?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()

// --- memory & document search ------------------------------------------------

/**
 * Free-text search over personal memory (Memory V2 / the vault), blended
 * lexical+semantic when an embedding model is available. Distinct from
 * [com.simone.jarvismobile.tools.SearchVaultTool] only in that it returns the
 * ranked chunks formatted for a spoken answer with citations, matching
 * [SearchKnowledgeTool]'s contract so the model handles both the same way.
 */
class SearchMemoryTool(private val memory: MemoryIndex) : Tool {
    override val name = "search_memory"
    override val description = "Cerca nella memoria personale (appunti salvati, note, promemoria)."
    override val policy = ToolPolicy.READ_ONLY
    override val sensitivity = SensitivityLevel.PERSONAL
    override val requiresNetwork = false
    override val timeoutMs = 5_000L

    override fun validate(arguments: JsonObject): String? =
        if (arguments.text("query") == null) "manca il campo 'query'" else null

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val query = arguments.text("query") ?: return ToolResult.Failure("missing_query")
        memory.ensureBuilt()
        val hits = runCatching { memory.retrieveSmart(query, limit = 4) }.getOrDefault(emptyList())
        if (hits.isEmpty()) {
            return okJson(
                "found" to "false",
                "spoken" to "Non trovo niente nella memoria su questo argomento.",
            )
        }
        val passages = hits.joinToString("\n\n") { "[${it.chunk.title}] ${it.chunk.text.take(400)}" }
        return okJson(
            "found" to "true",
            "passages" to passages,
            "spoken" to "${hits.first().chunk.text.take(500)} (da: ${hits.first().chunk.title})",
        )
    }
}

/** Free-text search over imported documents/photos (OCR'd text), citing the source file. */
class SearchDocumentsTool(private val documents: DocumentImportManager) : Tool {
    override val name = "search_documents"
    override val description = "Cerca nei documenti e nelle foto importate (PDF, testo, immagini con testo OCR)."
    override val policy = ToolPolicy.READ_ONLY
    override val sensitivity = SensitivityLevel.PERSONAL
    override val requiresNetwork = false
    override val timeoutMs = 5_000L

    override fun validate(arguments: JsonObject): String? =
        if (arguments.text("query") == null) "manca il campo 'query'" else null

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val query = arguments.text("query") ?: return ToolResult.Failure("missing_query")
        if (!documents.hasReadyDocuments()) {
            return okJson(
                "found" to "false",
                "spoken" to "Non ho ancora documenti importati e pronti da cercare.",
            )
        }
        val evidence = documents.documentEvidence(query)
        return if (evidence.isNullOrBlank()) {
            okJson("found" to "false", "spoken" to "Nei documenti importati non trovo niente su questo.")
        } else {
            okJson("found" to "true", "passages" to evidence, "spoken" to evidence.take(500))
        }
    }
}

/** A specific attachment by name ("cosa c'è scritto nel PDF del contratto?"), not a relevance search. */
class GetAttachmentContextTool(private val documents: DocumentImportManager) : Tool {
    override val name = "get_attachment_context"
    override val description = "Legge il contenuto di un documento/foto specifico indicato per nome."
    override val policy = ToolPolicy.READ_ONLY
    override val sensitivity = SensitivityLevel.PERSONAL
    override val requiresNetwork = false
    override val timeoutMs = 5_000L

    override fun validate(arguments: JsonObject): String? =
        if (arguments.text("name") == null) "manca il campo 'name'" else null

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val nameQuery = arguments.text("name") ?: return ToolResult.Failure("missing_name")
        val found = documents.contextFor(nameQuery)
            ?: return okJson(
                "found" to "false",
                "spoken" to "Non trovo un solo documento chiamato così. Prova a essere più preciso.",
            )
        val (record, text) = found
        return okJson(
            "found" to "true",
            "document" to record.displayName,
            "content" to text,
            "spoken" to text.take(500),
        )
    }
}

// --- notes ------------------------------------------------------------------

class CreateNoteTool(private val archive: ArchiveRepository) : Tool {
    override val name = "create_note"
    override val description = "Crea una nota personale con titolo e contenuto."
    override val policy = ToolPolicy.LOW_RISK_WRITE
    override val sensitivity = SensitivityLevel.PERSONAL
    override val requiresNetwork = false
    override val timeoutMs = 5_000L

    override fun validate(arguments: JsonObject): String? {
        val title = arguments.text("title") ?: return "manca il campo 'title'"
        if (title.length < 2) return "titolo troppo corto"
        return null
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val title = arguments.text("title") ?: return ToolResult.Failure("missing_title")
        val content = arguments.text("content") ?: ""
        val tags = arguments.tagList("tags")
        val saved = archive.create(ArchiveKind.NOTE, title, content, tags)
            ?: return ToolResult.Failure("archive_write_failed")
        return okJson("id" to saved.id, "title" to saved.title, "spoken" to "Nota creata: ${saved.title}.")
    }
}

/** Reads a note by (fragment of) its title; fails closed on ambiguity, same rule as delete. */
class ReadNoteTool(private val archive: ArchiveRepository) : Tool {
    override val name = "read_note"
    override val description = "Legge una nota personale in base al titolo (o a un frammento)."
    override val policy = ToolPolicy.READ_ONLY
    override val sensitivity = SensitivityLevel.PERSONAL
    override val requiresNetwork = false
    override val timeoutMs = 5_000L

    override fun validate(arguments: JsonObject): String? =
        if (arguments.text("title") == null) "manca il campo 'title'" else null

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val needle = arguments.text("title") ?: return ToolResult.Failure("missing_title")
        val matches = archive.findByText(needle, ArchiveKind.NOTE)
        return when {
            matches.isEmpty() -> okJson(
                "found" to "false",
                "spoken" to "Non trovo una nota chiamata così.",
            )
            matches.size > 1 -> okJson(
                "found" to "false",
                "ambiguous" to "true",
                "spoken" to "Ho trovato più note simili: " +
                    matches.joinToString(", ") { it.title } + ". Quale intendi?",
            )
            else -> {
                val note = matches.single()
                okJson(
                    "found" to "true",
                    "title" to note.title,
                    "content" to note.content,
                    "spoken" to "${note.title}: ${note.content.take(500)}",
                )
            }
        }
    }
}

class UpdateNoteTool(private val archive: ArchiveRepository) : Tool {
    override val name = "update_note"
    override val description = "Modifica il contenuto (o il titolo) di una nota esistente, indicata per titolo."
    override val policy = ToolPolicy.LOW_RISK_WRITE
    override val sensitivity = SensitivityLevel.PERSONAL
    override val requiresNetwork = false
    override val timeoutMs = 5_000L

    override fun validate(arguments: JsonObject): String? {
        arguments.text("title") ?: return "manca il campo 'title'"
        if (arguments.text("new_content") == null && arguments.text("new_title") == null) {
            return "manca 'new_content' o 'new_title'"
        }
        return null
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val needle = arguments.text("title") ?: return ToolResult.Failure("missing_title")
        val match = archive.findByText(needle, ArchiveKind.NOTE).singleOrNull()
            ?: return okJson(
                "found" to "false",
                "spoken" to "Non trovo una sola nota chiamata così, quindi non la modifico.",
            )
        val updated = archive.update(
            match.id,
            title = arguments.text("new_title"),
            content = arguments.text("new_content"),
        ) ?: return ToolResult.Failure("archive_write_failed")
        return okJson("id" to updated.id, "spoken" to "Nota «${updated.title}» aggiornata.")
    }
}

class DeleteNoteTool(private val archive: ArchiveRepository) : Tool {
    override val name = "delete_note"
    override val description = "Elimina una nota personale indicata per titolo."
    override val policy = ToolPolicy.CONFIRMING_WRITE
    override val sensitivity = SensitivityLevel.PERSONAL
    override val requiresNetwork = false
    override val timeoutMs = 5_000L

    override fun validate(arguments: JsonObject): String? =
        if (arguments.text("title") == null) "manca il campo 'title'" else null

    override fun confirmationPrompt(arguments: JsonObject): String? =
        arguments.text("title")?.let { "Confermi di voler eliminare la nota «$it»?" }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val needle = arguments.text("title") ?: return ToolResult.Failure("missing_title")
        val removed = archive.deleteByText(needle, ArchiveKind.NOTE)
            ?: return okJson(
                "found" to "false",
                "spoken" to "Non trovo una sola nota chiamata così, quindi non ho eliminato nulla.",
            )
        return okJson("spoken" to "Nota «${removed.title}» eliminata.")
    }
}

// --- to-watch -----------------------------------------------------------------

class CreateWatchItemTool(private val archive: ArchiveRepository) : Tool {
    override val name = "create_watch_item"
    override val description = "Aggiunge qualcosa alla lista 'da vedere/leggere/ascoltare' (film, serie, libro, ecc.)."
    override val policy = ToolPolicy.LOW_RISK_WRITE
    override val sensitivity = SensitivityLevel.PERSONAL
    override val requiresNetwork = false
    override val timeoutMs = 5_000L

    override fun validate(arguments: JsonObject): String? {
        val title = arguments.text("title") ?: return "manca il campo 'title'"
        if (title.length < 2) return "titolo troppo corto"
        return null
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val title = arguments.text("title") ?: return ToolResult.Failure("missing_title")
        val type = arguments.text("type") ?: ""
        val note = arguments.text("note") ?: ""
        val link = arguments.text("link") ?: ""
        val saved = archive.create(ArchiveKind.TO_WATCH, title, content = note, watchType = type, link = link)
            ?: return ToolResult.Failure("archive_write_failed")
        val kind = if (type.isNotBlank()) " ($type)" else ""
        return okJson("id" to saved.id, "spoken" to "Aggiunto alle cose da vedere: ${saved.title}$kind.")
    }
}

/** Updates or completes a to-watch item, found by (fragment of) its title. */
class UpdateWatchItemTool(private val archive: ArchiveRepository) : Tool {
    override val name = "update_watch_item"
    override val description = "Modifica o segna come completato un elemento della lista da vedere, per titolo."
    override val policy = ToolPolicy.LOW_RISK_WRITE
    override val sensitivity = SensitivityLevel.PERSONAL
    override val requiresNetwork = false
    override val timeoutMs = 5_000L

    override fun validate(arguments: JsonObject): String? =
        if (arguments.text("title") == null) "manca il campo 'title'" else null

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val needle = arguments.text("title") ?: return ToolResult.Failure("missing_title")
        val match = archive.findByText(needle, ArchiveKind.TO_WATCH).singleOrNull()
            ?: return okJson(
                "found" to "false",
                "spoken" to "Non trovo un solo elemento chiamato così nella lista da vedere.",
            )
        val markDone = arguments.text("completed") == "true"
        val updated = if (markDone) {
            archive.update(match.id, status = ArchiveStatus.DONE)
        } else {
            archive.update(
                match.id,
                title = arguments.text("new_title"),
                content = arguments.text("note"),
                link = arguments.text("link"),
            )
        } ?: return ToolResult.Failure("archive_write_failed")
        val spoken = if (markDone) "Segnato come completato: ${updated.title}." else "«${updated.title}» aggiornato."
        return okJson("id" to updated.id, "spoken" to spoken)
    }
}
