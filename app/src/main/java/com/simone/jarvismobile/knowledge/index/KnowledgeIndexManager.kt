package com.simone.jarvismobile.knowledge.index

import android.content.Context
import android.util.Log
import com.simone.jarvismobile.core.knowledge.fts.IndexedSource
import com.simone.jarvismobile.core.knowledge.fts.KnowledgeIndex
import com.simone.jarvismobile.core.knowledge.zim.ZimIndexer
import com.simone.jarvismobile.data.SettingsRepository
import com.simone.jarvismobile.knowledge.zim.ZimKnowledge
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owner of the persistent knowledge index (the ONE index; the text library and the opted-in archives both feed it). Indexing an
 * archive is an explicit user action ("Indicizza"), bounded by the space budget and an article cap, resumable after an
 * interruption (the cursor is committed with every batch), and never required: archives stay readable directly by title.
 */
@Singleton
class KnowledgeIndexManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val zim: ZimKnowledge,
) {
    data class Progress(val running: Boolean = false, val label: String = "", val done: Int = 0, val total: Int = 0, val message: String? = null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()
    private val _sources = MutableStateFlow<List<IndexedSource>>(emptyList())
    val sources: StateFlow<List<IndexedSource>> = _sources.asStateFlow()

    private val db by lazy { AndroidFtsDb(context) }

    /** The index; touching it opens (and, first time, creates) the database, so call from a background dispatcher. */
    val index: KnowledgeIndex by lazy { KnowledgeIndex(db) }

    suspend fun budgetBytes(): Long = settings.knowledgeIndexBudgetMb.first().toLong() * 1024 * 1024

    suspend fun refresh() = withContext(Dispatchers.IO) {
        _sources.value = runCatching { index.sources() }.getOrDefault(emptyList())
    }

    fun fileBytes(): Long = context.getDatabasePath(AndroidFtsDb.FILE).takeIf { it.exists() }?.length() ?: 0L

    /** Starts (or resumes) indexing the leads of one verified archive. No-op while another run is active. */
    fun startZim(uri: String, label: String) {
        if (job?.isActive == true) return
        job = scope.launch {
            _progress.value = Progress(true, label, 0, 0)
            try {
                val (source, size) = zim.sourceFor(uri) ?: run {
                    _progress.value = Progress(false, label, message = "Archivio non verificato: premi prima «Verifica lettura».")
                    return@launch
                }
                val budget = budgetBytes()
                val state = ZimIndexer(source.zim, index).run(
                    signature = size.toString(), maxArticles = ARTICLE_CAP, budgetBytes = budget,
                    shouldContinue = { isActive },
                    onProgress = { p -> _progress.value = Progress(true, label, p.position, p.total) },
                )
                val msg = when (state) {
                    IndexedSource.State.COMPLETE -> "Indicizzazione completata."
                    IndexedSource.State.PARTIAL_BUDGET -> "Indice parziale: raggiunto il limite di spazio o di articoli. Quanto indicizzato resta utilizzabile."
                    IndexedSource.State.INDEXING -> "Indicizzazione in pausa: riprende da dove si è fermata."
                }
                _progress.value = Progress(false, label, message = msg)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) {
                    _progress.value = Progress(false, label, message = "Indicizzazione in pausa: riprende da dove si è fermata.")
                    throw t
                }
                Log.w(TAG, "index_failed ${t.javaClass.simpleName}")
                _progress.value = Progress(false, label, message = "Indicizzazione interrotta (${t.javaClass.simpleName}). Si può riprendere.")
            } finally {
                refresh()
            }
        }
    }

    fun pause() { job?.cancel() }

    /** Removes everything indexed. Archives and library files are untouched. */
    suspend fun clear() = withContext(Dispatchers.IO) {
        job?.cancelAndJoin()
        runCatching { index.clear() }
        _progress.value = Progress()
        refresh()
    }

    /** Forgets one archive's index (e.g. when it is removed from the Offline Hub). */
    suspend fun removeZim(uuidKey: String) = withContext(Dispatchers.IO) { runCatching { index.removeSource(uuidKey) }; refresh() }

    private companion object {
        const val TAG = "JarvisKnowledgeIndex"
        const val ARTICLE_CAP = 250_000
    }
}
