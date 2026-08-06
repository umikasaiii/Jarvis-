package com.simone.jarvismobile.memory

import android.content.Context
import android.util.Log
import com.simone.jarvismobile.core.memory.MemoryStructure
import com.simone.jarvismobile.core.memory.MemoryTurn
import com.simone.jarvismobile.core.memory.ShortTermMemorySnapshot
import com.simone.jarvismobile.core.memory.ShortTermMemorySummarizer
import com.simone.jarvismobile.data.StoredMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Conversation-scoped structured memory in app-private storage. */
@Singleton
class ConversationMemoryStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val file: File get() = File(context.filesDir, FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()

    private val _snapshot = MutableStateFlow(ShortTermMemorySnapshot())
    val snapshot = _snapshot.asStateFlow()

    @Volatile private var loaded = false

    suspend fun ensureLoaded(): ShortTermMemorySnapshot = mutex.withLock {
        if (!loaded) {
            _snapshot.value = read()
            loaded = true
        }
        _snapshot.value
    }

    suspend fun update(messages: List<StoredMessage>) = mutex.withLock {
        if (!loaded) {
            _snapshot.value = read()
            loaded = true
        }
        val turns = messages.map { MemoryTurn(it.fromUser, it.text) }
        val next = ShortTermMemorySummarizer.summarize(turns, _snapshot.value)
        if (next != _snapshot.value) {
            _snapshot.value = next
            write(next)
        }
    }

    /** Explicit "remember only for this conversation" fact. */
    suspend fun addTemporary(text: String): Boolean = mutex.withLock {
        val body = text.replace(Regex("""\s+"""), " ").trim()
        if (body.isBlank() || MemoryStructure.containsCredential(body)) return@withLock false
        if (!loaded) {
            _snapshot.value = read()
            loaded = true
        }
        val facts = (_snapshot.value.facts + body).distinctBy { it.lowercase() }.takeLast(MAX_FACTS)
        val fields = MemoryStructure.extract(facts.joinToString(". "))
        val next = ShortTermMemorySnapshot(
            facts = facts,
            topics = fields.topics,
            people = fields.people,
            dates = fields.dates,
            updatedAt = System.currentTimeMillis(),
        )
        _snapshot.value = next
        write(next)
        true
    }

    suspend fun clear() = mutex.withLock {
        _snapshot.value = ShortTermMemorySnapshot()
        loaded = true
        withContext(Dispatchers.IO) { runCatching { file.delete() } }
        Unit
    }

    private suspend fun read(): ShortTermMemorySnapshot = withContext(Dispatchers.IO) {
        runCatching {
            if (!file.exists()) return@runCatching ShortTermMemorySnapshot()
            json.decodeFromString(ShortTermMemorySnapshot.serializer(), file.readText())
        }.getOrElse {
            Log.w(TAG, "short_memory_load_failed ${it.javaClass.simpleName}")
            ShortTermMemorySnapshot()
        }
    }

    private suspend fun write(value: ShortTermMemorySnapshot) = withContext(Dispatchers.IO) {
        runCatching { file.writeText(json.encodeToString(ShortTermMemorySnapshot.serializer(), value)) }
            .onFailure { Log.w(TAG, "short_memory_save_failed ${it.javaClass.simpleName}") }
        Unit
    }

    private companion object {
        const val TAG = "JarvisShortMemory"
        const val FILE_NAME = "conversation_memory_v2.json"
        const val MAX_FACTS = 12
    }
}
