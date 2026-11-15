package com.simone.jarvismobile.core.llm

/** Durable key/value storage for the guard (SharedPreferences `commit()` on Android). [write] must be synchronous and durable. */
interface LoadJournalStore {
    fun read(): Map<String, String>
    fun write(values: Map<String, String>)
}

/**
 * Remembers that a model load was IN PROGRESS, durably, BEFORE native initialisation starts. If Android kills the process during
 * the load (native out-of-memory cannot be caught), the next start finds the marker and knows the previous attempt was
 * interrupted — without ever having to guess from RAM figures. Consequences, all decided here and tested on the JVM:
 *  - the interrupted model is NOT reloaded automatically (no crash loop); the user can still load it by hand;
 *  - nothing is deleted: not the model, not the settings, not any other data — this class only stores a few lines of text;
 *  - an interrupted marker is cleared only by a successful load of the same file or by the user dismissing it.
 */
class LoadGuard(private val store: LoadJournalStore, private val now: () -> Long = System::currentTimeMillis) {
    data class Attempt(val slot: String, val path: String, val name: String, val fileBytes: Long, val startedAt: Long, val adviceLevel: String)
    data class Interrupted(val slot: String, val path: String, val name: String, val fileBytes: Long, val startedAt: Long, val count: Int)

    private val lock = Any()

    /** Call right before the native load. Durable on return. */
    fun begin(a: Attempt) = synchronized(lock) {
        val m = store.read().toMutableMap()
        m[PENDING] = listOf(a.slot, a.path, a.name, a.fileBytes.toString(), a.startedAt.toString(), a.adviceLevel).joinToString(SEP)
        store.write(m)
    }

    /** Call when the load RETURNED (success or a caught failure): the process survived, so the marker goes. */
    fun finish(path: String, success: Boolean) = synchronized(lock) {
        val m = store.read().toMutableMap()
        m.remove(PENDING)
        if (success) {
            val rest = decode(m[INTERRUPTED]).filterNot { it.path == path }
            if (rest.isEmpty()) m.remove(INTERRUPTED) else m[INTERRUPTED] = encode(rest)
        }
        store.write(m)
    }

    /**
     * Call once at process start. A leftover PENDING marker means the previous process died while loading: it is moved to the
     * interrupted list (count + 1) and returned. Idempotent: a second call returns null.
     */
    fun recoverOnStart(): Interrupted? = synchronized(lock) {
        val m = store.read().toMutableMap()
        val raw = m.remove(PENDING) ?: return null
        val f = raw.split(SEP)
        if (f.size < 6) { store.write(m); return null }
        val list = decode(m[INTERRUPTED]).toMutableList()
        val prev = list.firstOrNull { it.path == f[1] }
        val rec = Interrupted(f[0], f[1], f[2], f[3].toLongOrNull() ?: 0, f[4].toLongOrNull() ?: now(), (prev?.count ?: 0) + 1)
        list.removeAll { it.path == f[1] }
        list += rec
        m[INTERRUPTED] = encode(list)
        store.write(m)
        rec
    }

    /** False for a model whose last attempt was interrupted: the app must not reload it by itself. */
    fun autoLoadAllowed(path: String): Boolean = synchronized(lock) { decode(store.read()[INTERRUPTED]).none { it.path == path } }

    fun interrupted(): List<Interrupted> = synchronized(lock) { decode(store.read()[INTERRUPTED]) }

    /** The user dismissed the notice (or decided to retry): the model may be auto-loaded again. Nothing else changes. */
    fun dismiss(path: String) = synchronized(lock) {
        val m = store.read().toMutableMap()
        val rest = decode(m[INTERRUPTED]).filterNot { it.path == path }
        if (rest.isEmpty()) m.remove(INTERRUPTED) else m[INTERRUPTED] = encode(rest)
        store.write(m)
    }

    private fun encode(l: List<Interrupted>) = l.joinToString(REC) { listOf(it.slot, it.path, it.name, it.fileBytes, it.startedAt, it.count).joinToString(SEP) }

    private fun decode(s: String?): List<Interrupted> = if (s.isNullOrEmpty()) emptyList() else s.split(REC).mapNotNull { r ->
        val f = r.split(SEP)
        if (f.size < 6) null else Interrupted(f[0], f[1], f[2], f[3].toLongOrNull() ?: 0, f[4].toLongOrNull() ?: 0, f[5].toIntOrNull() ?: 1)
    }

    private companion object { const val PENDING = "pending"; const val INTERRUPTED = "interrupted"; const val SEP = "\u001F"; const val REC = "\u001E" }
}
