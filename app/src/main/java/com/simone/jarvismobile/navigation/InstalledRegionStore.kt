package com.simone.jarvismobile.navigation

import android.content.Context
import android.util.Log
import com.simone.jarvismobile.core.navigation.GeoBounds
import com.simone.jarvismobile.core.navigation.InstallState
import com.simone.jarvismobile.core.navigation.RegionFiles
import com.simone.jarvismobile.core.navigation.RegionMetadata
import com.simone.jarvismobile.core.navigation.RegionVersions
import com.simone.jarvismobile.core.navigation.SUPPORTED_REGION_SCHEMA_VERSION
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The on-disk catalogue of installed offline regions. Layout (spec §2):
 *
 *   filesDir/navigation/maps/<regionId>/metadata.json
 *                                       <region>.pmtiles
 *
 * A region is only reported INSTALLED when its `metadata.json` parses *and* the
 * referenced PMTiles file is present — a half-written or corrupt install is never
 * offered to the navigator (spec §2, §19). This reads real files; when nothing is
 * installed it returns an empty list, which is what drives the "download the map
 * first" state rather than any silent cloud fallback.
 */
@Singleton
class InstalledRegionStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val root: File get() = File(context.filesDir, "navigation/maps")

    fun regionDir(id: String): File = File(root, id)

    suspend fun installed(): List<RegionMetadata> = withContext(Dispatchers.IO) {
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: return@withContext emptyList()
        dirs.mapNotNull { readRegion(it) }
    }

    private fun readRegion(dir: File): RegionMetadata? {
        val meta = File(dir, "metadata.json")
        if (!meta.exists()) return null
        return runCatching {
            val json = JSONObject(meta.readText())
            val b = json.getJSONObject("bounds")
            val v = json.getJSONObject("versions")
            val f = json.getJSONObject("files")
            val pmtiles = f.getString("pmtiles")
            val pmtilesExists = File(dir, pmtiles).exists()
            val versions = RegionVersions(
                v.optInt("mapVersion", 1), v.optInt("routingVersion", 1),
                v.optInt("searchIndexVersion", 1), v.optInt("schemaVersion", 1),
            )
            RegionMetadata(
                id = json.getString("id"),
                name = json.getString("name"),
                bounds = GeoBounds(
                    b.getDouble("minLat"), b.getDouble("minLon"),
                    b.getDouble("maxLat"), b.getDouble("maxLon"),
                ),
                versions = versions,
                osmDate = json.optString("osmDate", ""),
                sizeBytes = json.optLong("sizeBytes", 0L),
                checksumSha256 = json.optString("checksumSha256", ""),
                files = RegionFiles(
                    pmtiles = pmtiles,
                    routing = f.optString("routing", ""),
                    searchDb = f.optString("searchDb", ""),
                    style = f.optString("style", "jarvis-navigation.json"),
                ),
                // Present metadata but missing payload → CORRUPT; readable but built
                // for a schema this build doesn't understand → INCOMPATIBLE. Neither
                // is silently treated as usable (spec §4/§5 "verificare compatibilità").
                state = when {
                    !pmtilesExists -> InstallState.CORRUPT
                    versions.schemaVersion != SUPPORTED_REGION_SCHEMA_VERSION -> InstallState.INCOMPATIBLE
                    else -> InstallState.INSTALLED
                },
            )
        }.getOrElse {
            Log.w(TAG, "region_read_failed ${dir.name} ${it.javaClass.simpleName}")
            null
        }
    }

    /** Absolute path of a region's PMTiles, for the `pmtiles://file://…` source. */
    fun pmtilesPath(region: RegionMetadata): String =
        File(regionDir(region.id), region.files.pmtiles).absolutePath

    private companion object { const val TAG = "JarvisNavRegions" }
}
