package com.simone.jarvismobile.proactive

import com.simone.jarvismobile.core.proactive.TriggerEvidenceEntry
import com.simone.jarvismobile.core.proactive.TriggerEvidencePolicy
import com.simone.jarvismobile.core.proactive.TriggerEvidenceSource
import com.simone.jarvismobile.core.proactive.TriggerEvidenceStage
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * § JARVIS Implementation Master Plan — MICRO-PATCH 14.2.3 §4/§5. The single
 * owner of persistent trigger-diagnostic writes/reads — reused by every real
 * FIRST_UNLOCK/NEXT_ALARM/CONFIGURED_TIME producer, never a second store.
 * DEBUG EVIDENCE ONLY: [record] never throws and never blocks the real
 * trigger path it instruments (a diagnostic write failing must never look
 * like the trigger itself failing).
 */
@Singleton
class TriggerEvidenceStore @Inject constructor(
    private val dao: TriggerEvidenceDao,
) {
    /** Distinguishes receipts across a process restart — same pattern as [ProactiveManager]'s own `sessionDiagnosticId`. */
    val processSessionId: String = UUID.randomUUID().toString().take(8)

    suspend fun record(
        source: TriggerEvidenceSource,
        stage: TriggerEvidenceStage,
        detail: String? = null,
        now: Long = System.currentTimeMillis(),
    ) {
        runCatching {
            dao.insert(
                TriggerEvidenceRowEntity(
                    eventAtMs = now,
                    processSessionId = processSessionId,
                    source = source.name,
                    stage = stage.name,
                    detail = TriggerEvidencePolicy.sanitizeDetail(detail),
                ),
            )
            dao.pruneSource(source.name, TriggerEvidencePolicy.MAX_ENTRIES_PER_SOURCE)
        }
    }

    suspend fun recent(source: TriggerEvidenceSource, limit: Int = 20): List<TriggerEvidenceEntry> = runCatching {
        dao.recentBySource(source.name, limit).mapNotNull { it.toEntryOrNull() }
    }.getOrDefault(emptyList())

    suspend fun recentAll(limit: Int = 60): List<TriggerEvidenceEntry> = runCatching {
        dao.recentAll(limit).mapNotNull { it.toEntryOrNull() }
    }.getOrDefault(emptyList())

    suspend fun pruneOld(retentionDays: Long = RETENTION_DAYS, now: Long = System.currentTimeMillis()) = runCatching {
        dao.deleteOlderThan(now - retentionDays * DAY_MS)
    }

    private fun TriggerEvidenceRowEntity.toEntryOrNull(): TriggerEvidenceEntry? {
        val src = runCatching { TriggerEvidenceSource.valueOf(source) }.getOrNull() ?: return null
        val stg = runCatching { TriggerEvidenceStage.valueOf(stage) }.getOrNull() ?: return null
        return TriggerEvidenceEntry(eventAtMs, processSessionId, src, stg, detail)
    }

    private companion object {
        const val RETENTION_DAYS = 7L
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
