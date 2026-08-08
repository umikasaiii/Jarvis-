package com.simone.jarvismobile.automation

import android.content.Context
import android.util.Log
import com.simone.jarvismobile.core.automation.Automation
import com.simone.jarvismobile.core.automation.AutomationCodec
import com.simone.jarvismobile.memory.VaultRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user's automation rules.
 *
 * Storage follows the same rule as the agenda: the vault is the human-readable
 * source of truth (`JARVIS/Automazioni.md`), with an app-private copy when no
 * vault is connected so a rule is never silently dropped. Connecting a vault
 * later migrates what is local.
 *
 * All parsing and rendering lives in `:core` and is unit-tested; this class only
 * does I/O and scheduling.
 */
@Singleton
class AutomationRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vault: VaultRepository,
    private val scheduler: AutomationScheduler,
) {
    private val mutex = Mutex()

    private val _automations = MutableStateFlow<List<Automation>>(emptyList())
    val automations: StateFlow<List<Automation>> = _automations.asStateFlow()

    private val localFile: File get() = File(context.filesDir, LOCAL_FILE)

    suspend fun reload(): List<Automation> {
        val loaded = mutex.withLock { loadLocked() }
        scheduler.sync(loaded)
        return loaded
    }

    private suspend fun loadLocked(): List<Automation> {
        _automations.value = AutomationCodec.parseFile(readRaw())
        return _automations.value
    }

    suspend fun add(automation: Automation): Boolean = mutex.withLock {
        val updated = loadLocked() + automation
        commit(updated).also {
            Log.i(TAG, "automation_add ok=$it total=${updated.size}")
        }
    }

    suspend fun remove(id: String): Boolean = mutex.withLock {
        val current = loadLocked()
        val updated = current.filterNot { it.id == id }
        if (updated.size == current.size) return@withLock false
        commit(updated)
    }

    suspend fun setEnabled(id: String, enabled: Boolean): Boolean = mutex.withLock {
        val updated = loadLocked().map { if (it.id == id) it.copy(enabled = enabled) else it }
        commit(updated)
    }

    /** Records that a rule ran, so the UI can show it and a daily rule can tell. */
    suspend fun markFired(id: String, at: LocalDateTime = LocalDateTime.now()): Boolean = mutex.withLock {
        val updated = loadLocked().map { if (it.id == id) it.copy(lastFired = at) else it }
        commit(updated)
    }

    fun find(id: String): Automation? = _automations.value.firstOrNull { it.id == id }

    private suspend fun commit(updated: List<Automation>): Boolean {
        if (!writeRaw(AutomationCodec.renderFile(updated))) return false
        _automations.value = updated
        scheduler.sync(updated)
        return true
    }

    // --- storage ---------------------------------------------------------

    private suspend fun readRaw(): String {
        vault.readJarvisFile(FILE)?.let { fromVault ->
            val local = readLocal()
            if (local.isNotBlank()) {
                val merged = (AutomationCodec.parseFile(fromVault) + AutomationCodec.parseFile(local))
                    .distinctBy { it.id }
                if (vault.writeJarvisFile(FILE, AutomationCodec.renderFile(merged))) {
                    clearLocal()
                    return AutomationCodec.renderFile(merged)
                }
            }
            return fromVault
        }
        if (vault.isConfigured()) {
            val local = readLocal()
            if (local.isNotBlank() && vault.writeJarvisFile(FILE, local)) {
                clearLocal()
                return local
            }
            return ""
        }
        return readLocal()
    }

    private suspend fun writeRaw(content: String): Boolean {
        if (vault.isConfigured() && vault.writeJarvisFile(FILE, content)) return true
        return writeLocal(content)
    }

    private suspend fun readLocal(): String = withContext(Dispatchers.IO) {
        runCatching { if (localFile.exists()) localFile.readText() else "" }.getOrDefault("")
    }

    private suspend fun writeLocal(content: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { localFile.writeText(content); true }.getOrDefault(false)
    }

    private suspend fun clearLocal() = withContext(Dispatchers.IO) {
        runCatching { localFile.delete() }
        Unit
    }

    private companion object {
        const val TAG = "JarvisAutomation"
        const val FILE = "Automazioni.md"
        const val LOCAL_FILE = "automazioni.md"
    }
}
