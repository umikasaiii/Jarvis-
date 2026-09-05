package com.simone.jarvismobile.agenda

import android.content.Context
import android.util.Log
import com.simone.jarvismobile.core.agenda.Agenda
import com.simone.jarvismobile.core.agenda.AgendaEntry
import com.simone.jarvismobile.core.agenda.DayPeriod
import com.simone.jarvismobile.core.agenda.ReminderAlert
import com.simone.jarvismobile.memory.VaultRepository
import com.simone.jarvismobile.reminders.ReminderScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * JARVIS's personal offline calendar: entries with an hour are appointments;
 * entries without an hour are dated activities/tasks. Both are structured data,
 * not free text with "domani" buried in the description.
 *
 * Storage follows the project's rule that the vault is the human-readable source
 * of truth: entries live in `JARVIS/Agenda.md` as Obsidian task lines
 * (`- [ ] 2026-08-07 15:00 — revisione auto`), so they are readable and editable
 * in Obsidian itself. When no vault is connected the same Markdown is kept in the
 * app's private storage, so reminders are never silently dropped; connecting a
 * vault later migrates them across.
 *
 * All parsing/sorting/filtering lives in `:core` ([Agenda]) and is unit-tested.
 */
@Singleton
class AgendaRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vault: VaultRepository,
    private val reminderScheduler: ReminderScheduler,
) {
    private val mutex = Mutex()

    private val _entries = MutableStateFlow<List<AgendaEntry>>(emptyList())

    /** The whole agenda, chronologically. Observed by the dashboard. */
    val entries: StateFlow<List<AgendaEntry>> = _entries.asStateFlow()

    private val localFile: File get() = File(context.filesDir, LOCAL_FILE)

    /** Reads the agenda from wherever it lives and publishes it. */
    suspend fun reload(): List<AgendaEntry> {
        val loaded = mutex.withLock { loadLocked() }
        reminderScheduler.sync(loaded)
        return loaded
    }

    private suspend fun loadLocked(): List<AgendaEntry> {
        val text = readRaw()
        val parsed = Agenda.parseFile(text)
        _entries.value = Agenda.sorted(parsed)
        return _entries.value
    }

    /**
     * Files a new commitment. Returns false only when the write itself failed —
     * with no vault the entry still lands in local storage.
     */
    suspend fun add(entry: AgendaEntry): Boolean = mutex.withLock {
        val current = loadLocked()
        val updated = Agenda.sorted(current + entry)
        val ok = writeRaw(Agenda.renderFile(updated))
        if (ok) {
            _entries.value = updated
            reminderScheduler.sync(updated)
        }
        Log.i(TAG, "agenda_add ok=$ok total=${updated.size}")
        ok
    }

    /**
     * Sets the done flag on one entry by id. Every caller — the dashboard tap,
     * and the chat/voice path via [com.simone.jarvismobile.tools.AgendaIntentRouter],
     * which resolves the user's own words to a real entry first — already knows
     * exactly which one it means, so this never does its own text matching.
     */
    suspend fun setDone(id: String, done: Boolean): AgendaEntry? = mutex.withLock {
        val current = loadLocked()
        val target = current.firstOrNull { it.id == id } ?: return@withLock null
        // Stamp the completion date so the "Completate" section can show when,
        // like Google Tasks; clear it when the item is re-opened.
        val updatedEntry = target.copy(
            done = done,
            completedAt = if (done) LocalDate.now() else null,
        )
        val updated = Agenda.sorted(current.map { if (it.id == id) updatedEntry else it })
        if (!writeRaw(Agenda.renderFile(updated))) return@withLock null
        _entries.value = updated
        reminderScheduler.cancelEntry(id)
        // A re-opened item gets its alerts scheduled again; a completed one does not.
        reminderScheduler.sync(updated)
        updatedEntry
    }

    /**
     * Applies [transform] to one entry by id and persists — the general edit the
     * task UI needs (rename, star, move to another list, retype notes, change
     * due date). Keeps the id stable so nothing is duplicated.
     */
    suspend fun update(id: String, transform: (AgendaEntry) -> AgendaEntry): AgendaEntry? = mutex.withLock {
        val current = loadLocked()
        val target = current.firstOrNull { it.id == id } ?: return@withLock null
        val updatedEntry = transform(target).copy(id = id)
        val updated = Agenda.sorted(current.map { if (it.id == id) updatedEntry else it })
        if (!writeRaw(Agenda.renderFile(updated))) return@withLock null
        _entries.value = updated
        reminderScheduler.cancelEntry(id)
        reminderScheduler.sync(updated)
        updatedEntry
    }

    /** Removes one entry (and any of its sub-tasks) by id. */
    suspend fun removeById(id: String): Boolean = mutex.withLock {
        val current = loadLocked()
        if (current.none { it.id == id }) return@withLock false
        val updated = current.filterNot { it.id == id || it.parentId == id }
        if (!writeRaw(Agenda.renderFile(updated))) return@withLock false
        _entries.value = updated
        reminderScheduler.cancelEntry(id)
        reminderScheduler.sync(updated)
        true
    }

    /** Adds a child task under [parentId], inheriting the parent's list. */
    suspend fun addSubtask(parentId: String, title: String): AgendaEntry? = mutex.withLock {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return@withLock null
        val current = loadLocked()
        val parent = current.firstOrNull { it.id == parentId } ?: return@withLock null
        val child = AgendaEntry(
            date = null,
            text = trimmed,
            list = parent.list,
            parentId = parentId,
            order = (current.filter { it.parentId == parentId }.maxOfOrNull { it.order } ?: 0) + 1,
        )
        val updated = Agenda.sorted(current + child)
        if (!writeRaw(Agenda.renderFile(updated))) return@withLock null
        _entries.value = updated
        child
    }

    /** Removes the first entry whose text contains [needle]. */
    suspend fun remove(needle: String): AgendaEntry? = mutex.withLock {
        val current = loadLocked()
        val target = current.firstOrNull {
            it.text.contains(needle.trim(), ignoreCase = true)
        } ?: return@withLock null
        val updated = current - target
        if (!writeRaw(Agenda.renderFile(updated))) return@withLock null
        _entries.value = updated
        reminderScheduler.cancelEntry(target.id)
        reminderScheduler.sync(updated)
        target
    }

    /** Replaces all notification rules for one dashboard entry. */
    suspend fun updateAlerts(id: String, alerts: List<ReminderAlert>): AgendaEntry? = mutex.withLock {
        val current = loadLocked()
        val target = current.firstOrNull { it.id == id } ?: return@withLock null
        val updatedEntry = target.copy(alerts = alerts.distinctBy { it.key })
        val updated = Agenda.sorted(current.map { if (it.id == id) updatedEntry else it })
        if (!writeRaw(Agenda.renderFile(updated))) return@withLock null
        _entries.value = updated
        reminderScheduler.cancelEntry(id)
        reminderScheduler.sync(updated)
        updatedEntry
    }

    /**
     * What is on for a given day / part of the day; everything upcoming by
     * default. [toDay], when given alongside [day], turns this into an
     * inclusive date-RANGE query (§ FASE 2A.9 — "durante tutta la settimana
     * prossima") instead of one exact day; see [Agenda.filter]'s own doc
     * comment.
     */
    suspend fun query(
        today: LocalDate,
        day: LocalDate? = null,
        period: DayPeriod? = null,
        toDay: LocalDate? = null,
    ): List<AgendaEntry> = Agenda.filter(reload(), today, day, period, toDay = toDay)

    // --- storage ---------------------------------------------------------

    /**
     * Reads the agenda by MERGING a reliable local copy with the vault file.
     *
     * The local copy (a plain app-private file, never subject to SAF quirks) is
     * the safety net: it used to be cleared once a vault was connected, so when a
     * vault reported a successful write but silently dropped the bytes, the next
     * read came back empty and a following add overwrote the file with only the
     * new entry — losing every earlier task. Now local is kept forever and folded
     * in on every read: the vault's copy wins for entries present in both (so an
     * Obsidian edit sticks), and entries that exist only locally are preserved
     * (so a flaky vault can never lose an on-device add).
     */
    private suspend fun readRaw(): String {
        val localEntries = Agenda.parseFile(readLocal())
        if (!vault.isConfigured()) return Agenda.renderFile(localEntries)
        val vaultEntries = Agenda.parseFile(vault.readJarvisFile(AGENDA_FILE).orEmpty())
        val byId = LinkedHashMap<String, AgendaEntry>()
        localEntries.forEach { byId[it.id] = it }
        vaultEntries.forEach { byId[it.id] = it } // vault overrides on shared ids
        return Agenda.renderFile(byId.values.toList())
    }

    private suspend fun writeRaw(content: String): Boolean {
        // Local first and always — it is the source that cannot silently fail.
        val localOk = writeLocal(content)
        // Mirror into the vault so Obsidian still sees the file; best-effort, and
        // never the sole copy, so its flakiness can't lose data.
        if (vault.isConfigured()) vault.writeJarvisFile(AGENDA_FILE, content)
        return localOk
    }

    private suspend fun readLocal(): String = withContext(Dispatchers.IO) {
        runCatching { if (localFile.exists()) localFile.readText() else "" }.getOrDefault("")
    }

    private suspend fun writeLocal(content: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { localFile.writeText(content); true }.getOrDefault(false)
    }

    private companion object {
        const val TAG = "JarvisAgenda"
        const val AGENDA_FILE = "Agenda.md"
        const val LOCAL_FILE = "agenda.md"
    }
}
