package com.simone.jarvismobile.snapshot.providers

import com.simone.jarvismobile.core.snapshot.MemoryContext
import com.simone.jarvismobile.core.snapshot.MemoryContextItem
import com.simone.jarvismobile.memory.MemoryIndex
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deliberately NOT query-specific RAG (§ richiesta esplicita: "se non
 * esiste ancora un retrieval contestuale affidabile, lasciare il campo
 * predisposto e utilizzare solo ciò che può essere recuperato in
 * sicurezza"). True query-relevant retrieval already lives in
 * `engine/MemoryEngine`/`engine/ContextAssembler` and stays untouched
 * (§ vincolo esplicito: no new RAG this phase) — this provider only reads
 * [MemoryIndex.listMemories], a small, general, already-existing method,
 * never the whole store.
 */
fun interface MemoryContextProvider {
    suspend fun provide(): MemoryContext?
}

private const val PROVIDER_MAX_ITEMS = 10

@Singleton
class DefaultMemoryContextProvider @Inject constructor(
    private val memoryIndex: MemoryIndex,
) : MemoryContextProvider {

    override suspend fun provide(): MemoryContext? {
        if (!runCatching { memoryIndex.isConfigured() }.getOrDefault(false)) return null
        val memories = runCatching { memoryIndex.listMemories(limit = PROVIDER_MAX_ITEMS) }.getOrNull() ?: return null
        return MemoryContext(items = memories.map { MemoryContextItem(it) }, capturedAt = Instant.now())
    }
}
