package com.simone.jarvismobile.backup.drive

import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google OAuth 2.0 (Authorization Code + PKCE) for the Drive `drive.appdata`
 * scope, entirely without Google Play Services / Google Sign-In.
 *
 * JARVIS is deliberately GMS-free elsewhere (see ADR 0013 — Activity
 * Recognition was skipped for exactly this reason), and the on-device Google
 * Sign-In / Credential Manager SDKs that most Android apps use for this are
 * Play-Services-backed. There is a real, standard alternative that isn't:
 * RFC 8252's native-app flow — open Google's consent page in the user's own
 * browser, catch the redirect on a custom URI scheme, exchange the code for
 * tokens over plain HTTPS. Nothing here touches Play Services; it is the same
 * kind of OAuth call any web app makes, just launched via an external browser
 * Intent (Chrome is already in this app's `<queries>`) instead of a WebView
 * (Google disallows WebView-embedded OAuth for exactly this flow).
 *
 * The OAuth client id/secret are never hardcoded: the user creates their own
 * Google Cloud project (docs/GOOGLE_DRIVE_SETUP.md) and pastes them into
 * Impostazioni, so no Google credential of any kind ships inside the APK or
 * this repository.
 */
@Singleton
class GoogleAuthManager @Inject constructor(
    private val store: DriveCredentialStore,
) {
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }
    private val json = Json { ignoreUnknownKeys = true }

    private val _connectResult = MutableStateFlow<DriveConnectResult?>(null)
    /** Published by [handleRedirect]; the UI collects it and calls [consumeConnectResult]. */
    val connectResult: StateFlow<DriveConnectResult?> = _connectResult.asStateFlow()

    fun consumeConnectResult() { _connectResult.value = null }

    /**
     * Builds the "open Google's consent page" intent, or null if the user has
     * not pasted a client id in yet. A fresh PKCE verifier/state pair is
     * generated and persisted (survives a process death while the browser has
     * focus) each time this is called.
     */
    fun beginConnectIntent(): Intent? {
        val clientId = store.clientId
        if (clientId.isBlank()) return null
        val verifier = randomUrlSafe(64)
        val state = randomUrlSafe(24)
        store.pendingVerifier = verifier
        store.pendingState = state
        val challenge = codeChallenge(verifier)
        val uri = Uri.parse(AUTH_ENDPOINT).buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("access_type", "offline")
            .appendQueryParameter("prompt", "consent")
            .appendQueryParameter("state", state)
            .build()
        return Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Handles the redirect back from the browser ([OAuthRedirectActivity]).
     * Validates [state], exchanges the code for tokens, and caches the
     * account's email for display. Returns a user-facing outcome, never throws.
     */
    suspend fun handleRedirect(uri: Uri): DriveConnectResult {
        val result = handleRedirectInternal(uri)
        _connectResult.value = result
        return result
    }

    private suspend fun handleRedirectInternal(uri: Uri): DriveConnectResult = withContext(Dispatchers.IO) {
        val expectedState = store.pendingState
        val verifier = store.pendingVerifier
        store.clearPending()
        val error = uri.getQueryParameter("error")
        if (error != null) return@withContext DriveConnectResult.Failed("Autorizzazione negata ($error)")
        val code = uri.getQueryParameter("code")
        val state = uri.getQueryParameter("state")
        if (code.isNullOrBlank() || verifier.isNullOrBlank() || state != expectedState) {
            return@withContext DriveConnectResult.Failed("Risposta di autorizzazione non valida.")
        }
        val body = FormBody.Builder()
            .add("client_id", store.clientId)
            .apply { if (store.clientSecret.isNotBlank()) add("client_secret", store.clientSecret) }
            .add("code", code)
            .add("code_verifier", verifier)
            .add("grant_type", "authorization_code")
            .add("redirect_uri", REDIRECT_URI)
            .build()
        val tokens = runCatching { postForToken(body) }.getOrElse {
            Log.w(TAG, "token_exchange_failed ${it.javaClass.simpleName}")
            return@withContext DriveConnectResult.Failed("Scambio del codice con Google non riuscito.")
        } ?: return@withContext DriveConnectResult.Failed("Google non ha restituito i token.")
        if (tokens.refreshToken == null) {
            return@withContext DriveConnectResult.Failed(
                "Google non ha restituito un refresh token: ricollega revocando prima l'accesso da " +
                    "myaccount.google.com/permissions.",
            )
        }
        store.refreshToken = tokens.refreshToken
        store.accessToken = tokens.accessToken
        store.accessTokenExpiresAtMs = System.currentTimeMillis() + tokens.expiresIn * 1_000L
        val email = runCatching { fetchEmail(tokens.accessToken) }.getOrNull()
        store.accountEmail = email
        DriveConnectResult.Connected(email)
    }

    /** A currently-valid access token, refreshing first if it has expired; null when disconnected/revoked. */
    suspend fun validAccessToken(): String? = withContext(Dispatchers.IO) {
        val refreshToken = store.refreshToken ?: return@withContext null
        val current = store.accessToken
        val expiresAt = store.accessTokenExpiresAtMs
        if (current != null && System.currentTimeMillis() < expiresAt - EXPIRY_SAFETY_MARGIN_MS) {
            return@withContext current
        }
        refresh(refreshToken)
    }

    /** Forces a refresh (used after a 401 from the Drive API even if our clock thought the token was fresh). */
    suspend fun forceRefresh(): String? = withContext(Dispatchers.IO) {
        val refreshToken = store.refreshToken ?: return@withContext null
        refresh(refreshToken)
    }

    private suspend fun refresh(refreshToken: String): String? {
        val body = FormBody.Builder()
            .add("client_id", store.clientId)
            .apply { if (store.clientSecret.isNotBlank()) add("client_secret", store.clientSecret) }
            .add("refresh_token", refreshToken)
            .add("grant_type", "refresh_token")
            .build()
        val tokens = runCatching { postForToken(body) }.getOrNull()
        if (tokens == null) {
            // A refresh token only fails this way when it was revoked (account
            // disconnected on Google's side, or the user revoked access) — an
            // expired-but-fixable access token would not reach this branch.
            Log.w(TAG, "refresh_failed_disconnecting")
            disconnect()
            return null
        }
        store.accessToken = tokens.accessToken
        store.accessTokenExpiresAtMs = System.currentTimeMillis() + tokens.expiresIn * 1_000L
        return tokens.accessToken
    }

    /** Revokes the token with Google (best-effort) and clears everything local. */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        val token = store.refreshToken ?: store.accessToken
        if (token != null) {
            runCatching {
                val body = FormBody.Builder().add("token", token).build()
                client.newCall(Request.Builder().url(REVOKE_ENDPOINT).post(body).build()).execute().close()
            }
        }
        store.clearTokens()
    }

    fun isConnected(): Boolean = store.isConnected()
    fun connectedEmail(): String? = store.accountEmail

    private fun postForToken(body: FormBody): Tokens? {
        val request = Request.Builder().url(TOKEN_ENDPOINT).post(body).build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string() ?: return null
            if (!response.isSuccessful) {
                Log.w(TAG, "token_endpoint_error code=${response.code}")
                return null
            }
            return runCatching { json.decodeFromString(TokenResponse.serializer(), text) }
                .getOrNull()
                ?.let { Tokens(it.accessToken, it.refreshToken, it.expiresIn ?: 3600) }
        }
    }

    private fun fetchEmail(accessToken: String?): String? {
        if (accessToken == null) return null
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

    private fun randomUrlSafe(byteCount: Int): String {
        val bytes = ByteArray(byteCount).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun codeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private data class Tokens(val accessToken: String?, val refreshToken: String?, val expiresIn: Long)

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String? = null,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresIn: Long? = null,
    )

    @Serializable
    private data class UserInfo(val email: String? = null)

    companion object {
        /**
         * A private-use custom scheme (this app's own package, reverse-domain —
         * RFC 8252 §7.1) rather than an https App Link: it needs no domain
         * verification and Google explicitly supports it for "Desktop app"
         * OAuth clients, which is the client type this flow needs (see
         * docs/GOOGLE_DRIVE_SETUP.md — NOT the "Android" client type, which is
         * meant for the Play-Services-backed sign-in SDKs this app deliberately
         * avoids). Host-based (`scheme://host`), not path-only (`scheme:/path`),
         * because Android Lint's AppLinkUrlError requires an intent-filter
         * `<data>` for a BROWSABLE/VIEW/DEFAULT filter to declare a host.
         */
        const val REDIRECT_URI = "com.simone.jarvismobile://oauth2redirect"
        private const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
        private const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
        private const val REVOKE_ENDPOINT = "https://oauth2.googleapis.com/revoke"
        private const val USERINFO_ENDPOINT = "https://www.googleapis.com/oauth2/v2/userinfo"

        /** appdata: JARVIS's own hidden folder only, never the user's visible Drive; email: just to label the connected account. */
        private const val SCOPES = "https://www.googleapis.com/auth/drive.appdata email"
        private const val TIMEOUT_SECONDS = 20L
        private const val EXPIRY_SAFETY_MARGIN_MS = 60_000L
        private const val TAG = "JarvisDriveAuth"
    }
}

/** Outcome of completing the browser round trip, for the UI to show. */
sealed interface DriveConnectResult {
    data class Connected(val email: String?) : DriveConnectResult
    data class Failed(val reason: String) : DriveConnectResult
}
