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

    /**
     * Why the last write failed, in a word. Without this a failed save is a
     * dead end: the caller says "non sono riuscito" and nobody, including the
     * developer, can tell whether it was the vault, the disk or a bug.
     */
    private val _lastError = MutableStateFlow("")
    val lastError: StateFlow<String> = _lastError.asStateFlow()

    private val localFile: File get() = File(context.filesDir, LOCAL_FILE)

    suspend fun reload(): List<Automation> {
        val loaded = mutex.withLock { loadLocked() }
        // Scheduling must never fail a load: the rules are read and shown even if
        // the exact-alarm path complains (e.g. a missing permission on an OEM ROM).
        runCatching { scheduler.sync(loaded) }
            .onFailure { Log.w(TAG, "automation_reload_schedule_failed ${it.javaClass.simpleName}") }
        return loaded
    }

    private suspend fun loadLocked(): List<Automation> {
        _automations.value = AutomationCodec.parseFile(readRaw())
        return _automations.value
    }

    suspend fun add(automation: Automation): Boolean = mutex.withLock {
        try {
            val updated = loadLocked() + automation
            commit(updated).also {
                Log.i(TAG, "automation_add ok=$it total=${updated.size}")
            }
        } catch (e: Throwable) {
            // An exception here used to surface as a bare "could not save".
            // Include the message and any cause: a NoClassDefFoundError alone
            // hides *which* class, and an init failure hides the real reason.
            _lastError.value = describe(e)
            Log.w(TAG, "automation_add_failed ${describe(e)}")
            false
        }
    }

    /** A short, personal-data-free description of a failure: type + message + cause. */
    private fun describe(e: Throwable): String {
        val head = e.message?.let { "${e.javaClass.simpleName}: $it" } ?: e.javaClass.simpleName
        val cause = e.cause?.let { c ->
            " ← ${c.javaClass.simpleName}${c.message?.let { ": $it" } ?: ""}"
        }.orEmpty()
        return (head + cause).take(200)
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
        if (!writeRaw(AutomationCodec.renderFile(updated))) {
            if (_lastError.value.isBlank()) _lastError.value = "scrittura file"
            return false
        }
        _automations.value = updated
        // Scheduling must never fail a save: the rule is stored, and a schedule
        // that did not take can be rebuilt on the next reload. Losing the rule
        // because WorkManager complained would be the wrong trade.
        runCatching { scheduler.sync(updated) }
            .onFailure { Log.w(TAG, "automation_schedule_failed ${it.javaClass.simpleName}") }
        _lastError.value = ""
        return true
    }

    // --- storage ---------------------------------------------------------

    private suspend fun readRaw(): String {
        val fromVaultOrNull = runCatching { vault.readJarvisFile(FILE) }
            .onFailure { Log.w(TAG, "automation_vault_read_failed ${it.javaClass.simpleName}") }
            .getOrNull()
        fromVaultOrNull?.let { fromVault ->
            val local = readLocal()
            if (local.isNotBlank()) {
                val merged = (AutomationCodec.parseFile(fromVault) + AutomationCodec.parseFile(local))
                    .distinctBy { it.id }
                if (vaultWrite(AutomationCodec.renderFile(merged))) {
                    clearLocal()
                    return AutomationCodec.renderFile(merged)
                }
            }
            return fromVault
        }
        if (runCatching { vault.isConfigured() }.getOrDefault(false)) {
            val local = readLocal()
            if (local.isNotBlank() && vaultWrite(local)) {
                clearLocal()
                return local
            }
            return ""
        }
        return readLocal()
    }

    private suspend fun writeRaw(content: String): Boolean {
        if (runCatching { vault.isConfigured() }.getOrDefault(false) && vaultWrite(content)) return true
        return writeLocal(content)
    }

    private suspend fun vaultWrite(content: String): Boolean =
        runCatching { vault.writeJarvisFile(FILE, content) }
            .onFailure { Log.w(TAG, "automation_vault_write_failed ${it.javaClass.simpleName}") }
            .getOrDefault(false)

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
