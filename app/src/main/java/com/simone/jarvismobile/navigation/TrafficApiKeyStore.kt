package com.simone.jarvismobile.navigation

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user's own TomTom Developer API key for the opt-in live-traffic layer
 * (see [TomTomTrafficFetcher], `docs/PRIVACY.md` §sanctioned online
 * exceptions). Same Keystore-backed `EncryptedSharedPreferences` pattern as
 * [com.simone.jarvismobile.backup.drive.DriveCredentialStore] — a real
 * credential, so it never sits in the plaintext DataStore every other
 * setting uses (docs/SECURITY.md §21).
 */
@Singleton
class TrafficApiKeyStore @Inject constructor(
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

    var apiKey: String?
        get() = prefs.getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }
        set(value) {
            if (value.isNullOrBlank()) prefs.edit().remove(KEY_API_KEY).apply()
            else prefs.edit().putString(KEY_API_KEY, value.trim()).apply()
        }

    private companion object {
        const val FILE_NAME = "jarvis_traffic_credentials"
        const val KEY_API_KEY = "tomtom_api_key"
    }
}
