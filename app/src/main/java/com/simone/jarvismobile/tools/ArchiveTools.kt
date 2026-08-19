package com.simone.jarvismobile.tools

import com.simone.jarvismobile.archive.ArchiveListRepository
import com.simone.jarvismobile.archive.ArchiveRepository
import com.simone.jarvismobile.archive.PersonalArchiveSearch
import com.simone.jarvismobile.core.archive.ArchiveKind
import com.simone.jarvismobile.core.archive.ArchiveStatus
import com.simone.jarvismobile.core.archive.ListItemStatus
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
 * The Local LLM's tool layer for the three separate layers the Personal
 * Archive spec asks for: KNOWLEDGE (untouched — [SearchKnowledgeTool] already
 * covers it, registered as "search_knowledge"), PERSONAL ARCHIVE (notes,
 * to-watch, shopping/custom lists — [ArchiveRepository]/[ArchiveListRepository]),
 * and PERSONAL DOCUMENTS ([DocumentImportManager]). Same shape as every other
 * tool file in this package (see [KnowledgeTools], [AgendaTools]): a thin,
 * validated wrapper around an existing repository — NORMAL mode's
 * [CommandMatcher] calls the exact same [Tool]s through [ToolRunner], so
 * there is one implementation behind both routing paths, never two.
 *
 * TODO tools are not here: `add_task`/`list_agenda`/`complete_agenda`/
 * `delete_agenda` already cover create/read/update/delete for a
 * Google-Tasks-style item — duplicating that under `create_archive_item`
 * would be exactly the parallel implementation the spec explicitly forbids.
 */

private fun okJson(vararg pairs: Pair<String, String>): ToolResult =
    ToolResult.Success(JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) }))

private fun JsonObject.text(key: String): String? =
    runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it.isNotBlank() }

private fun JsonObject.tagList(key: String): List<String> =
    text(key)?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()

/** "note" (default) or "to_watch" — every generic archive-item tool accepts this. */
private fun JsonObject.archiveKind(): ArchiveKind =
    if (text("type")?.lowercase()?.let { it.contains("watch") || it.contains("vedere") } == true) {
        ArchiveKind.TO_WATCH
    } else {
        ArchiveKind.NOTE
    }

// --- memory & document search ------------------------------------------------

/**
 * Free-text search over personal memory (Memory V2 / the vault) — distinct
 * from [SearchArchiveTool]: Memory V2 is the older "ricordati che…"
 * quick-fact vault (docs/PRO_MODE.md), the Personal Archive is notes/lists
 * created explicitly through the Archivio UI or "crea una nota"/"aggiungi
 * alla lista". Both remain queryable; the model picks based on the question.
 */
class SearchMemoryTool(private val memory: MemoryIndex) : Tool {
    override val name = "search_memory"
    override val description = "Cerca nella memoria personale (ricordati-che, promemoria salvati)."
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

/**
 * The Personal Archive layer's own search (spec §9/§10): notes, to-watch and
 * list items — never Memory V2, never documents, never the Wiki. Backs both
 * "cerca nel mio archivio X" (NORMAL, via [CommandMatcher]) and the model's
 * own tool call in Modalità Pro — one [PersonalArchiveSearch] behind both.
 */
class SearchArchiveTool(private val search: PersonalArchiveSearch) : Tool {
    override val name = "search_archive"
    override val description = "Cerca nel Personal Archive: appunti, liste, cose da vedere (non documenti, non la wiki)."
    override val policy = ToolPolicy.READ_ONLY
    override val sensitivity = SensitivityLevel.PERSONAL
    override val requiresNetwork = false
    override val timeoutMs = 5_000L

    override fun validate(arguments: JsonObject): String? =
        if (arguments.text("query") == null) "manca il campo 'query'" else null

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val query = arguments.text("query") ?: return ToolResult.Failure("missing_query")
        val hits = search.search(query)
        if (hits.isEmpty()) {
            return okJson("found" to "false", "spoken" to "Non trovo niente nel mio archivio su questo.")
        }
        val passages = hits.joinToString("\n\n") { "[${it.label}] ${it.snippet.take(300)}" }
        return okJson(
            "found" to "true",
            "passages" to passages,
            "spoken" to "${hits.first().snippet.take(400)} (da: ${hits.first().label})",
        )
    }
}

