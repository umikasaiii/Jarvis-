package com.simone.jarvismobile.backup.drive

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything Google-Drive-shaped and local: whether the user has completed
 * Drive authorization at least once, their connected account's email (shown
 * as "account collegato: …"), and the id of the "JARVIS Backups" Drive folder
 * this app created (drive.file scope only grants access to files/folders the
 * app itself created — there is no separate hidden appdata space to use).
 *
 * There is no client id/secret or refresh token to hold here: with the
 * Identity/AuthorizationClient API and an "Android" OAuth client (matched by
 * package name + signing certificate), Play Services resolves the right
 * Google Cloud project and manages token refresh itself — see
 * docs/DECISIONS/0015-drive-authorization-client.md.
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

    /** True once the interactive consent flow has completed successfully at least once. */
    var authorized: Boolean
        get() = prefs.getBoolean(KEY_AUTHORIZED, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTHORIZED, value).apply()

    /** Cached after connecting (a userinfo call), only to show "account collegato: …". */
    var accountEmail: String?
        get() = prefs.getString(KEY_EMAIL, null)
        set(value) = prefs.edit().putString(KEY_EMAIL, value).apply()

    /** The "JARVIS Backups" Drive folder id, created on first upload and reused after. */
    var backupFolderId: String?
        get() = prefs.getString(KEY_FOLDER_ID, null)
        set(value) = prefs.edit().putString(KEY_FOLDER_ID, value).apply()

    fun isConnected(): Boolean = authorized

    /** Disconnect: local state only — [GoogleAuthManager.disconnect] also revokes with Google. */
    fun clear() {
        prefs.edit()
            .remove(KEY_AUTHORIZED)
            .remove(KEY_EMAIL)
            .remove(KEY_FOLDER_ID)
            .apply()
    }

    private companion object {
        const val FILE_NAME = "jarvis_drive_credentials"
        const val KEY_AUTHORIZED = "authorized"
        const val KEY_EMAIL = "account_email"
        const val KEY_FOLDER_ID = "backup_folder_id"
    }
}
