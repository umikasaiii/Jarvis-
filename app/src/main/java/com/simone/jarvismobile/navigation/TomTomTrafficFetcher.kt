package com.simone.jarvismobile.navigation

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The opt-in live-traffic overlay for JARVIS Drive's internal map — a real,
 * JARVIS-native layer (not an overlay on the Google Maps app), sourced from
 * [TomTom's Traffic API](https://developer.tomtom.com), which is free for
 * mobile-app map+traffic tile display (generous daily allowance, no card
 * required). Requires the user's own free API key
 * ([TrafficApiKeyStore]) — active automatically once a key is saved, no
 * separate toggle; off whenever no key is saved (`docs/PRIVACY.md`).
 *
 * The vector flow tile URL template (`.../traffic/map/4/tile/flow/{style}/{z}/{x}/{y}.pbf?key=...`)
 * is confirmed against TomTom's own official `tomtom-international/postman-collections`
 * repository on GitHub — `developer.tomtom.com`/`docs.tomtom.com` themselves are
 * blocked by this environment's network proxy, so the exact vector schema
 * (source-layer name, per-feature congestion property) used by [TRAFFIC_SOURCE_LAYER]
 * and the paint expression in `jarvis-navigation.json`'s `traffic-flow` layer is
 * based on the most consistent public documentation/community usage found, not
 * a payload actually fetched and inspected from here — it needs on-device
 * confirmation once a real key is in place (does the road color faithfully
 * reflect real congestion, or does everything render one flat color).
 */
@Singleton
class TomTomTrafficFetcher @Inject constructor(
    private val keyStore: TrafficApiKeyStore,
) {
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * The vector source object to splice into `jarvis-traffic`
     * (`{"type":"vector","tiles":["...{z}/{x}/{y}.pbf?key=..."],...}`), or null
     * when no key is saved — the caller then leaves the traffic layer hidden,
     * never a broken/empty tile request.
     */
    fun trafficSourceJson(): String? {
        val key = keyStore.apiKey ?: return null
        val tileUrl = "$TILE_TEMPLATE?key=$key"
        return JSONObject().apply {
            put("type", "vector")
            put("tiles", JSONArray().put(tileUrl))
            put("minzoom", 0)
            put("maxzoom", 22)
        }.toString()
    }

    /**
     * GETs a single known-good tile (zoom 12 over Rome, empirically confirmed
     * to return real flow data on-device — zoom 0 was tried first and failed,
     * whether from an invalid style name or the tile simply being out of the
     * service's actual coverage/zoom range was never pinned down) to confirm
     * [key] is actually accepted before the Settings screen reports it as
     * saved/working — the one and only use of the key outside of normal tile
     * rendering.
     */
    suspend fun verifyApiKey(key: String): Boolean = withContext(Dispatchers.IO) {
        if (key.isBlank()) return@withContext false
        runCatching {
            val url = "https://api.tomtom.com/traffic/map/4/tile/flow/relative/12/2190/1520.pbf?key=${key.trim()}"
            client.newCall(Request.Builder().url(url).build()).execute().use { it.isSuccessful }
        }.onFailure { Log.w(TAG, "traffic_key_verify_failed ${it.javaClass.simpleName}") }.getOrDefault(false)
    }

    companion object {
        /** Source-layer name the vector flow tiles are documented to expose ("Traffic flow"). */
        const val TRAFFIC_SOURCE_LAYER = "Traffic flow"
        private const val TAG = "JarvisTraffic"
        /**
         * "relative" confirmed working on-device against a real key (2026-08-17);
         * "relative0" was tried first (based on an indirectly-sourced style-naming
         * guess) and did not work — style name is exactly "relative", no suffix.
         */
        private const val TILE_TEMPLATE = "https://api.tomtom.com/traffic/map/4/tile/flow/relative/{z}/{x}/{y}.pbf"
    }
}
