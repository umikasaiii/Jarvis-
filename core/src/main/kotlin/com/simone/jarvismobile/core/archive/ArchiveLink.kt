package com.simone.jarvismobile.core.archive

/**
 * A typed pointer to any archive entity — a note, a list item, a document, a
 * to-watch item — so [ArchiveLink] can connect two of them without a foreign
 * key into four different tables. `type` is a free string ("note",
 * "list_item", "document", "to_watch") rather than a shared enum: the app
 * layer's document records live in `core/document`, outside this module, and
 * this module must not depend on it just to name a relation.
 */
data class ArchiveRef(val type: String, val id: String)

/**
 * "Nota Assicurazione moto" ↔ documento "assicurazione_moto.pdf" (spec §13).
 * Undirected in meaning but stored as one row; [from]/[to] is just which side
 * was linked first.
 */
data class ArchiveLink(
    val id: String,
    val from: ArchiveRef,
    val to: ArchiveRef,
    val createdAt: Long,
) {
    init {
        require(from != to) { "an archive item cannot be linked to itself" }
    }
}
