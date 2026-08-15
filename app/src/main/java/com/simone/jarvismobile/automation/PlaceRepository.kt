package com.simone.jarvismobile.automation

import android.content.Context
import android.util.Log
import com.simone.jarvismobile.core.places.Place
import com.simone.jarvismobile.core.places.PlaceCodec
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user's named places (`JARVIS/Luoghi.md`).
 *
 * Storage follows the same rule as the agenda and the automations: the vault is
 * the human-readable source of truth, with an app-private copy when no vault is
 * connected. A place is the only sensitive thing here — a coordinate — so it is
 * written where the user can see and delete it, never hidden in a database.
 *
 * Whenever the list changes the geofences are re-synced, so deleting a place in
 * Obsidian removes the geofence that watched it on the next reload.
 */
@Singleton
class PlaceRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vault: VaultRepository,
    private val locationTriggers: LocationTriggers,
) {
    private val mutex = Mutex()

    private val _places = MutableStateFlow<List<Place>>(emptyList())
    val places: StateFlow<List<Place>> = _places.asStateFlow()

    private val _lastError = MutableStateFlow("")
    val lastError: StateFlow<String> = _lastError.asStateFlow()

    private val localFile: File get() = File(context.filesDir, LOCAL_FILE)

    suspend fun reload(): List<Place> {
        val loaded = mutex.withLock { loadLocked() }
        locationTriggers.syncPlaces(loaded)
        return loaded
    }

    private suspend fun loadLocked(): List<Place> {
        _places.value = PlaceCodec.parseFile(readRaw())
        return _places.value
    }

    /** Adds or replaces a place; a second "casa" updates the first, never doubles it. */
    suspend fun save(place: Place): Boolean = mutex.withLock {
        try {
            val updated = loadLocked().filterNot { it.key == place.key } + place
            commit(updated).also { Log.i(TAG, "place_save ok=$it total=${updated.size}") }
        } catch (e: Throwable) {
            _lastError.value = e.javaClass.simpleName
            Log.w(TAG, "place_save_failed ${e.javaClass.simpleName}")
            false
        }
    }

    suspend fun remove(name: String): Boolean = mutex.withLock {
        val key = Place.normalize(name)
        val current = loadLocked()
        val updated = current.filterNot { it.key == key }
        if (updated.size == current.size) return@withLock false
        commit(updated)
    }

    fun find(name: String): Place? {
        val key = Place.normalize(name)
        return _places.value.firstOrNull { it.key == key }
    }

    private suspend fun commit(updated: List<Place>): Boolean {
        if (!writeRaw(PlaceCodec.renderFile(updated))) {
            if (_lastError.value.isBlank()) _lastError.value = "scrittura file"
            return false
        }
        _places.value = updated
        runCatching { locationTriggers.syncPlaces(updated) }
            .onFailure { Log.w(TAG, "place_sync_failed ${it.javaClass.simpleName}") }
        _lastError.value = ""
        return true
    }

    // --- storage ---------------------------------------------------------

    private suspend fun readRaw(): String {
        val fromVault = runCatching { vault.readJarvisFile(FILE) }
            .onFailure { Log.w(TAG, "place_vault_read_failed ${it.javaClass.simpleName}") }
            .getOrNull()
        fromVault?.let { remote ->
            val local = readLocal()
            if (local.isNotBlank()) {
                val merged = (PlaceCodec.parseFile(remote) + PlaceCodec.parseFile(local))
                    .distinctBy { it.key }
                if (vaultWrite(PlaceCodec.renderFile(merged))) {
                    clearLocal()
                    return PlaceCodec.renderFile(merged)
                }
            }
            return remote
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
            .onFailure { Log.w(TAG, "place_vault_write_failed ${it.javaClass.simpleName}") }
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
        const val FILE = "Luoghi.md"
        const val LOCAL_FILE = "luoghi.md"
    }
}
