package com.simone.jarvismobile.navigation

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.simone.jarvismobile.core.navigation.PmtilesHeaderParser
import com.simone.jarvismobile.core.navigation.RegionMetadata
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Installs, verifies and removes offline map regions. The concrete, works-today
 * path is importing a PMTiles archive the user already has (via the system file
 * picker) — no server required. The file is streamed into a `*.part`, hashed as
 * it goes, its geographic bounds read from the PMTiles header, and only after a
 * clean copy is it renamed into place with a `metadata.json` beside it. A partial
 * or corrupt import never becomes a usable region (spec §2, §19).
 *
 * Networked catalogue downloads (resumable, manifest-driven) slot in behind this
 * same store; the import path already exercises the copy→verify→install flow.
 */
@Singleton
class RegionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: InstalledRegionStore,
    private val navDao: NavDao,
) {
    enum class Phase { COPYING, VERIFYING, INSTALLING, DONE, FAILED }

    data class Progress(
        val name: String,
        val phase: Phase,
        val bytesDone: Long,
        val bytesTotal: Long,
        val error: String? = null,
    )

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    private val _regions = MutableStateFlow<List<RegionMetadata>>(emptyList())
    val regions: StateFlow<List<RegionMetadata>> = _regions.asStateFlow()

    suspend fun refresh() { _regions.value = store.installed() }

    /**
     * Imports a `.pmtiles` chosen from the picker as a new region. Returns the
     * installed [RegionMetadata], or null on failure (the reason is on [progress]).
     */
    suspend fun importPmtiles(uri: Uri, displayName: String? = null): RegionMetadata? =
        withContext(Dispatchers.IO) {
            val name = displayName ?: queryName(uri)
            val total = querySize(uri)
            _progress.value = Progress(name, Phase.COPYING, 0, total)

            val id = slug(name).ifBlank { UUID.randomUUID().toString() }
            val dir = store.regionDir(id).apply { mkdirs() }
            val finalFile = File(dir, PMTILES_NAME)
            val part = File(dir, "$PMTILES_NAME.part")

            val checksum = runCatching { streamCopyAndHash(uri, part, name, total) }.getOrElse {
                Log.w(TAG, "region_copy_failed ${it.javaClass.simpleName}")
                fail(name, "copia non riuscita"); part.delete(); return@withContext null
            }

            // Read the PMTiles header for the real bounds (spec: pick region by GPS).
            _progress.value = Progress(name, Phase.VERIFYING, total, total)
            val header = runCatching {
                part.inputStream().use { input ->
                    val head = ByteArray(PmtilesHeaderParser.HEADER_SIZE)
                    val read = input.read(head)
                    if (read < PmtilesHeaderParser.HEADER_SIZE) null else PmtilesHeaderParser.parse(head)
                }
            }.getOrNull()
            if (header == null) {
                fail(name, "file PMTiles non valido"); part.delete(); return@withContext null
            }

            // Install: metadata beside the payload, then atomically swap into place.
            _progress.value = Progress(name, Phase.INSTALLING, total, total)
            val meta = buildMetadata(id, name, header.bounds, part.length(), checksum)
            File(dir, METADATA_NAME).writeText(meta.toString())
            if (!part.renameTo(finalFile)) {
                part.copyTo(finalFile, overwrite = true); part.delete()
            }

            refresh()
            _progress.value = Progress(name, Phase.DONE, total, total)
            store.installed().firstOrNull { it.id == id }
        }

    suspend fun deleteRegion(id: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { navDao.deleteRegionPlaces(id) } // drop this region's POIs too
        val ok = store.regionDir(id).deleteRecursively()
        refresh()
        ok
    }

    /** Recomputes the payload's SHA-256 and compares it to the stored checksum. */
    suspend fun verifyRegion(id: String): Boolean = withContext(Dispatchers.IO) {
        val region = store.installed().firstOrNull { it.id == id } ?: return@withContext false
        val file = File(store.regionDir(id), region.files.pmtiles)
        if (!file.exists() || region.checksumSha256.isBlank()) return@withContext false
        val actual = hashFile(file)
        actual.equals(region.checksumSha256, ignoreCase = true)
    }

    private fun streamCopyAndHash(uri: Uri, dest: File, name: String, total: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var done = 0L
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    done += n
                    if (done % (1 shl 20) < buf.size) {
                        _progress.value = Progress(name, Phase.COPYING, done, total)
                    }
                }
            }
        } ?: error("no input stream")
        return hex(digest.digest())
    }

    private fun hashFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return hex(digest.digest())
    }

    private fun buildMetadata(id: String, name: String, bounds: com.simone.jarvismobile.core.navigation.GeoBounds, size: Long, checksum: String): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("osmDate", "")
            put("sizeBytes", size)
            put("checksumSha256", checksum)
            put("bounds", JSONObject().apply {
                put("minLat", bounds.minLat); put("minLon", bounds.minLon)
                put("maxLat", bounds.maxLat); put("maxLon", bounds.maxLon)
            })
            put("versions", JSONObject().apply {
                put("mapVersion", 1); put("routingVersion", 0)
                put("searchIndexVersion", 0); put("schemaVersion", 1)
            })
            put("files", JSONObject().apply {
                put("pmtiles", PMTILES_NAME); put("routing", ""); put("searchDb", "")
                put("style", "jarvis-navigation.json")
            })
        }
    }

    private fun fail(name: String, reason: String) {
        _progress.value = Progress(name, Phase.FAILED, 0, 0, reason)
    }

    private fun queryName(uri: Uri): String {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "regione"
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && i >= 0 && !c.isNull(i)) name = c.getString(i)
            }
        }
        return name.removeSuffix(".pmtiles")
    }

    private fun querySize(uri: Uri): Long {
        var size = 0L
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.SIZE)
                if (c.moveToFirst() && i >= 0 && !c.isNull(i)) size = c.getLong(i)
            }
        }
        return size
    }

    private fun slug(s: String): String =
        s.lowercase(Locale.ITALIAN).replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40)

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) { val v = b.toInt() and 0xff; sb.append(HEX[v ushr 4]).append(HEX[v and 0xf]) }
        return sb.toString()
    }

    private companion object {
        const val TAG = "JarvisRegionManager"
        const val PMTILES_NAME = "region.pmtiles"
        const val METADATA_NAME = "metadata.json"
        const val HEX = "0123456789abcdef"
    }
}
