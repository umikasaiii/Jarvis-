package com.simone.jarvismobile.navigation

import android.util.Log
import com.simone.jarvismobile.core.navigation.FavoriteKind
import com.simone.jarvismobile.core.navigation.FavoritePlace
import com.simone.jarvismobile.core.navigation.FavoriteResolver
import com.simone.jarvismobile.core.navigation.ItalianTextNormalizer
import com.simone.jarvismobile.core.navigation.LatLng
import com.simone.jarvismobile.core.navigation.Place
import com.simone.jarvismobile.core.navigation.PlaceCategory
import com.simone.jarvismobile.core.navigation.PlaceHit
import com.simone.jarvismobile.core.navigation.PlaceSearchRanker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline destination/POI search, reverse geocoding, favourites and history.
 * Recall is SQLite FTS; ranking is the tested `:core` ranker. Coordinates only
 * ever come from this local index or a stored favourite — never invented by the
 * model (spec §8, §9, §21). Nothing leaves the device.
 */
@Singleton
class PlaceSearchRepository @Inject constructor(
    private val dao: NavDao,
    private val store: InstalledRegionStore,
    private val sqliteIndex: RegionSearchIndex,
) {
    /**
     * Loads a region's places into the FTS index the first time it's needed, from
     * navigation/maps/<id>/search.json — a plain JSON array of places. A region
     * without that file simply has no searchable POIs yet.
     *
     * Skipped entirely for a region that already ships `search.sqlite`
     * (pre-built FTS5, spec §2): that file is queried directly by
     * [sqliteIndex] instead, so nothing is parsed/inserted on the phone for it.
     * `search.json` stays the fallback for a region installed before this file
     * existed, or re-fetched from an older catalogue.
     */
    suspend fun ensurePlacesLoaded(regionId: String) = withContext(Dispatchers.IO) {
        if (sqliteIndex.hasSqlite(regionId)) return@withContext
        if (dao.placeCount(regionId) > 0) return@withContext
        val file = File(store.regionDir(regionId), "search.json")
        if (!file.exists()) return@withContext
        runCatching {
            val arr = JSONArray(file.readText())
            val places = ArrayList<PlaceFtsEntity>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                places += PlaceFtsEntity(
                    refId = o.optString("id", UUID.randomUUID().toString()),
                    name = o.getString("name"),
                    address = o.optString("address", ""),
                    category = o.optString("category", "OTHER").uppercase(),
                    regionId = regionId,
                    lat = o.getDouble("lat"),
                    lon = o.getDouble("lon"),
                    importance = o.optDouble("importance", 0.3),
                )
            }
            dao.insertPlaces(places)
            Log.i(TAG, "places_loaded region=$regionId count=${places.size}")
        }.onFailure { Log.w(TAG, "places_load_failed ${it.javaClass.simpleName}") }
    }

    /**
     * Rebuilds a region's place index after a fresh `search.json`/`search.sqlite`
     * download. For a `search.sqlite` region this just drops the cached read-only
     * handle to the old file (renamed-over on disk, but an already-open handle
     * would otherwise keep serving the stale one) — there is nothing to delete
     * from Room, since that region was never copied into it.
     */
    suspend fun reloadPlaces(regionId: String) = withContext(Dispatchers.IO) {
        sqliteIndex.invalidate(regionId)
        runCatching { dao.deleteRegionPlaces(regionId) }
        ensurePlacesLoaded(regionId)
    }

    /**
     * Text/address search across every installed region (spec §5). A region
     * shipping the pre-built `search.sqlite` is queried directly there; a region
     * that only has the older `search.json` is queried from the on-device Room
     * FTS table [ensurePlacesLoaded] populated it into — the two sources never
     * overlap for the same region, so their results are just concatenated.
     */
    suspend fun search(query: String, origin: LatLng?, limit: Int = 10): List<PlaceHit> =
        withContext(Dispatchers.IO) {
            val match = ftsQuery(query) ?: return@withContext emptyList()
            val regionIds = runCatching { store.installed().map { it.id } }.getOrDefault(emptyList())
            val fromSqlite = regionIds.filter { sqliteIndex.hasSqlite(it) }
                .flatMap { sqliteIndex.search(it, match, 60) }
            val fromRoom = runCatching { dao.searchPlaces(match, 60) }.getOrDefault(emptyList()).map { it.toPlace() }
            PlaceSearchRanker.rank(query, fromSqlite + fromRoom, origin, limit)
        }

    suspend fun nearby(category: PlaceCategory, origin: LatLng, regionId: String): PlaceHit? =
        withContext(Dispatchers.IO) {
            val candidates = if (sqliteIndex.hasSqlite(regionId)) {
                sqliteIndex.byCategory(regionId, category)
            } else {
                dao.placesByCategory(category.name, regionId).map { it.toPlace() }
            }
            PlaceSearchRanker.nearest(category, candidates, origin)
        }

    suspend fun reverseGeocode(point: LatLng, regionId: String): Place? =
        withContext(Dispatchers.IO) {
            val all = if (sqliteIndex.hasSqlite(regionId)) {
                sqliteIndex.all(regionId)
            } else {
                dao.placesInRegion(regionId).map { it.toPlace() }
            }
            PlaceSearchRanker.reverse(point, all)
        }

    // --- favourites ---------------------------------------------------------

    suspend fun favorites(): List<FavoritePlace> = dao.favorites().map { it.toFavorite() }

    /** Raw favourite rows (with their id), for a management UI. */
    suspend fun favoriteEntities(): List<NavFavoriteEntity> = dao.favorites()

    suspend fun setFavorite(kind: FavoriteKind, label: String, location: LatLng, placeId: String? = null) {
        dao.upsertFavorite(
            NavFavoriteEntity(
                id = if (kind == FavoriteKind.CUSTOM) UUID.randomUUID().toString() else kind.name,
                kind = kind.name,
                label = label,
                lat = location.lat,
                lon = location.lon,
                placeId = placeId,
            ),
        )
    }

    suspend fun deleteFavorite(id: String) = dao.deleteFavorite(id)

    suspend fun resolveFavorite(query: String): FavoritePlace? =
        FavoriteResolver.resolve(query, favorites())

    // --- history ------------------------------------------------------------

    suspend fun addHistory(label: String, location: LatLng) {
        dao.addHistory(
            NavHistoryEntity(UUID.randomUUID().toString(), label, location.lat, location.lon, System.currentTimeMillis()),
        )
    }

    suspend fun history(limit: Int = 20): List<Pair<String, LatLng>> =
        dao.history(limit).map { it.label to LatLng(it.lat, it.lon) }

    /**
     * Builds a safe FTS MATCH expression: normalised tokens as prefix terms
     * (`roma* navona*`). Returns null when there is nothing to search, so a blank
     * query never hits the database with invalid syntax.
     */
    private fun ftsQuery(query: String): String? {
        val tokens = ItalianTextNormalizer.tokens(query)
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") { it.replace("\"", "") + "*" }
    }

    private companion object { const val TAG = "JarvisPlaceSearch" }
}
