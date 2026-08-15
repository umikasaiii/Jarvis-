package com.simone.jarvismobile.automation.rule

import android.util.Log
import kotlinx.coroutines.flow.Flow
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The saved places an "arrivo a &lt;luogo&gt;" rule points at (phase 6).
 *
 * Stored in Room (`automation_places`) because a coordinate the user chose is
 * exactly the user-created data the ADR keeps in the database, not a rebuildable
 * cache. Every change re-syncs the geofences, so deleting a place removes the
 * fence that watched it on the next write.
 *
 * The row id is a stable slug of the name ("casa", "il lavoro" → "il_lavoro"):
 * a rule stores that id in its `placeId` param, so renaming the display text
 * later does not orphan the rules that reference it.
 */
@Singleton
class PlaceRepository @Inject constructor(
    private val dao: AutomationPlaceDao,
    private val geofences: PlaceGeofenceSource,
) {
    fun observePlaces(): Flow<List<AutomationPlaceEntity>> = dao.observeAll()

    suspend fun enabled(): List<AutomationPlaceEntity> = dao.enabled()

    suspend fun byId(id: String): AutomationPlaceEntity? = dao.byId(id)

    /** Re-registers the geofences from what is stored; used on boot and app start. */
    suspend fun reload() {
        pruneExpired()
        runCatching { geofences.sync(dao.enabled()) }
            .onFailure { Log.w(TAG, "geofence_sync_failed ${it.javaClass.simpleName}") }
    }

    /**
     * Saves a place from a captured position. Returns the stored row so the
     * caller can show what it recorded (and hand its id to a rule).
     */
    suspend fun saveNamedPlace(
        name: String,
        latitude: Double,
        longitude: Double,
        radiusMeters: Float,
        now: LocalDateTime = LocalDateTime.now(),
    ): AutomationPlaceEntity? {
        val displayName = name.trim()
        if (displayName.isEmpty()) return null
        val id = slug(displayName)
        if (id.isEmpty()) return null
        val existing = dao.byId(id)
        val place = AutomationPlaceEntity(
            id = id,
            displayName = displayName,
            latitude = latitude,
            longitude = longitude,
            radiusMeters = radiusMeters.coerceIn(MIN_RADIUS, MAX_RADIUS),
            type = "USER",
            enabled = true,
            expiresAt = null,
            createdAt = existing?.createdAt ?: now.toString(),
            updatedAt = now.toString(),
            schemaVersion = 1,
        )
        dao.upsert(place)
        runCatching { geofences.sync(dao.enabled()) }
            .onFailure { Log.w(TAG, "geofence_sync_failed ${it.javaClass.simpleName}") }
        Log.i(TAG, "place_saved id=$id radius=${place.radiusMeters}")
        return place
    }

    suspend fun setEnabled(id: String, enabled: Boolean, now: LocalDateTime = LocalDateTime.now()) {
        val place = dao.byId(id) ?: return
        dao.upsert(place.copy(enabled = enabled, updatedAt = now.toString()))
        runCatching { geofences.sync(dao.enabled()) }
    }

    suspend fun delete(id: String) {
        dao.delete(id)
        runCatching { geofences.sync(dao.enabled()) }
    }

    private suspend fun pruneExpired(now: LocalDateTime = LocalDateTime.now()) {
        runCatching { dao.deleteExpired(now.toString()) }
    }

    /** Lower-cased, single-underscored, id-safe form of a name. */
    private fun slug(name: String): String =
        name.trim().lowercase()
            .replace(Regex("[^\\p{L}\\p{Nd}]+"), "_")
            .trim('_')

    private companion object {
        const val TAG = "JarvisAutomation"
        const val MIN_RADIUS = 50f
        const val MAX_RADIUS = 5000f
    }
}
