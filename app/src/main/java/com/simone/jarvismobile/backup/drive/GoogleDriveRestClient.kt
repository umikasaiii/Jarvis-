package com.simone.jarvismobile.backup.drive

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** One file (or folder) as Drive reports it. */
data class DriveFile(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val md5Checksum: String?,
)

/** A Drive REST call's outcome: success, an expired/invalid token (caller refreshes & retries once), or a hard error. */
sealed interface DriveResult<out T> {
    data class Ok<T>(val value: T) : DriveResult<T>
    data object Unauthorized : DriveResult<Nothing>
    data class Error(val reason: String) : DriveResult<Nothing>
}

/**
 * Thin Drive API v3 REST client. Everything lives inside one folder this app
 * creates in the user's own Drive ("JARVIS Backups") — the `drive.file` scope
 * only grants access to files/folders the app itself created or the user
 * explicitly opened, and does not include the separate `appDataFolder` space
 * (that requires the `drive.appdata` scope instead). The folder **is** visible
 * in the user's normal Drive UI; see docs/PRIVACY.md.
 *
 * Plain OkHttp calls against the documented JSON endpoints rather than
 * Google's `google-api-client`/`google-api-services-drive` Java libraries —
 * a handful of well-defined REST calls do not need that dependency tree, and
 * OkHttp is already used everywhere else in the app (see OpenMeteoWeatherSource).
 *
 * Every call takes the bearer token as a parameter instead of owning
 * [GoogleAuthManager] itself, so a caller can retry once after a forced
 * refresh when this returns [DriveResult.Unauthorized].
 */
@Singleton
class GoogleDriveRestClient @Inject constructor() {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(UPLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(UPLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }
    private val json = Json { ignoreUnknownKeys = true }

    /** The non-trashed "JARVIS Backups" folder, if this app has already created one. */
    suspend fun findBackupFolder(accessToken: String): DriveResult<DriveFile?> = withContext(Dispatchers.IO) {
        val query = "name=${driveQuote(FOLDER_NAME)} and mimeType='application/vnd.google-apps.folder' and trashed=false"
        val url = FILES_ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("spaces", "drive")
            .addQueryParameter("fields", "files(id,name)")
            .build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer $accessToken").get().build()
        execute(request) { json.decodeFromString(DriveListResponse.serializer(), it).files.firstOrNull()?.toDriveFile() }
    }

    /** Creates the "JARVIS Backups" folder at the root of the user's Drive. */
    suspend fun createBackupFolder(accessToken: String): DriveResult<DriveFile> = withContext(Dispatchers.IO) {
        val metadata = "{\"name\":${jsonQuote(FOLDER_NAME)},\"mimeType\":\"application/vnd.google-apps.folder\"}"
        val request = Request.Builder()
            .url("$FILES_ENDPOINT?fields=id,name")
            .header("Authorization", "Bearer $accessToken")
            .post(metadata.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .build()
        execute(request) { json.decodeFromString(DriveFileResponse.serializer(), it).toDriveFile() }
    }

    /** Uploads [file] as [name] into [parentFolderId]. */
    suspend fun upload(
        accessToken: String,
        name: String,
        file: File,
        mimeType: String,
        parentFolderId: String,
    ): DriveResult<DriveFile> = withContext(Dispatchers.IO) {
        val metadata = "{\"name\":${jsonQuote(name)},\"parents\":[${jsonQuote(parentFolderId)}]}"
        val body = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            // Content-Type per part comes from each RequestBody's own media
            // type; OkHttp rejects a Content-Type header passed explicitly
            // alongside a body that already carries one.
            .addPart(metadata.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .addPart(file.asRequestBody(mimeType.toMediaType()))
            .build()
        val request = Request.Builder()
            .url("$UPLOAD_ENDPOINT?uploadType=multipart&fields=id,name,size,md5Checksum")
            .header("Authorization", "Bearer $accessToken")
            .post(body)
            .build()
        execute(request) { json.decodeFromString(DriveFileResponse.serializer(), it).toDriveFile() }
    }

    /** Every file JARVIS has stored in [parentFolderId]. */
    suspend fun list(accessToken: String, parentFolderId: String): DriveResult<List<DriveFile>> =
        withContext(Dispatchers.IO) {
            val url = FILES_ENDPOINT.toHttpUrl().newBuilder()
                .addQueryParameter("q", "${driveQuote(parentFolderId)} in parents and trashed=false")
                .addQueryParameter("spaces", "drive")
                .addQueryParameter("pageSize", "1000")
                .addQueryParameter("fields", "files(id,name,size,md5Checksum)")
                .build()
            val request = Request.Builder().url(url).header("Authorization", "Bearer $accessToken").get().build()
            execute(request) { json.decodeFromString(DriveListResponse.serializer(), it).files.map { f -> f.toDriveFile() } }
        }

    /** Raw bytes of [fileId]. */
    suspend fun download(accessToken: String, fileId: String): DriveResult<ByteArray> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$FILES_ENDPOINT/$fileId?alt=media")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            when {
                response.code == 401 -> DriveResult.Unauthorized
                !response.isSuccessful -> DriveResult.Error("HTTP ${response.code}")
                else -> response.body?.bytes()?.let { DriveResult.Ok(it) } ?: DriveResult.Error("corpo vuoto")
            }
        }
    }

