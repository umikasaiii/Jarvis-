package com.simone.jarvismobile.data

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** One persisted line of conversation. */
@Serializable
data class StoredMessage(val fromUser: Boolean, val text: String, val at: Long = 0L)

/**
 * Keeps the conversation across app restarts.
 *
 * The model's own multi-turn memory is a KV cache inside the loaded engine: it
 * cannot survive process death, and Android will kill the process whenever it
 * wants the RAM. So the transcript is written to app-private storage and, when
 * the app comes back, it is both shown again in the chat and folded into the new
 * conversation's system instruction as a recap — that is what makes JARVIS still
 * know what you were talking about an hour later.
 *
 * App-private storage only (no vault, no cloud): the transcript can contain
 * anything the user said, so it never leaves the app's sandbox and is cleared by
 * "Nuova conversazione".
 */
@Singleton
class ChatStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val file: File get() = File(context.filesDir, FILE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** The saved conversation, oldest first. Empty when there is none. */
    suspend fun load(): List<StoredMessage> = withContext(Dispatchers.IO) {
        runCatching {
            if (!file.exists()) return@runCatching emptyList()
            json.decodeFromString<List<StoredMessage>>(file.readText())
        }.getOrElse {
            Log.w(TAG, "chat_load_failed ${it.javaClass.simpleName}")
            emptyList()
        }
    }

    /** Replaces the saved conversation, keeping only the most recent [MAX] lines. */
    suspend fun save(messages: List<StoredMessage>) = withContext(Dispatchers.IO) {
        runCatching {
            file.writeText(json.encodeToString(messages.takeLast(MAX)))
        }.onFailure { Log.w(TAG, "chat_save_failed ${it.javaClass.simpleName}") }
        Unit
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        runCatching { file.delete() }
        Unit
    }

    companion object {
        private const val TAG = "JarvisChatStore"
        private const val FILE = "chat.json"

        /** How many lines are kept on disk. Bounded so the file stays small. */
        const val MAX = 60
    }
}