/** Free-text search over imported documents (PDF/testo/foto OCR), citing the source file+page/section. */
class SearchDocumentsTool(private val documents: DocumentImportManager) : Tool {
    override val name = "search_documents"
    override val description = "Cerca nei documenti personali importati (PDF, testo, immagini con testo OCR)."
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

/** A specific document by name ("cosa c'è scritto nel PDF del contratto?"), not a relevance search. */
class ReadDocumentContextTool(private val documents: DocumentImportManager) : Tool {
    override val name = "read_document_context"
    override val description = "Legge il contenuto di un documento specifico indicato per nome."
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

/** Photos/scans by name/tag/OCR text — narrower than [SearchDocumentsTool], images only. */
class SearchImagesTool(private val documents: DocumentImportManager) : Tool {
    override val name = "search_images"
    override val description = "Cerca tra le foto e scansioni importate, per nome, tag o testo OCR."
    override val policy = ToolPolicy.READ_ONLY
    override val sensitivity = SensitivityLevel.PERSONAL
    override val requiresNetwork = false
    override val timeoutMs = 5_000L

    override fun validate(arguments: JsonObject): String? =
        if (arguments.text("query") == null) "manca il campo 'query'" else null

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val query = arguments.text("query") ?: return ToolResult.Failure("missing_query")
        val hits = documents.searchImages(query)
        return if (hits.isEmpty()) {
            okJson("found" to "false", "spoken" to "Non trovo foto che corrispondano a questa ricerca.")
        } else {
            okJson(
                "found" to "true",
                "images" to hits.joinToString(", ") { it.displayName },
                "spoken" to "Ho trovato: " + hits.joinToString(", ") { it.displayName },
            )
        }
    }
}

// --- archive items: notes & to-watch (generic, type-parameterized) ----------

class CreateArchiveItemTool(private val archive: ArchiveRepository) : Tool {
    override val name = "create_archive_item"
    override val description =
        "Crea un elemento del Personal Archive: una nota (type=note, predefinito) o un elemento da vedere (type=to_watch)."
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
        val kind = arguments.archiveKind()
        val content = arguments.text("content") ?: arguments.text("description") ?: ""
        val tags = arguments.tagList("tags")
        val watchType = arguments.text("watch_type") ?: ""
        val link = arguments.text("link") ?: ""
        val saved = archive.create(kind, title, content, tags, watchType, link) ?: return ToolResult.Failure("archive_write_failed")
        val spoken = if (kind == ArchiveKind.TO_WATCH) {
            "Aggiunto alle cose da vedere: ${saved.title}."
        } else {
            "Nota creata: ${saved.title}."
        }
        return okJson("id" to saved.id, "title" to saved.title, "spoken" to spoken)
    }
}

/** Reads (or, with no title, lists) archive items of the given type. Fails closed on ambiguity for a specific title. */
class ReadArchiveItemTool(private val archive: ArchiveRepository) : Tool {
    override val name = "read_archive_item"
    override val description = "Legge un elemento del Personal Archive (nota o cosa-da-vedere) per titolo."
    override val policy = ToolPolicy.READ_ONLY
    override val sensitivity = SensitivityLevel.PERSONAL
    override val requiresNetwork = false
    override val timeoutMs = 5_000L

    override fun validate(arguments: JsonObject): String? = null

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val kind = arguments.archiveKind()
        val needle = arguments.text("title")
        if (needle == null) {
            val all = archive.byKind(kind)
            return if (all.isEmpty()) {
                okJson("found" to "false", "spoken" to "Non ho ancora nulla di questo tipo nell'archivio.")
            } else {
                okJson(
                    "found" to "true",
                    "items" to all.joinToString(", ") { it.title },
                    "spoken" to "Ho: " + all.joinToString(", ") { it.title },
                )
            }
        }
        val matches = archive.findByText(needle, kind)
        return when {
            matches.isEmpty() -> okJson("found" to "false", "spoken" to "Non trovo un elemento chiamato così.")
            matches.size > 1 -> okJson(
                "found" to "false",
                "ambiguous" to "true",
                "spoken" to "Ho trovato più elementi simili: " + matches.joinToString(", ") { it.title } + ". Quale intendi?",
            )
            else -> {
                val item = matches.single()
                okJson(
                    "found" to "true",
                    "title" to item.title,
                    "content" to item.content,
                    "spoken" to "${item.title}: ${item.content.ifBlank { item.watchType }.take(500)}",
                )
            }
        }
    }
}

class UpdateArchiveItemTool(private val archive: ArchiveRepository) : Tool {
    override val name = "update_archive_item"
    override val description = "Modifica un elemento del Personal Archive (titolo/contenuto/stato), indicato per titolo."
    override val policy = ToolPolicy.LOW_RISK_WRITE
    override val sensitivity = SensitivityLevel.PERSONAL
    override val requiresNetwork = false
    override val timeoutMs = 5_000L

