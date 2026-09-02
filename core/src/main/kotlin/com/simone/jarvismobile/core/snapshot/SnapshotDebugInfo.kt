package com.simone.jarvismobile.core.snapshot

/**
 * Developer-facing summary of one selection — counts and categories only,
 * NEVER personal content (§ richiesta esplicita: "NON mostrare per default
 * contenuti personali completi nei log"). [describe] reproduces the exact
 * format requested:
 * ```
 * ContextSelection:
 * TEMPORAL → selected
 * AGENDA → selected
 * DRIVING → skipped
 * DEVICE → skipped
 * MEMORY → 2 items
 * Size → 1430 chars
 * ```
 */
data class SnapshotDebugInfo(
    val snapshotId: String,
    val sourcesAvailable: Set<String>,
    val sourcesMissing: Set<String>,
    val ageMs: Long,
    val selected: Set<SelectionCategory>,
    val skipped: Set<SelectionCategory>,
    val memoryItemCount: Int,
    val recentEventCount: Int,
    val approxSizeChars: Int,
) {
    fun describe(): String {
        val lines = mutableListOf("ContextSelection:")
        for (category in SelectionCategory.entries) {
            val line = when {
                category == SelectionCategory.MEMORY && category in selected -> "MEMORY → $memoryItemCount items"
                category == SelectionCategory.RECENT_EVENTS && category in selected -> "RECENT_EVENTS → $recentEventCount events"
                category in selected -> "$category → selected"
                else -> "$category → skipped"
            }
            lines += line
        }
        lines += "Size → $approxSizeChars chars"
        return lines.joinToString("\n")
    }

    companion object {
        fun from(snapshot: PersonalIntelligenceSnapshot, selection: RelevantPersonalContext, ageMs: Long): SnapshotDebugInfo = SnapshotDebugInfo(
            snapshotId = snapshot.snapshotId,
            sourcesAvailable = snapshot.sourceSummary.available,
            sourcesMissing = snapshot.sourceSummary.missing,
            ageMs = ageMs,
            selected = selection.selected,
            skipped = selection.skipped,
            memoryItemCount = selection.memory?.items?.size ?: 0,
            recentEventCount = selection.recentEvents?.events?.size ?: 0,
            approxSizeChars = selection.approxSizeChars,
        )
    }
}
