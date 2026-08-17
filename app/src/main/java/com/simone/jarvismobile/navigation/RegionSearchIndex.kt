package com.simone.jarvismobile.navigation

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.simone.jarvismobile.core.navigation.LatLng
import com.simone.jarvismobile.core.navigation.Place
import com.simone.jarvismobile.core.navigation.PlaceCategory
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opens each installed region's pre-built `search.sqlite` (spec §2, §4) directly,
 * read-only. The FTS5 index itself is generated once on a PC by
 * `tools/navigation/osm_to_jarvis.py`; nothing here parses OSM data or builds an
 * index — this only runs `SELECT`s against a file that already exists, which is
 * what keeps "no OSM index built on the phone" true. A region that only shipped
 * the older `search.json` (no `search.sqlite` yet) isn't handled here — the
 * `PlaceSearchRepository` fallback covers those through the on-device Room FTS
 * table it has always used.
 */
@Singleton
class RegionSearchIndex @Inject constructor(
    private val store: InstalledRegionStore,
) {
    // null = "checked, no usable search.sqlite for this region" (cached to avoid
    // re-stat'ing the filesystem on every keystroke of a search box).
    private val handles = HashMap<String, SQLiteDatabase?>()

    @Synchronized
    private fun open(regionId: String): SQLiteDatabase? {
        if (handles.containsKey(regionId)) return handles[regionId]
        val file = File(store.regionDir(regionId), SQLITE_FILE_NAME)
        val db = if (file.exists()) {
            runCatching { SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY) }
                .onFailure { Log.w(TAG, "search_sqlite_open_failed region=$regionId ${it.javaClass.simpleName}") }
                .getOrNull()
        } else {
            null
        }
        handles[regionId] = db
        return db
    }

    fun hasSqlite(regionId: String): Boolean = open(regionId) != null

    /** Drops a cached handle after a region's `search.sqlite` is (re)downloaded or deleted. */
    @Synchronized
    fun invalidate(regionId: String) {
        runCatching { handles.remove(regionId)?.close() }
    }

    /**
     * Full-text search within one region's index. [ftsMatch] is the same
     * prefix-token MATCH expression [PlaceSearchRepository] already builds for
     * the Room FTS4 fallback, so both paths accept identical query syntax.
     */
    fun search(regionId: String, ftsMatch: String, limit: Int): List<Place> {
        val db = open(regionId) ?: return emptyList()
        return runCatching {
            db.rawQuery(
                "SELECT ref_id, name, address, category, lat, lon, importance FROM places " +
                    "WHERE places MATCH ? LIMIT ?",
                arrayOf(ftsMatch, limit.toString()),
            ).use { it.readPlaces() }
        }.onFailure { Log.w(TAG, "search_sqlite_query_failed region=$regionId ${it.javaClass.simpleName}") }
            .getOrDefault(emptyList())
    }

    fun byCategory(regionId: String, category: PlaceCategory): List<Place> {
        val db = open(regionId) ?: return emptyList()
        return runCatching {
            db.rawQuery(
                "SELECT ref_id, name, address, category, lat, lon, importance FROM places WHERE category = ?",
                arrayOf(category.name),
            ).use { it.readPlaces() }
        }.onFailure { Log.w(TAG, "search_sqlite_category_failed region=$regionId ${it.javaClass.simpleName}") }
            .getOrDefault(emptyList())
    }

    fun all(regionId: String): List<Place> {
        val db = open(regionId) ?: return emptyList()
        return runCatching {
            db.rawQuery("SELECT ref_id, name, address, category, lat, lon, importance FROM places", null)
                .use { it.readPlaces() }
        }.onFailure { Log.w(TAG, "search_sqlite_all_failed region=$regionId ${it.javaClass.simpleName}") }
            .getOrDefault(emptyList())
    }

    private fun Cursor.readPlaces(): List<Place> {
        val out = ArrayList<Place>(count.coerceAtLeast(0))
        while (moveToNext()) {
            out += Place(
                id = getString(0),
                name = getString(1),
                category = runCatching { PlaceCategory.valueOf(getString(3)) }.getOrDefault(PlaceCategory.OTHER),
                location = LatLng(getDouble(4), getDouble(5)),
                importance = getDouble(6),
                address = getString(2) ?: "",
            )
        }
        return out
    }

    private companion object {
        const val TAG = "JarvisSearchSqlite"
        const val SQLITE_FILE_NAME = "search.sqlite"
    }
}
