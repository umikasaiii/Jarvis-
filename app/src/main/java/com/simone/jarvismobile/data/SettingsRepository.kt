package com.simone.jarvismobile.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
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
        val USE_BLUETOOTH = booleanPreferencesKey("use_bluetooth")
        val FOLLOW_UP = booleanPreferencesKey("follow_up_enabled")
        val MODEL_PATH = stringPreferencesKey("llm_model_path")
        val MODEL_NAME = stringPreferencesKey("llm_model_name")
        val ADV_MODEL_PATH = stringPreferencesKey("llm_adv_model_path")
        val ADV_MODEL_NAME = stringPreferencesKey("llm_adv_model_name")
        val VAULT_URI = stringPreferencesKey("vault_tree_uri")
        val RESPONSE_NOTIFICATIONS = booleanPreferencesKey("response_notifications")
        val SHOW_RESPONSE_PREVIEW = booleanPreferencesKey("show_response_preview")
        val REMINDER_NOTIFICATIONS = booleanPreferencesKey("reminder_notifications")
        val REMINDER_MORNING_HOUR = intPreferencesKey("reminder_morning_hour")
    }

    /** Persisted SAF tree URI of the Obsidian vault, or empty if none chosen. */
    val vaultUri: Flow<String> =
        context.settingsDataStore.data.map { it[Keys.VAULT_URI] ?: "" }

    suspend fun setVaultUri(uri: String) {
        context.settingsDataStore.edit { it[Keys.VAULT_URI] = uri }
    }

    suspend fun clearVaultUri() {
        context.settingsDataStore.edit { it.remove(Keys.VAULT_URI) }
    }

    /** Absolute path of the LLM model to auto-load, or empty if none chosen. */
    val modelPath: Flow<String> =
        context.settingsDataStore.data.map { it[Keys.MODEL_PATH] ?: "" }

    val modelName: Flow<String> =
        context.settingsDataStore.data.map { it[Keys.MODEL_NAME] ?: "" }

    suspend fun setActiveModel(path: String, name: String) {
        context.settingsDataStore.edit {
            it[Keys.MODEL_PATH] = path
            it[Keys.MODEL_NAME] = name
        }
    }

    suspend fun clearActiveModel() {
        context.settingsDataStore.edit {
            it.remove(Keys.MODEL_PATH)
            it.remove(Keys.MODEL_NAME)
        }
    }

    /**
     * Optional second, larger model used only for questions that need real
     * reasoning. Keeping a small model for commands and short replies is what
     * keeps JARVIS quick (docs/MODELS.md).
     */
    val advancedModelPath: Flow<String> =
        context.settingsDataStore.data.map { it[Keys.ADV_MODEL_PATH] ?: "" }

    val advancedModelName: Flow<String> =
        context.settingsDataStore.data.map { it[Keys.ADV_MODEL_NAME] ?: "" }

    suspend fun setAdvancedModel(path: String, name: String) {
        context.settingsDataStore.edit {
            it[Keys.ADV_MODEL_PATH] = path
            it[Keys.ADV_MODEL_NAME] = name
        }
    }

    suspend fun clearAdvancedModel() {
        context.settingsDataStore.edit {
            it.remove(Keys.ADV_MODEL_PATH)
            it.remove(Keys.ADV_MODEL_NAME)
        }
    }

    val assistantName: Flow<String> =
        context.settingsDataStore.data.map { it[Keys.NAME] ?: DEFAULT_NAME }

    val recordSeconds: Flow<Int> =
        context.settingsDataStore.data.map { (it[Keys.RECORD_SECONDS] ?: DEFAULT_RECORD_SECONDS).coerceIn(1, 8) }

    /**
     * Whether to route audio to Bluetooth (AirPods) when available. On some ROMs
     * (e.g. MagicOS) Bluetooth call-audio routing requires the system Location
     * toggle to be ON; turning this off lets JARVIS run with the phone mic and
     * speaker only, with no Location prompt.
     */
    val useBluetooth: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.USE_BLUETOOTH] ?: true }

    /**
     * Whether, after JARVIS finishes speaking, the microphone re-opens for a few
     * seconds so the user can reply without pressing again (Phase 4 hands-free
     * follow-up). The window is short and tied to the just-finished exchange — no
     * always-on background mic (docs/PRIVACY.md). Off = one press, one exchange.
     */
    val followUpEnabled: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.FOLLOW_UP] ?: true }

    /** Notify even when a response finishes after the chat is no longer visible. */
    val responseNotifications: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.RESPONSE_NOTIFICATIONS] ?: true }

    /** Off by default so private answers are hidden on the lock screen. */
    val showResponsePreview: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.SHOW_RESPONSE_PREVIEW] ?: false }

    val reminderNotifications: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.REMINDER_NOTIFICATIONS] ?: true }

    val reminderMorningHour: Flow<Int> =
        context.settingsDataStore.data.map { (it[Keys.REMINDER_MORNING_HOUR] ?: 8).coerceIn(0, 23) }

    suspend fun setAssistantName(value: String) {
        context.settingsDataStore.edit { it[Keys.NAME] = value.trim().ifBlank { DEFAULT_NAME } }
    }

    suspend fun setRecordSeconds(value: Int) {
        context.settingsDataStore.edit { it[Keys.RECORD_SECONDS] = value.coerceIn(1, 8) }
    }

    suspend fun setUseBluetooth(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.USE_BLUETOOTH] = value }
    }

    suspend fun setFollowUpEnabled(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.FOLLOW_UP] = value }
    }

    suspend fun setResponseNotifications(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.RESPONSE_NOTIFICATIONS] = value }
    }

    suspend fun setShowResponsePreview(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.SHOW_RESPONSE_PREVIEW] = value }
    }

    suspend fun setReminderNotifications(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.REMINDER_NOTIFICATIONS] = value }
    }

    suspend fun setReminderMorningHour(value: Int) {
        context.settingsDataStore.edit { it[Keys.REMINDER_MORNING_HOUR] = value.coerceIn(0, 23) }
    }

    companion object {
        const val DEFAULT_NAME = "JARVIS"
        const val DEFAULT_RECORD_SECONDS = 3
    }
}
