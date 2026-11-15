package com.simone.jarvismobile.llm

import android.content.Context
import com.simone.jarvismobile.core.llm.LoadGuard
import com.simone.jarvismobile.core.llm.LoadJournalStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The durable "a model load is in progress" marker (see [LoadGuard]). Stored in its own tiny SharedPreferences file written with
 * `commit()` (synchronous) so the marker is on disk BEFORE native initialisation starts; it holds a few lines of text and
 * never touches models, settings, the database, chats, notes, documents or the ZIM/FTS indexes.
 */
@Singleton
class ModelLoadGuard @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("jarvis_load_guard", Context.MODE_PRIVATE)

    val guard = LoadGuard(object : LoadJournalStore {
        override fun read(): Map<String, String> = prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
        override fun write(values: Map<String, String>) {
            val e = prefs.edit().clear()
            values.forEach { (k, v) -> e.putString(k, v) }
            e.commit()
        }
    })

    private val _interrupted = MutableStateFlow<List<LoadGuard.Interrupted>>(emptyList())
    /** Loads the previous process did not survive; shown to the user, never acted on automatically. */
    val interrupted: StateFlow<List<LoadGuard.Interrupted>> = _interrupted.asStateFlow()

    @Volatile private var recovered = false

    /** Once per process, before anything is loaded. */
    @Synchronized fun startup() {
        if (recovered) return
        recovered = true
        guard.recoverOnStart()
        _interrupted.value = guard.interrupted()
    }

    fun dismiss(path: String) { guard.dismiss(path); _interrupted.value = guard.interrupted() }

    fun refresh() { _interrupted.value = guard.interrupted() }
}