    override fun validate(arguments: JsonObject): String? =
        if (arguments.text("title") == null) "manca il campo 'title'" else null

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val kind = arguments.archiveKind()
        val needle = arguments.text("title") ?: return ToolResult.Failure("missing_title")
        val match = archive.findByText(needle, kind).singleOrNull()
            ?: return okJson("found" to "false", "spoken" to "Non trovo un solo elemento chiamato così, quindi non lo modifico.")
        val markDone = arguments.text("completed") == "true"
        val status = if (markDone) ArchiveStatus.DONE else null
        val updated = archive.update(
            match.id,
            title = arguments.text("new_title"),
            content = arguments.text("new_content") ?: arguments.text("content"),
            status = status,
        ) ?: return ToolResult.Failure("archive_write_failed")
        val spoken = if (markDone) "Segnato come completato: ${updated.title}." else "«${updated.title}» aggiornato."
        return okJson("id" to updated.id, "spoken" to spoken)
    }
}

class DeleteArchiveItemTool(private val archive: ArchiveRepository) : Tool {
    override val name = "delete_archive_item"
    override val description = "Elimina un elemento del Personal Archive (nota o cosa-da-vedere), indicato per titolo."
    override val policy = ToolPolicy.CONFIRMING_WRITE
    override val sensitivity = SensitivityLevel.PERSONAL
    override val requiresNetwork = false
    override val timeoutMs = 5_000L

    override fun validate(arguments: JsonObject): String? =
        if (arguments.text("title") == null) "manca il campo 'title'" else null

    override fun confirmationPrompt(arguments: JsonObject): String? =
        arguments.text("title")?.let { "Confermi di voler eliminare «$it»?" }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val kind = arguments.archiveKind()
        val needle = arguments.text("title") ?: return ToolResult.Failure("missing_title")
        val removed = archive.deleteByText(needle, kind)
            ?: return okJson("found" to "false", "spoken" to "Non trovo un solo elemento chiamato così, quindi non ho eliminato nulla.")
        return okJson("spoken" to "«${removed.title}» eliminato.")
    }
}

// --- lists: shopping + custom (generic) --------------------------------------

/**
 * Lists items either of a named list (`list`, spec's Lists/Shopping) or of an
 * archive-item type (`type`=note/to_watch, delegating to [ReadArchiveItemTool]'s
 * listing branch) — exactly one of the two identifies what to list, covering
 * "cosa devo comprare"/"cosa c'è nella lista X" and "cosa devo vedere" with
 * one tool instead of two near-duplicates.
 */
class ListItemsTool(
    private val lists: ArchiveListRepository,
    private val archive: ArchiveRepository,
) : Tool {
    override val name = "list_items"
    override val description = "Elenca gli elementi di una lista (per nome) o del Personal Archive (per type=note/to_watch)."
    override val policy = ToolPolicy.READ_ONLY
    override val sensitivity = SensitivityLevel.PERSONAL
    override val requiresNetwork = false
    override val timeoutMs = 5_000L

    override fun validate(arguments: JsonObject): String? = null

    override suspend fun execute(arguments: JsonObject): ToolResult {
        arguments.text("list")?.let { listNeedle ->
            val (list, items) = lists.itemsOf(listNeedle)
                ?: return okJson("found" to "false", "spoken" to "Non trovo una lista chiamata così.")
            val open = items.filter { it.status == ListItemStatus.OPEN }
            return if (open.isEmpty()) {
                okJson("found" to "true", "spoken" to "La lista «${list.name}» è vuota.")
            } else {
                okJson(
                    "found" to "true",
                    "items" to open.joinToString(", ") { it.title },
                    "spoken" to "Nella lista «${list.name}»: " + open.joinToString(", ") { it.title },
                )
            }
        }
        val kind = arguments.archiveKind()
        val all = archive.byKind(kind).filter { it.status == ArchiveStatus.OPEN }
        return if (all.isEmpty()) {
            okJson("found" to "true", "spoken" to "Non ho ancora nulla di questo tipo.")
        } else {
            okJson(
                "found" to "true",
                "items" to all.joinToString(", ") { it.title },
                "spoken" to "Ho: " + all.joinToString(", ") { it.title },
            )
        }
    }
}

class CreateListTool(private val lists: ArchiveListRepository) : Tool {
    override val name = "create_list"
    override val description = "Crea una lista personalizzata con il nome indicato."
    override val policy = ToolPolicy.LOW_RISK_WRITE
    override val sensitivity = SensitivityLevel.PERSONAL
    override val requiresNetwork = false
    override val timeoutMs = 5_000L

