package com.simone.jarvismobile.core.memory

/**
 * Which tier a piece of conversational memory belongs to. This is a read-model
 * tag, not a fourth physical store: [WORKING] reads from the existing
 * `ConversationMemoryStore`, [EPISODIC] from a new bounded Room table,
 * [SEMANTIC]/[USER] both from the existing vault-backed `MemoryRecord` store
 * (distinguished by how a record was written, not by a second physical store —
 * see `docs/CONVERSATIONAL_ENGINE.md`).
 */
enum class MemoryTier {
    WORKING,
    EPISODIC,
    SEMANTIC,
    USER,
}

/**
 * One retrievable memory, normalised to a single shape regardless of which
 * tier/store it actually lives in. `MemoryEngine` (app layer) is the only
 * place that maps the real stores onto this type.
 *
 * [entityTriples] is a reserved, always-empty seam for a future
 * entity-relation-entity graph — no code writes to it yet.
 */
data class MemoryEntry(
    val id: String,
    val content: String,
    val type: String,
    val tier: MemoryTier,
    val timestamp: Long,
    val importance: Double,
    val source: String,
    val lastAccessed: Long,
    val entityTriples: List<String> = emptyList(),
)
