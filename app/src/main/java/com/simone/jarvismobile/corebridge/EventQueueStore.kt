package com.simone.jarvismobile.corebridge

import android.util.Log
import com.simone.jarvismobile.core.bridge.EventQueuePolicy
import com.simone.jarvismobile.core.bridge.JarvisEvent
import com.simone.jarvismobile.core.bridge.JarvisEventType
import com.simone.jarvismobile.core.bridge.EventPriority
import com.simone.jarvismobile.core.bridge.QueuedEvent
import com.simone.jarvismobile.core.tools.SensitivityLevel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything [EventBridge] needs from the persisted queue — split out from
 * [EventQueueStore] (§ convenzione del progetto "Interfaces first... Fakes
 * for tests") so [EventBridge]'s publish/flush logic is unit-testable with
 * an in-memory fake instead of requiring a real Android `Context`/file
 * system, which nothing in this JVM-only test environment can provide.
 */
interface EventQueue {
    suspend fun enqueue(event: JarvisEvent)
    suspend fun peekAll(): List<QueuedEvent>
    suspend fun removeDelivered(ids: Set<String>)
}

/**
 * Flat-file persisted retry queue for [JarvisEvent]s awaiting delivery to
 * JARVIS Core, mirroring `CloudBackupProvider`/`CloudSyncManager`'s existing
 * plain-text-file precedent (no new Room table — DB stays at v9) but adding
 * the staleness/capacity eviction that precedent doesn't have, via
 * [EventQueuePolicy.prune] (§ richiesta esplicita: "NON conservare
 * indefinitamente eventi sensibili... elimina automaticamente eventi
 * vecchi/non più utili").
 *
 * One JSON object per line under `filesDir/corebridge/event_queue.jsonl` —
 * the same one-record-per-line shape already used elsewhere in this project
 * for append-friendly local logs.
 */
@Singleton
class EventQueueStore @Inject constructor(
    @ApplicationContext context: android.content.Context,
) : EventQueue {
    private val json = Json { ignoreUnknownKeys = true }
    private val file = File(context.filesDir, "corebridge/event_queue.jsonl").apply {
        parentFile?.mkdirs()
    }
    private val mutex = Mutex()

    override suspend fun enqueue(event: JarvisEvent) {
        mutex.withLock {
            val current = readAllLocked()
            val next = EventQueuePolicy.prune(
                current + QueuedEvent(event, enqueuedAtMs = System.currentTimeMillis()),
                nowMs = System.currentTimeMillis(),
                maxQueueSize = MAX_QUEUE_SIZE,
            )
            writeAllLocked(next)
        }
    }

    /** Current queue after pruning expired entries — never mutates the file, callers decide what to do with the result. */
    override suspend fun peekAll(): List<QueuedEvent> = mutex.withLock {
        val pruned = EventQueuePolicy.prune(readAllLocked(), System.currentTimeMillis(), MAX_QUEUE_SIZE)
        writeAllLocked(pruned)
        pruned
    }

    override suspend fun removeDelivered(ids: Set<String>) {
        if (ids.isEmpty()) return
        mutex.withLock {
            writeAllLocked(readAllLocked().filterNot { it.event.id in ids })
        }
    }

    private fun readAllLocked(): List<QueuedEvent> {
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull { line -> runCatching { json.decodeFromString(StoredQueuedEvent.serializer(), line) }.getOrNull() }
                .map { it.toDomain() }
        }.getOrElse { e ->
            Log.w(TAG, "event_queue_read_failed ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    private fun writeAllLocked(events: List<QueuedEvent>) {
        runCatching {
            file.writeText(events.joinToString("\n") { json.encodeToString(StoredQueuedEvent.serializer(), StoredQueuedEvent.from(it)) })
        }.onFailure { e -> Log.w(TAG, "event_queue_write_failed ${e.javaClass.simpleName}") }
    }

    @Serializable
    private data class StoredQueuedEvent(
        val id: String,
        val type: String,
        val timestampMs: Long,
        val source: String,
        val priority: String,
        val privacyLevel: String,
        val payload: Map<String, String>,
        val enqueuedAtMs: Long,
    ) {
        fun toDomain(): QueuedEvent = QueuedEvent(
            event = JarvisEvent(
                id = id,
                type = runCatching { JarvisEventType.valueOf(type) }.getOrDefault(JarvisEventType.DEVICE_STATE_CHANGED),
                timestampMs = timestampMs,
                source = source,
                priority = runCatching { EventPriority.valueOf(priority) }.getOrDefault(EventPriority.NORMAL),
                privacyLevel = runCatching { SensitivityLevel.valueOf(privacyLevel) }.getOrDefault(SensitivityLevel.PERSONAL),
                payload = payload,
            ),
            enqueuedAtMs = enqueuedAtMs,
        )

        companion object {
            fun from(q: QueuedEvent) = StoredQueuedEvent(
                id = q.event.id,
                type = q.event.type.name,
                timestampMs = q.event.timestampMs,
                source = q.event.source,
                priority = q.event.priority.name,
                privacyLevel = q.event.privacyLevel.name,
                payload = q.event.payload,
                enqueuedAtMs = q.enqueuedAtMs,
            )
        }
    }

    private companion object {
        const val TAG = "EventQueueStore"
        const val MAX_QUEUE_SIZE = 200
    }
}
