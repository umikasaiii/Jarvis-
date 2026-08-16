package com.simone.jarvismobile.backup.drive

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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

/** One file as Drive's appDataFolder reports it. */
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
 * Thin Drive API v3 REST client scoped to `appDataFolder` — JARVIS's own hidden
 * per-app space in the user's Drive: invisible in their normal Drive UI and
 * removed automatically if the app is uninstalled or access is revoked.
 *
 * Plain OkHttp calls against the documented JSON endpoints rather than
 * Google's `google-api-client`/`google-api-services-drive` Java libraries —
 * half a dozen well-defined REST calls do not need that dependency tree, and
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

    /** Uploads [file] as [name] into appDataFolder. */
    suspend fun upload(accessToken: String, name: String, file: File, mimeType: String): DriveResult<DriveFile> =
        withContext(Dispatchers.IO) {
            val metadata = "{\"name\":${jsonEscape(name)},\"parents\":[\"appDataFolder\"]}"
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

    /** Every file JARVIS has stored in its own appDataFolder. */
    suspend fun list(accessToken: String): DriveResult<List<DriveFile>> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$FILES_ENDPOINT?spaces=appDataFolder&pageSize=1000&fields=files(id,name,size,md5Checksum)")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
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

    private fun jsonEscape(s: String): String = buildString {
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
        const val TIMEOUT_SECONDS = 20L
        const val UPLOAD_TIMEOUT_SECONDS = 120L
        const val TAG = "JarvisDriveRest"
    }
}
