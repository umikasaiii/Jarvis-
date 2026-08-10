package com.simone.jarvismobile.memory

import android.content.Context
import android.net.Uri
import android.util.Log
import com.simone.jarvismobile.core.memory.VectorMath
import com.simone.jarvismobile.data.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the optional [OnnxEmbedder] and turns memory chunks into semantic scores.
 * Loading is lazy and failure-safe: with no imported model, or on any init error,
 * it simply reports not-ready and the [MemoryIndex] keeps using the lexical
 * ranker. Chunk vectors are cached per session so only the query is embedded on
 * each retrieval.
 */
@Singleton
class EmbeddingRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    data class Status(val ready: Boolean = false, val dimension: Int = 0, val error: String? = null)

    private val _status = MutableStateFlow(Status())
    val status = _status.asStateFlow()

    @Volatile private var embedder: OnnxEmbedder? = null
    private val cache = ConcurrentHashMap<String, FloatArray>()
    private val mutex = Mutex()

    fun isReady(): Boolean = embedder != null

    /** Loads the embedder from the imported paths if not already loaded. */
    suspend fun ensureLoaded() = withContext(Dispatchers.IO) {
        if (embedder != null) return@withContext
        mutex.withLock {
            if (embedder != null) return@withLock
            val model = settings.embeddingModelPath.first()
            val vocab = settings.embeddingVocabPath.first()
            if (model.isBlank() || vocab.isBlank() || !File(model).exists() || !File(vocab).exists()) {
                _status.value = Status(ready = false)
                return@withLock
            }
            val loaded = OnnxEmbedder.load(model, vocab)
            if (loaded == null) {
                _status.value = Status(ready = false, error = "init")
                return@withLock
            }
            runCatching { loaded.embed("ok") } // warm-up sets the dimension
            embedder = loaded
            cache.clear()
            _status.value = Status(ready = true, dimension = loaded.dimension)
            Log.i(TAG, "embedder_ready dim=${loaded.dimension}")
        }
    }

    /**
     * Cosine similarity of [query] to each of [texts], or null when no embedder is
     * available so the caller can fall back cleanly. Chunk vectors are cached.
     */
    suspend fun semanticScores(query: String, texts: List<String>): Map<String, Double>? =
        withContext(Dispatchers.IO) {
            val e = embedder ?: return@withContext null
            val q = runCatching { e.embed(query) }.getOrNull() ?: return@withContext null
            texts.associateWith { text ->
                val v = cache.getOrPut(text) { runCatching { e.embed(text) }.getOrDefault(FloatArray(0)) }
                VectorMath.cosine(q, v).toDouble()
            }
        }

    /** Copies a picked model/vocab file into app-private storage and returns its path. */
    suspend fun importFile(uri: Uri, fileName: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.filesDir, "embedding").apply { mkdirs() }
            val out = File(dir, fileName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { input.copyTo(it) }
            } ?: return@runCatching null
            out.absolutePath
        }.getOrNull()
    }

    /** Drops the loaded model so the next retrieval reloads (after an import/change). */
    fun invalidate() {
        embedder?.close()
        embedder = null
        cache.clear()
        _status.value = Status()
    }

    private companion object {
        const val TAG = "JarvisEmbedding"
    }
}
