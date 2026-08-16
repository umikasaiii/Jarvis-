package com.simone.jarvismobile.backup.drive

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything Google-Drive-shaped and sensitive: the OAuth client id/secret the
 * user pastes in from their own Google Cloud project, the refresh/access
 * tokens, and the connected account's email. None of this is ever hardcoded
 * or committed — "credenziali cloud fuori dall'APK" — it only exists once the
 * user types it in, and it lives here, Keystore-encrypted at rest, never in
 * the plaintext DataStore the rest of Settings uses. See docs/GOOGLE_DRIVE_SETUP.md.
 */
@Singleton
class DriveCredentialStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var clientId: String
        get() = prefs.getString(KEY_CLIENT_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CLIENT_ID, value.trim()).apply()

    /** Google still expects this for a "Desktop app" OAuth client even with PKCE; may be blank. */
    var clientSecret: String
        get() = prefs.getString(KEY_CLIENT_SECRET, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CLIENT_SECRET, value.trim()).apply()

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_REFRESH_TOKEN, value).apply()

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_ACCESS_TOKEN, value).apply()

    var accessTokenExpiresAtMs: Long
        get() = prefs.getLong(KEY_ACCESS_EXPIRES, 0L)
        set(value) = prefs.edit().putLong(KEY_ACCESS_EXPIRES, value).apply()

    /** Cached once after connecting (userinfo call), only to show "account collegato: …". */
    var accountEmail: String?
        get() = prefs.getString(KEY_EMAIL, null)
        set(value) = prefs.edit().putString(KEY_EMAIL, value).apply()

    /**
     * The PKCE verifier/state for an authorization request in flight, persisted
     * (not just kept in memory) because MagicOS — this app's target OS — is known
     * to kill backgrounded apps aggressively while the browser has focus; without
     * this, a process death between "opened the consent page" and "came back"
     * would silently strand the connection attempt.
     */
    var pendingVerifier: String?
        get() = prefs.getString(KEY_PENDING_VERIFIER, null)
        set(value) = prefs.edit().putString(KEY_PENDING_VERIFIER, value).apply()

    var pendingState: String?
        get() = prefs.getString(KEY_PENDING_STATE, null)
        set(value) = prefs.edit().putString(KEY_PENDING_STATE, value).apply()

    fun clearPending() {
        prefs.edit().remove(KEY_PENDING_VERIFIER).remove(KEY_PENDING_STATE).apply()
    }

    fun isConnected(): Boolean = !refreshToken.isNullOrBlank()

    /** Disconnect: drop tokens and the cached email, keep the pasted-in client id/secret. */
    fun clearTokens() {
        prefs.edit()
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_ACCESS_EXPIRES)
            .remove(KEY_EMAIL)
            .apply()
    }

    private companion object {
        const val FILE_NAME = "jarvis_drive_credentials"
        const val KEY_CLIENT_ID = "client_id"
        const val KEY_CLIENT_SECRET = "client_secret"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_ACCESS_EXPIRES = "access_expires_at_ms"
        const val KEY_EMAIL = "account_email"
        const val KEY_PENDING_VERIFIER = "pending_verifier"
        const val KEY_PENDING_STATE = "pending_state"
    }
}
