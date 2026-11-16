package com.simone.jarvismobile.core.llm

import com.simone.jarvismobile.core.storage.AtomicTextFile
import java.io.File
import java.io.IOException

/**
 * M3 integrity gate — the persistence of the profile registry on top of [AtomicTextFile] (the project's existing temp + fsync + backup + atomic-rename
 * writer). The registry file is NEVER deleted to make room for a new one: a failed write leaves the previous version whole and the caller keeps its
 * in-memory profiles and retries on the next change (no silent loss); a garbled main file falls back to the last good `.bak`; a stale `.tmp` from an
 * interrupted write is ignored (and overwritten by the next write). It never touches model files. The JSON format is the one already published.
 *
 * Promise: an interrupted or failed write before the rename leaves the previous file intact. Not promised: surviving physical media failure or a power
 * loss the filesystem does not honour after fsync (JVM tests simulate interruption, they cannot prove that).
 */
class ModelProfileFile(file: File, beforeCommit: () -> Unit = {}) {
    private val atomic = AtomicTextFile(file, worthBackingUp = { ModelProfileRegistry.decodeOrNull(it) != null }, beforeCommit = beforeCommit)

    enum class Source { MAIN, BACKUP, EMPTY, UNREADABLE }
    class Loaded(val registry: ModelProfileRegistry, val source: Source)

    fun load(): Loaded {
        val main = try { if (atomic.file.isFile) atomic.file.readText() else null } catch (_: IOException) { return recoverFromBackup(Source.UNREADABLE) }
        if (main == null) return recoverFromBackup(Source.EMPTY)
        ModelProfileRegistry.decodeOrNull(main)?.let { return Loaded(it, Source.MAIN) }
        return recoverFromBackup(Source.UNREADABLE) // present but not a registry: the last good copy, never "no profiles" silently
    }

    private fun recoverFromBackup(otherwise: Source): Loaded {
        val bak = try { if (atomic.backup.isFile) atomic.backup.readText() else null } catch (_: IOException) { null }
        bak?.let { b -> ModelProfileRegistry.decodeOrNull(b)?.let { return Loaded(it, Source.BACKUP) } }
        return Loaded(ModelProfileRegistry(), otherwise)
    }

    /** @return true when committed; false when the write failed (nothing on disk changed — keep the in-memory registry and retry). */
    fun save(registry: ModelProfileRegistry): Boolean = try {
        atomic.write(ModelProfileRegistry.encode(registry)); true
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
