package com.simone.jarvismobile.navigation

import android.util.Log
import com.simone.jarvismobile.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** One downloadable region as listed in a catalogue manifest. */
data class CatalogEntry(
    val id: String,
    val name: String,
    val pmtilesUrl: String,
    val routingUrl: String?,
    val searchUrl: String?,
    /** Pre-built SQLite+FTS5 search index (spec §2) — preferred over [searchUrl] when present. */
    val searchSqliteUrl: String?,
    val sizeBytes: Long,
    val sha256: String?,
)

/**
 * Fetches a lightweight region catalogue (manifest) so the user can pick a region
 * to download instead of pasting URLs (spec §17, §18). The manifest is a small
 * JSON hosted anywhere; internet is used only to read it and to download the
 * chosen region — the maps themselves then work offline.
 *
 * Manifest shape:
 *   { "regions": [
 *       { "id":"lazio","name":"Lazio","pmtilesUrl":"…","routingUrl":"…",
 *         "searchSqliteUrl":"…","searchUrl":"…","sizeBytes":123,"sha256":"…" }, … ] }
 */
@Singleton
class RegionCatalogRepository @Inject constructor(
    private val settings: SettingsRepository,
) {
    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    }

    suspend fun manifestUrl(): String = settings.navManifestUrl.first()
    suspend fun saveManifestUrl(url: String) = settings.setNavManifestUrl(url.trim())

    /** Downloads and parses the manifest at [url]; empty list on any failure. */
    suspend fun fetch(url: String): List<CatalogEntry> = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext emptyList()
        runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val body = resp.body?.string() ?: return@withContext emptyList()
                parse(body)
            }
        }.getOrElse {
            Log.w(TAG, "manifest_fetch_failed ${it.javaClass.simpleName}")
            emptyList()
        }
    }

    private fun parse(text: String): List<CatalogEntry> {
        val arr = JSONObject(text).optJSONArray("regions") ?: return emptyList()
        val out = ArrayList<CatalogEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val pmtiles = o.optString("pmtilesUrl", "")
            if (pmtiles.isBlank()) continue
            out += CatalogEntry(
                id = o.optString("id", o.optString("name", "regione")),
                name = o.optString("name", o.optString("id", "regione")),
                pmtilesUrl = pmtiles,
                routingUrl = o.optString("routingUrl", "").ifBlank { null },
                searchUrl = o.optString("searchUrl", "").ifBlank { null },
                searchSqliteUrl = o.optString("searchSqliteUrl", "").ifBlank { null },
                sizeBytes = o.optLong("sizeBytes", 0L),
                sha256 = o.optString("sha256", "").ifBlank { null },
            )
        }
        return out
    }

    private companion object { const val TAG = "JarvisRegionCatalog" }
}
