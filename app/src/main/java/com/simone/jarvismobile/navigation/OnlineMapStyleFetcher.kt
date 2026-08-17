package com.simone.jarvismobile.navigation

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches the vector tile **source** definition from a free, unlimited,
 * no-API-key hosted style ([OpenFreeMap](https://openfreemap.org), built with
 * the same OpenMapTiles schema + Planetiler this app's own offline pipeline
 * already targets — `tools/navigation/README.md`), for the opt-in
 * "online map fallback" (`SettingsRepository.onlineMapFallbackEnabled`,
 * default off — offline stays the real default, spec: PRIVACY.md §sanctioned
 * online exceptions).
 *
 * Deliberately **does not hardcode a guessed tile URL**: it fetches
 * OpenFreeMap's own hosted style.json and copies whichever vector source it
 * actually contains, verbatim, into `jarvis-navigation.json`'s `jarvis-region`
 * source slot. This repo's build environment could not reach
 * `tiles.openfreemap.org` to confirm the exact endpoint shape (pmtiles-over-HTTP
 * vs. a `{z}/{x}/{y}` tile template) — copying their own source object instead
 * of guessing it means a wrong assumption here can't silently break rendering.
 * [STYLE_URL] itself is still unverified from this environment and needs
 * on-device confirmation; if OpenFreeMap ever changes it, only this one
 * constant needs updating.
 */
@Singleton
class OnlineMapStyleFetcher @Inject constructor() {
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    @Volatile private var cachedSourceJson: String? = null

    /**
     * The vector source object (as a JSON string, e.g. `{"type":"vector","url":"..."}`
     * or `{"type":"vector","tiles":[...]}`) to splice into `jarvis-region`, or
     * null on any failure (offline, blocked, unexpected shape) — the caller
     * then just shows the existing "no map" state, never a crash or fake tile.
     * Fetched once per process and cached; a fresh app start re-checks.
     */
    suspend fun vectorSourceJson(): String? = withContext(Dispatchers.IO) {
        cachedSourceJson?.let { return@withContext it }
        runCatching {
            val response = client.newCall(Request.Builder().url(STYLE_URL).build()).execute()
            response.use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                val sources = JSONObject(body).optJSONObject("sources") ?: return@withContext null
                val key = sources.keys().asSequence()
                    .firstOrNull { sources.optJSONObject(it)?.optString("type") == "vector" }
                    ?: return@withContext null
                sources.getJSONObject(key).toString().also { cachedSourceJson = it }
            }
        }.onFailure { Log.w(TAG, "online_style_fetch_failed ${it.javaClass.simpleName}") }.getOrNull()
    }

    private companion object {
        const val TAG = "JarvisOnlineMap"
        const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
    }
}
