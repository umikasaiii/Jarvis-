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
) {
    /**
     * Loads a region's places into the FTS index the first time it's needed, from
     * navigation/maps/<id>/search.json — a plain JSON array of places. A region
     * without that file simply has no searchable POIs yet.
     */
    suspend fun ensurePlacesLoaded(regionId: String) = withContext(Dispatchers.IO) {
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

    suspend fun search(query: String, origin: LatLng?, limit: Int = 10): List<PlaceHit> =
        withContext(Dispatchers.IO) {
            val match = ftsQuery(query) ?: return@withContext emptyList()
            val candidates = runCatching { dao.searchPlaces(match, 60) }.getOrDefault(emptyList())
                .map { it.toPlace() }
            PlaceSearchRanker.rank(query, candidates, origin, limit)
        }

    suspend fun nearby(category: PlaceCategory, origin: LatLng, regionId: String): PlaceHit? =
        withContext(Dispatchers.IO) {
            val candidates = dao.placesByCategory(category.name, regionId).map { it.toPlace() }
            PlaceSearchRanker.nearest(category, candidates, origin)
        }

    suspend fun reverseGeocode(point: LatLng, regionId: String): Place? =
        withContext(Dispatchers.IO) {
            val all = dao.placesInRegion(regionId).map { it.toPlace() }
            PlaceSearchRanker.reverse(point, all)
        }

    // --- favourites ---------------------------------------------------------

    suspend fun favorites(): List<FavoritePlace> = dao.favorites().map { it.toFavorite() }

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
