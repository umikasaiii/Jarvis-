package com.simone.jarvismobile.core.notes

import com.simone.jarvismobile.core.archive.ArchiveItem
import com.simone.jarvismobile.core.archive.ArchiveKind
import com.simone.jarvismobile.core.memory.MemoryKind
import com.simone.jarvismobile.core.memory.MemoryRecord

/** Which store really owns a note. Edits are always routed back to this store. */
enum class NoteSource { ARCHIVE, MEMORIA }

/** Original identity: the id is the owning store's id, never a new one. */
data class NoteRef(val source: NoteSource, val id: String) {
    val key: String get() = "${source.name}:$id"
}

/**
 * A *view* over one note held by one of the existing stores (BETA 3). It is
 * never persisted: there is no second copy of the content.
 */
data class NoteEntry(
    val ref: NoteRef,
    val title: String,
    val content: String,
    val folder: String,
    val pinned: Boolean,
    val sensitive: Boolean,
    val theme: String,
    val spacing: String,
    val createdAt: Long,
    val updatedAt: Long,
) {
    /** Only the Archive store keeps a pin flag; Memoria records cannot be pinned. */
    val canPin: Boolean get() = ref.source == NoteSource.ARCHIVE
}

object NotesFeed {
    const val UNFILED = ""

    fun fromArchive(item: ArchiveItem): NoteEntry? {
        if (item.kind != ArchiveKind.NOTE) return null
        return NoteEntry(
            ref = NoteRef(NoteSource.ARCHIVE, item.id),
            title = item.title, content = item.content, folder = item.folder,
            pinned = item.pinned, sensitive = false, theme = item.theme, spacing = item.spacing,
            createdAt = item.createdAt, updatedAt = item.updatedAt,
        )
    }

    /**
     * Conversation-only (TEMPORARY) memory is assistant working state and is
     * never shown as a note. PERMANENT and SENSITIVE records the user can see
     * and edit in Memoria are shown, SENSITIVE flagged.
     */
    fun fromMemory(record: MemoryRecord): NoteEntry? {
        if (record.kind == MemoryKind.TEMPORARY) return null
        return NoteEntry(
            ref = NoteRef(NoteSource.MEMORIA, record.id),
            title = titleFrom(record.text), content = record.text, folder = record.category,
            pinned = false, sensitive = record.kind == MemoryKind.SENSITIVE,
            theme = record.theme, spacing = record.spacing,
            createdAt = record.createdAt, updatedAt = record.updatedAt,
        )
    }

    /** First non-blank line without markup noise, capped; never empty. */
    fun titleFrom(text: String): String {
        val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() } ?: return "Nota senza titolo"
        val clean = line.trimStart('#', '-', '*', ' ').replace(Regex("""\[ ?[xX]? ?]"""), "").trim()
        return clean.ifBlank { "Nota senza titolo" }.take(60)
    }

    /** Union of both stores, each ref at most once (first occurrence wins). */
    fun merge(archive: List<ArchiveItem>, memory: List<MemoryRecord>): List<NoteEntry> {
        val all = archive.mapNotNull(::fromArchive) + memory.mapNotNull(::fromMemory)
        val seen = HashSet<String>()
        return all.filter { seen.add(it.ref.key) }
    }

    /** Pinned first, then most recently edited. */
    fun sorted(notes: List<NoteEntry>): List<NoteEntry> =
        notes.sortedWith(compareByDescending<NoteEntry> { it.pinned }.thenByDescending { it.updatedAt }.thenBy { it.ref.key })

    /** [folder] null = all; [UNFILED] = notes without a folder. */
    fun filter(notes: List<NoteEntry>, query: String, folder: String?): List<NoteEntry> {
        val q = query.trim()
        return notes.filter { n ->
            (folder == null || n.folder == folder) &&
                (q.isEmpty() || n.title.contains(q, ignoreCase = true) || n.content.contains(q, ignoreCase = true))
        }
    }

    fun folders(notes: List<NoteEntry>): List<String> =
        notes.map { it.folder }.filter { it.isNotBlank() }.distinct().sorted()
}

/** What the editor should do when the user leaves or pauses typing. */
sealed interface NoteSaveDecision {
    /** Nothing worth keeping (new + empty) or nothing changed. */
    data object Skip : NoteSaveDecision
    data class Save(val title: String, val content: String) : NoteSaveDecision
}

/**
 * Autosave policy: a note is never lost because its title is blank (the title
 * is derived from the text), never created empty, and never rewritten when
 * unchanged.
 */
object NoteSavePolicy {
    fun decide(existing: NoteEntry?, title: String, content: String, folder: String, pinned: Boolean, theme: String, spacing: String): NoteSaveDecision {
        val t = title.trim()
        val c = content
        if (existing == null && t.isEmpty() && c.isBlank()) return NoteSaveDecision.Skip
        val effectiveTitle = t.ifEmpty { NotesFeed.titleFrom(c) }
        if (existing != null &&
            existing.title == effectiveTitle && existing.content == c && existing.folder == folder &&
            existing.pinned == pinned && existing.theme == theme && existing.spacing == spacing
        ) return NoteSaveDecision.Skip
        return NoteSaveDecision.Save(effectiveTitle, c)
    }
}
