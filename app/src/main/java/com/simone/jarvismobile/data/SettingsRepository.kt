package com.simone.jarvismobile.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore by preferencesDataStore(name = "jarvis_settings")

/**
 * Small DataStore-backed store for user preferences that actually take effect in
 * Phase 1 (assistant name shown in the UI, recording-window length). Non-secret
 * only — secrets live in the Keystore (docs/SECURITY.md §21).
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val NAME = stringPreferencesKey("assistant_name")
        val RECORD_SECONDS = intPreferencesKey("record_seconds")
    }

    val assistantName: Flow<String> =
        context.settingsDataStore.data.map { it[Keys.NAME] ?: DEFAULT_NAME }

    val recordSeconds: Flow<Int> =
        context.settingsDataStore.data.map { (it[Keys.RECORD_SECONDS] ?: DEFAULT_RECORD_SECONDS).coerceIn(1, 8) }

    suspend fun setAssistantName(value: String) {
        context.settingsDataStore.edit { it[Keys.NAME] = value.trim().ifBlank { DEFAULT_NAME } }
    }

    suspend fun setRecordSeconds(value: Int) {
        context.settingsDataStore.edit { it[Keys.RECORD_SECONDS] = value.coerceIn(1, 8) }
    }

    companion object {
        const val DEFAULT_NAME = "JARVIS"
        const val DEFAULT_RECORD_SECONDS = 3
    }
}
