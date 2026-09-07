package com.simone.jarvismobile.core.agenda

import com.simone.jarvismobile.core.tools.StructuredToolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 4 §3/§7 — pure mapping
 * from an [AgendaQueryOutcome] to the [StructuredToolResult] evidence
 * `ListAgendaTool` (`app/`) attaches to its `ToolResult`. Extracted here
 * (not inline in the Android tool) so the exact EMPTY-vs-FAILURE
 * distinction the whole outcome taxonomy exists to enforce is a real,
 * running JVM test — this project has no Robolectric/instrumented infra to
 * exercise `ListAgendaTool` itself directly (same reasoning already
 * established for `GroundingGate`/`ToolLoopEvidence`/`PresentationGuard` in
 * earlier passages of this master plan).
 *
 * No revision/version field is ever populated: the current Markdown-file
 * storage does not track a per-entry revision, and PASSAGGIO 1 §6 is
 * explicit that this evidence must never fabricate one just to fill the
 * field — an absent revision stays absent (`StructuredToolResult.revision`
 * defaults to `null`).
 */
object AgendaEvidence {

    const val SOURCE_ID = "agenda_local"

    fun evidenceFor(
        outcome: AgendaQueryOutcome,
        requestedRange: String?,
        retrievedAt: Long,
    ): StructuredToolResult = when (outcome) {
        // § never SUCCESS_EMPTY on a genuine failure — the read never
        // happened, so there is nothing to call "empty".
        is AgendaQueryOutcome.Failure -> StructuredToolResult.sourceFailure(
            sourceId = SOURCE_ID,
            reasonCode = outcome.reasonCode,
            retryable = true,
        )
        is AgendaQueryOutcome.Success -> if (outcome.entries.isEmpty()) {
            StructuredToolResult.successEmpty(
                sourceId = SOURCE_ID,
                retrievedAt = retrievedAt,
                requestedRange = requestedRange,
            )
        } else {
            StructuredToolResult.successData(
                payload = JsonObject(
                    mapOf(
                        "count" to JsonPrimitive(outcome.entries.size),
                        "record_ids" to JsonArray(outcome.entries.map { JsonPrimitive(it.id) }),
                    ),
                ),
                sourceId = SOURCE_ID,
                retrievedAt = retrievedAt,
                coverage = requestedRange,
            )
        }
    }

    /**
     * § §1 — a machine-readable requested-range label, distinct from the
     * human-facing spoken sentence `ListAgendaTool` builds separately.
     * Day-granularity inclusive `[day, toDay]` is the exact equivalent of a
     * half-open `[day, toDay+1)` instant range at this granularity (see
     * [Agenda.filter]'s own doc comment) — no separate half-open
     * representation is introduced here, since one would produce identical
     * results while adding a second range shape to keep in sync.
     */
    fun requestedRangeLabel(day: LocalDate?, toDay: LocalDate?): String? = when {
        day != null && toDay != null -> "$day..$toDay"
        day != null -> day.toString()
        else -> null
    }
}
