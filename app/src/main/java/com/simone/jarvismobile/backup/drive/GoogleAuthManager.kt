package com.simone.jarvismobile.backup.drive

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Google Drive authorization for the `drive.file` scope via the Identity/
 * AuthorizationClient API (`com.google.android.gms:play-services-auth`)
 * against an "Android" OAuth client — matched by this app's package name and
 * **release** signing certificate SHA-1, configured entirely in Google Cloud
 * Console (docs/GOOGLE_DRIVE_SETUP.md). No client id/secret, no PKCE, no
 * custom redirect scheme: Play Services owns the whole exchange.
 *
 * This is a deliberate, scoped exception to this app's general Play-Services-
 * free stance — see docs/DECISIONS/0015-drive-authorization-client.md, which
 * supersedes the earlier GMS-free design (ADR 0014) for this one feature.
 *
 * No offline access is requested (`requestOfflineAccess`, which needs a
 * *separate* Web-application OAuth client): [Identity.getAuthorizationClient]
 * caches consent at the Play Services layer, so calling [currentAccessToken]
 * again later — including from a background [android.app.Service]/Worker with
 * no visible Activity — resolves silently as long as the user has not revoked
 * access, with no UI and nothing for this app to refresh itself.
 */
@Singleton
class GoogleAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: DriveCredentialStore,
) {
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }
    private val json = Json { ignoreUnknownKeys = true }

    private val authorizationClient by lazy { Identity.getAuthorizationClient(context) }

    /**
     * Starts (or silently completes) Drive authorization. Call this from a
     * screen with a visible Activity — [DriveAuthOutcome.NeedsResolution]
     * carries an [IntentSenderRequest] that must be launched via
     * `ActivityResultContracts.StartIntentSenderForResult()`; its result then
     * goes to [handleResolution].
     */
    suspend fun requestAuthorization(): DriveAuthOutcome = withContext(Dispatchers.IO) {
        val result = runCatching { authorizationClient.authorize(buildRequest()).awaitResult() }
            .getOrElse {
                Log.w(TAG, "authorize_failed ${it.javaClass.simpleName}")
                return@withContext DriveAuthOutcome.Failed("Richiesta di autorizzazione non riuscita.")
            }
        toOutcome(result)
    }

    /** Processes the [IntentSenderRequest] result from [requestAuthorization]'s NeedsResolution. */
    suspend fun handleResolution(data: Intent?): DriveAuthOutcome = withContext(Dispatchers.IO) {
        val result = runCatching { authorizationClient.getAuthorizationResultFromIntent(data) }
            .getOrElse {
                Log.w(TAG, "resolution_failed ${it.javaClass.simpleName}")
                return@withContext DriveAuthOutcome.Failed("Autorizzazione annullata o non riuscita.")
            }
        toOutcome(result)
    }

    /**
     * A currently-valid access token for a background caller (e.g. the nightly
     * backup worker) — silent by design: if consent was never granted or has
     * been revoked, this returns null rather than trying to show UI from a
     * context that cannot. Only the interactive [requestAuthorization] flow
     * (from a real screen) can (re)establish consent.
     */
    suspend fun currentAccessToken(): String? = withContext(Dispatchers.IO) {
        if (!store.authorized) return@withContext null
        val result = runCatching { authorizationClient.authorize(buildRequest()).awaitResult() }
            .getOrNull() ?: return@withContext null
        if (result.hasResolution()) {
            // Consent no longer holds without user interaction; only the
            // interactive flow can restore it, so this background call reports
            // "not connected" honestly rather than pretending otherwise.
            store.authorized = false
            return@withContext null
        }
        result.accessToken
    }

    fun isConnected(): Boolean = store.isConnected()
    fun connectedEmail(): String? = store.accountEmail

    /** Revokes access with Google (best-effort) and clears local state. */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        runCatching { authorizationClient.revokeAccess().awaitResult() }
            .onFailure { Log.w(TAG, "revoke_failed ${it.javaClass.simpleName}") }
        store.clear()
    }

    private suspend fun toOutcome(result: AuthorizationResult): DriveAuthOutcome {
        if (result.hasResolution()) {
            val pendingIntent = result.pendingIntent
                ?: return DriveAuthOutcome.Failed("Google non ha fornito un modo per completare l'autorizzazione.")
            return DriveAuthOutcome.NeedsResolution(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
        }
        val token = result.accessToken
            ?: return DriveAuthOutcome.Failed("Google non ha restituito un token di accesso.")
        val email = runCatching { fetchEmail(token) }.getOrNull() ?: store.accountEmail
        store.authorized = true
        store.accountEmail = email
        return DriveAuthOutcome.Authorized(email)
    }

    private fun buildRequest(): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE), Scope(EMAIL_SCOPE)))
            .build()

    private fun fetchEmail(accessToken: String): String? {
        val request = Request.Builder()
            .url(USERINFO_ENDPOINT)
            .header("Authorization", "Bearer $accessToken")
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string() ?: return null
            if (!response.isSuccessful) return null
            return runCatching { json.decodeFromString(UserInfo.serializer(), text) }.getOrNull()?.email
        }
    }

    private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
    }

    @Serializable
    private data class UserInfo(val email: String? = null)

    private companion object {
        const val USERINFO_ENDPOINT = "https://www.googleapis.com/oauth2/v2/userinfo"
        const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
        const val EMAIL_SCOPE = "email"
        const val TIMEOUT_SECONDS = 20L
        const val TAG = "JarvisDriveAuth"
    }
}

/** Outcome of [GoogleAuthManager.requestAuthorization]/[GoogleAuthManager.handleResolution]. */
sealed interface DriveAuthOutcome {
    data class Authorized(val email: String?) : DriveAuthOutcome
    data class NeedsResolution(val intentSenderRequest: IntentSenderRequest) : DriveAuthOutcome
    data class Failed(val reason: String) : DriveAuthOutcome
}