    suspend fun delete(accessToken: String, fileId: String): DriveResult<Unit> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$FILES_ENDPOINT/$fileId")
            .header("Authorization", "Bearer $accessToken")
            .delete()
            .build()
        client.newCall(request).execute().use { response ->
            when {
                response.code == 401 -> DriveResult.Unauthorized
                // A file already gone (e.g. deleted from another device) counts
                // as successfully deleted here, not as an error to retry forever.
                response.isSuccessful || response.code == 404 -> DriveResult.Ok(Unit)
                else -> DriveResult.Error("HTTP ${response.code}")
            }
        }
    }

    /** MD5 of a local file, for the post-upload integrity check against Drive's own md5Checksum. */
    fun md5(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private inline fun <T> execute(request: Request, parse: (String) -> T): DriveResult<T> {
        client.newCall(request).execute().use { response ->
            val text = response.body?.string()
            return when {
                response.code == 401 -> DriveResult.Unauthorized
                !response.isSuccessful || text == null -> {
                    Log.w(TAG, "drive_http_error code=${response.code}")
                    DriveResult.Error("HTTP ${response.code}")
                }
                else -> runCatching { DriveResult.Ok(parse(text)) }
                    .getOrElse { DriveResult.Error("risposta non valida") }
            }
        }
    }

    /** A double-quoted, escaped JSON string literal. */
    private fun jsonQuote(s: String): String = buildString {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                else -> append(c)
            }
        }
        append('"')
    }

    /**
     * A single-quoted, escaped Drive query-language string literal (Drive's
     * `q=` parameter, e.g. `name='JARVIS Backups'`) — a different quoting rule
     * from JSON's, so this is deliberately a separate function from [jsonQuote].
     */
    private fun driveQuote(s: String): String = buildString {
        append('\'')
        for (c in s) {
            when (c) {
                '\'' -> append("\\'")
                '\\' -> append("\\\\")
                else -> append(c)
            }
        }
        append('\'')
    }

    @Serializable
    private data class DriveFileResponse(
        val id: String,
        val name: String = "",
        val size: String? = null,
        val md5Checksum: String? = null,
    ) {
        fun toDriveFile() = DriveFile(id, name, size?.toLongOrNull() ?: 0L, md5Checksum)
    }

    @Serializable
    private data class DriveListResponse(val files: List<DriveFileResponse> = emptyList())

    private companion object {
        const val FILES_ENDPOINT = "https://www.googleapis.com/drive/v3/files"
        const val UPLOAD_ENDPOINT = "https://www.googleapis.com/upload/drive/v3/files"
        const val FOLDER_NAME = "JARVIS Backups"
        const val TIMEOUT_SECONDS = 20L
        const val UPLOAD_TIMEOUT_SECONDS = 120L
        const val TAG = "JarvisDriveRest"
    }
}