    override fun validate(arguments: JsonObject): String? =
        if (arguments.text("name") == null) "manca il campo 'name'" else null

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val name = arguments.text("name") ?: return ToolResult.Failure("missing_name")
        val list = lists.createList(name) ?: return ToolResult.Failure("archive_list_write_failed")
        return okJson("id" to list.id, "name" to list.name, "spoken" to "Lista «${list.name}» creata.")
    }
}

class AddListItemTool(private val lists: ArchiveListRepository) : Tool {
    override val name = "add_list_item"
    override val description =
        "Aggiunge un elemento a una lista (per la spesa usa list=\"spesa\"). Argomenti: list, title, quantity, priority, notes, link."
    override val policy = ToolPolicy.LOW_RISK_WRITE
    override val sensitivity = SensitivityLevel.PERSONAL
    override val requiresNetwork = false
    override val timeoutMs = 5_000L

    override fun validate(arguments: JsonObject): String? {
        arguments.text("list") ?: return "manca il campo 'list'"
        val title = arguments.text("title") ?: return "manca il campo 'title'"
        if (title.length < 2) return "titolo troppo corto"
        return null
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val listNeedle = arguments.text("list") ?: return ToolResult.Failure("missing_list")
        val title = arguments.text("title") ?: return ToolResult.Failure("missing_title")
        val quantity = arguments.text("quantity")?.toIntOrNull()
        val priority = arguments.text("priority") ?: ""
        val notes = arguments.text("notes") ?: ""
        val link = arguments.text("link") ?: ""
        val result = lists.addItem(listNeedle, title, quantity, priority, notes, link)
            ?: return ToolResult.Failure("archive_list_write_failed")
        val (list, item) = result
        val qty = quantity?.let { " (x$it)" } ?: ""
        return okJson("id" to item.id, "spoken" to "Aggiunto a «${list.name}»: ${item.title}$qty.")
    }
}

class UpdateListItemTool(private val lists: ArchiveListRepository) : Tool {
    override val name = "update_list_item"
    override val description =
        "Modifica o segna come completato un elemento di una lista, per nome della lista e dell'elemento."
    override val policy = ToolPolicy.LOW_RISK_WRITE
    override val sensitivity = SensitivityLevel.PERSONAL
    override val requiresNetwork = false
    override val timeoutMs = 5_000L

    override fun validate(arguments: JsonObject): String? {
        arguments.text("list") ?: return "manca il campo 'list'"
        arguments.text("item") ?: return "manca il campo 'item'"
        return null
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val listNeedle = arguments.text("list") ?: return ToolResult.Failure("missing_list")
        val itemNeedle = arguments.text("item") ?: return ToolResult.Failure("missing_item")
        val markDone = arguments.text("completed") == "true"
        val updated = if (markDone) {
            lists.completeItem(listNeedle, itemNeedle, true)
        } else {
            lists.updateItem(
                listNeedle,
                itemNeedle,
                title = arguments.text("new_title"),
                quantity = arguments.text("quantity")?.toIntOrNull(),
                priority = arguments.text("priority"),
                notes = arguments.text("notes"),
                link = arguments.text("link"),
            )
        } ?: return okJson("found" to "false", "spoken" to "Non trovo un solo elemento chiamato così in quella lista.")
        val spoken = if (markDone) "Segnato: ${updated.title}." else "«${updated.title}» aggiornato."
        return okJson("id" to updated.id, "spoken" to spoken)
    }
}

class RemoveListItemTool(private val lists: ArchiveListRepository) : Tool {
    override val name = "remove_list_item"
    override val description = "Rimuove un elemento da una lista, per nome della lista e dell'elemento."
    override val policy = ToolPolicy.LOW_RISK_WRITE
    override val sensitivity = SensitivityLevel.PERSONAL
    override val requiresNetwork = false
    override val timeoutMs = 5_000L

    override fun validate(arguments: JsonObject): String? {
        arguments.text("list") ?: return "manca il campo 'list'"
        arguments.text("item") ?: return "manca il campo 'item'"
        return null
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val listNeedle = arguments.text("list") ?: return ToolResult.Failure("missing_list")
        val itemNeedle = arguments.text("item") ?: return ToolResult.Failure("missing_item")
        val removed = lists.removeItem(listNeedle, itemNeedle)
            ?: return okJson("found" to "false", "spoken" to "Non trovo un solo elemento chiamato così in quella lista.")
        return okJson("spoken" to "«${removed.title}» rimosso.")
    }
}
