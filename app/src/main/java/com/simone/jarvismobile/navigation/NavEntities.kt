package com.simone.jarvismobile.navigation

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.simone.jarvismobile.core.navigation.FavoriteKind
import com.simone.jarvismobile.core.navigation.FavoritePlace
import com.simone.jarvismobile.core.navigation.LatLng
import com.simone.jarvismobile.core.navigation.Place
import com.simone.jarvismobile.core.navigation.PlaceCategory

/**
 * Offline place index, backed by SQLite **FTS4** (spec §8): full-text recall over
 * name/address happens in the database; the final relevance·distance·importance
 * ranking is the tested `:core` ranker. Numeric columns read back as doubles.
 * All local — no Google Places/Geocoder, no network.
 */
@Fts4
@Entity(tableName = "nav_places")
data class PlaceFtsEntity(
    val refId: String,
    val name: String,
    val address: String,
    val category: String,
    val regionId: String,
    val lat: Double,
    val lon: Double,
    val importance: Double,
)

/** A locally-stored favourite (HOME/WORK/CUSTOM) — never uploaded (spec §10, §21). */
@Entity(tableName = "nav_favorites")
data class NavFavoriteEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val label: String,
    val lat: Double,
    val lon: Double,
    val placeId: String?,
)

/** A visited/searched destination, kept only on-device (spec §21). */
@Entity(tableName = "nav_history")
data class NavHistoryEntity(
    @PrimaryKey val id: String,
    val label: String,
    val lat: Double,
    val lon: Double,
    val at: Long,
)

@Dao
interface NavDao {
    // --- places (FTS) ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaces(places: List<PlaceFtsEntity>)

    @Query("SELECT * FROM nav_places WHERE nav_places MATCH :query LIMIT :limit")
    suspend fun searchPlaces(query: String, limit: Int): List<PlaceFtsEntity>

    @Query("SELECT * FROM nav_places WHERE category = :category AND regionId = :regionId")
    suspend fun placesByCategory(category: String, regionId: String): List<PlaceFtsEntity>

    @Query("SELECT * FROM nav_places WHERE regionId = :regionId")
    suspend fun placesInRegion(regionId: String): List<PlaceFtsEntity>

    @Query("SELECT COUNT(*) FROM nav_places WHERE regionId = :regionId")
    suspend fun placeCount(regionId: String): Int

    @Query("DELETE FROM nav_places WHERE regionId = :regionId")
    suspend fun deleteRegionPlaces(regionId: String)

    // --- favourites ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFavorite(favorite: NavFavoriteEntity)

    @Query("SELECT * FROM nav_favorites")
    suspend fun favorites(): List<NavFavoriteEntity>

    @Query("DELETE FROM nav_favorites WHERE id = :id")
    suspend fun deleteFavorite(id: String)

    // --- history ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addHistory(entry: NavHistoryEntity)

    @Query("SELECT * FROM nav_history ORDER BY at DESC LIMIT :limit")
    suspend fun history(limit: Int): List<NavHistoryEntity>
}

// --- mapping to/from the pure-core models -----------------------------------

fun PlaceFtsEntity.toPlace(): Place = Place(
    id = refId,
    name = name,
    category = runCatching { PlaceCategory.valueOf(category) }.getOrDefault(PlaceCategory.OTHER),
    location = LatLng(lat, lon),
    importance = importance,
    address = address,
)

fun NavFavoriteEntity.toFavorite(): FavoritePlace = FavoritePlace(
    kind = runCatching { FavoriteKind.valueOf(kind) }.getOrDefault(FavoriteKind.CUSTOM),
    label = label,
    location = LatLng(lat, lon),
    placeId = placeId,
)
