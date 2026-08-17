package com.simone.jarvismobile.navigation

import android.util.Log
import com.simone.jarvismobile.core.navigation.LatLng
import com.simone.jarvismobile.core.navigation.Place
import com.simone.jarvismobile.core.navigation.PlaceCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Online destination search via TomTom's Fuzzy Search API — the same TomTom
 * account/key already saved for live traffic ([TrafficApiKeyStore],
 * [TomTomTrafficFetcher]). Used only as a **fill-in** when the offline index
 * (`PlaceSearchRepository`) doesn't have enough results — active
 * automatically once a key is saved, no separate toggle (spec:
 * `docs/PRIVACY.md`) — a meaningfully different, more sensitive exception
 * than the tile fetches: this sends the
 * user's *typed* destination text (plus a rough current position for
 * relevance ranking) to TomTom, not just anonymous coordinates.
 *
 * The endpoint (`.../search/2/search/{query}.json?key=...`) is confirmed
 * against TomTom's own official `tomtom-international/postman-collections`
 * repository on GitHub; the `position`/`poi.name`/`address.freeformAddress`
 * result shape is based on the most consistent public documentation found,
 * not a payload actually fetched and inspected from this sandbox (its
 * network proxy blocks TomTom's own docs sites) — same caveat as
 * [TomTomTrafficFetcher], needing on-device confirmation.
 */
@Singleton
class TomTomSearchFetcher @Inject constructor(
    private val keyStore: TrafficApiKeyStore,
) {
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    suspend fun search(query: String, near: LatLng?, limit: Int = 5): List<Place> = withContext(Dispatchers.IO) {
        val key = keyStore.apiKey ?: return@withContext emptyList()
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext emptyList()
        runCatching {
            val encodedQuery = URLEncoder.encode(trimmed, "UTF-8")
            val bias = if (near != null) "&lat=${near.lat}&lon=${near.lon}" else ""
            val url = "https://api.tomtom.com/search/2/search/$encodedQuery.json" +
                "?key=$key&limit=$limit$bias"
            val response = client.newCall(Request.Builder().url(url).build()).execute()
            response.use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val body = resp.body?.string() ?: return@withContext emptyList()
                val results = JSONObject(body).optJSONArray("results") ?: return@withContext emptyList()
                (0 until results.length()).mapNotNull { i -> parseResult(results.getJSONObject(i)) }
            }
        }.onFailure { Log.w(TAG, "traffic_search_failed ${it.javaClass.simpleName}") }.getOrDefault(emptyList())
    }

    private fun parseResult(r: JSONObject): Place? {
        val position = r.optJSONObject("position") ?: return null
        val lat = position.optDouble("lat", Double.NaN)
        val lon = position.optDouble("lon", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return null
        val poiName = r.optJSONObject("poi")?.optString("name")?.takeIf { it.isNotBlank() }
        val freeform = r.optJSONObject("address")?.optString("freeformAddress")?.takeIf { it.isNotBlank() }
        val name = poiName ?: freeform ?: return null
        return Place(
            id = "tomtom:${r.optString("id", "$lat,$lon")}",
            name = name,
            category = PlaceCategory.OTHER,
            location = LatLng(lat, lon),
            address = freeform.orEmpty(),
        )
    }

    private companion object {
        const val TAG = "JarvisTraffic"
    }
}
