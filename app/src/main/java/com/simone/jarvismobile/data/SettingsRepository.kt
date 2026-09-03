package com.simone.jarvismobile.data

import com.simone.jarvismobile.core.speech.SpeechStyle
import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
        val KNOWLEDGE_URI = stringPreferencesKey("knowledge_tree_uri")
        val RESPONSE_NOTIFICATIONS = booleanPreferencesKey("response_notifications")
        val SHOW_RESPONSE_PREVIEW = booleanPreferencesKey("show_response_preview")
        val REMINDER_NOTIFICATIONS = booleanPreferencesKey("reminder_notifications")
        val REMINDER_MORNING_HOUR = intPreferencesKey("reminder_morning_hour")
        val TTS_VOICE_NAME = stringPreferencesKey("tts_voice_name")
        val TTS_SPEECH_RATE = floatPreferencesKey("tts_speech_rate")
        val TTS_PITCH = floatPreferencesKey("tts_pitch")
        val TTS_PAUSE_SCALE = floatPreferencesKey("tts_pause_scale")
        val TTS_EXPRESSIVENESS = floatPreferencesKey("tts_expressiveness")
        val SPEAK_BACKGROUND_RESPONSES = booleanPreferencesKey("speak_background_responses")

        // --- external neural voice (Phase 4b) ---------------------------
        val TTS_ENGINE_ID = stringPreferencesKey("tts_engine_id")
        val TTS_MODEL_PATH = stringPreferencesKey("tts_model_path")
        val TTS_VOICES_PATH = stringPreferencesKey("tts_voices_path")
        val TTS_VOCABULARY_PATH = stringPreferencesKey("tts_vocabulary_path")
        val TTS_NEURAL_VOICE = stringPreferencesKey("tts_neural_voice")
        val TTS_VOLUME = floatPreferencesKey("tts_volume")
        val TTS_SPEECH_ENABLED = booleanPreferencesKey("tts_speech_enabled")
        val TTS_STREAMING = booleanPreferencesKey("tts_streaming")

        // --- JARVIS Core (PC companion) --------------------------------
        val CORE_ENABLED = booleanPreferencesKey("core_enabled")
        val CORE_HOST = stringPreferencesKey("core_host")
        val CORE_PORT = intPreferencesKey("core_port")
        val CORE_USE_HTTPS = booleanPreferencesKey("core_use_https")
        val CORE_TIMEOUT_MS = intPreferencesKey("core_timeout_ms")
        val CORE_API_TOKEN = stringPreferencesKey("core_api_token")
    }

    /**
     * SAF tree URI of the offline reference library (wiki exports, manuals,
     * guides). Deliberately separate from the vault: reference knowledge is
     * evidence, personal notes are memory, and the two must not mix.
     */
    val knowledgeUri: Flow<String> =
        context.settingsDataStore.data.map { it[Keys.KNOWLEDGE_URI] ?: "" }

    suspend fun setKnowledgeUri(value: String) {
        context.settingsDataStore.edit {
            if (value.isBlank()) it.remove(Keys.KNOWLEDGE_URI) else it[Keys.KNOWLEDGE_URI] = value
        }
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

    /** Empty means "best installed offline voice". */
    val ttsVoiceName: Flow<String> =
        context.settingsDataStore.data.map { it[Keys.TTS_VOICE_NAME] ?: "" }

    val ttsSpeechRate: Flow<Float> =
        context.settingsDataStore.data.map {
            (it[Keys.TTS_SPEECH_RATE] ?: DEFAULT_TTS_RATE).coerceIn(MIN_TTS_RATE, MAX_TTS_RATE)
        }

    val ttsPitch: Flow<Float> =
        context.settingsDataStore.data.map {
            (it[Keys.TTS_PITCH] ?: DEFAULT_TTS_PITCH).coerceIn(MIN_TTS_PITCH, MAX_TTS_PITCH)
        }

    /**
     * How long JARVIS holds a pause. Rate and pitch change the voice; this
     * changes the *rhythm*, which is what makes speech sound spoken rather than
     * recited (see core.speech.SpeechShaper).
     */
    val ttsPauseScale: Flow<Float> =
        context.settingsDataStore.data.map {
            (it[Keys.TTS_PAUSE_SCALE] ?: SpeechStyle.NATURALE.pauseScale).coerceIn(0f, 3f)
        }

    /** 0 = flat and machine-like, 1 = fully expressive phrasing. */
    val ttsExpressiveness: Flow<Float> =
        context.settingsDataStore.data.map {
            (it[Keys.TTS_EXPRESSIVENESS] ?: SpeechStyle.NATURALE.expressiveness).coerceIn(0f, 1f)
        }

    /** Optional and off by default: a finished background task may speak aloud. */
    val speakBackgroundResponses: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.SPEAK_BACKGROUND_RESPONSES] ?: false }

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

    suspend fun setTtsVoiceName(value: String) {
        context.settingsDataStore.edit { prefs ->
            val name = value.trim()
            if (name.isBlank()) prefs.remove(Keys.TTS_VOICE_NAME)
            else prefs[Keys.TTS_VOICE_NAME] = name
        }
    }

    suspend fun setTtsSpeechRate(value: Float) {
        context.settingsDataStore.edit {
            it[Keys.TTS_SPEECH_RATE] = value.coerceIn(MIN_TTS_RATE, MAX_TTS_RATE)
        }
    }

    suspend fun setTtsPitch(value: Float) {
        context.settingsDataStore.edit {
            it[Keys.TTS_PITCH] = value.coerceIn(MIN_TTS_PITCH, MAX_TTS_PITCH)
        }
    }

    suspend fun setTtsPauseScale(value: Float) {
        context.settingsDataStore.edit { it[Keys.TTS_PAUSE_SCALE] = value.coerceIn(0f, 3f) }
    }

    // --- external neural voice ------------------------------------------
    // Every file slot is scoped to its engine. Kokoro and Piper take different
    // files, and a single shared "model path" would hand a Piper graph to Kokoro
    // the moment the user switched engines. The pre-Piper keys are still read as
    // a fallback for Kokoro so an existing import is not lost.

    val ttsEngineId: Flow<String> =
        context.settingsDataStore.data.map { it[Keys.TTS_ENGINE_ID] ?: "" }

    private fun modelKey(engine: String) = stringPreferencesKey("tts_model_path_$engine")
    private fun voicesKey(engine: String) = stringPreferencesKey("tts_voices_path_$engine")
    private fun vocabularyKey(engine: String) = stringPreferencesKey("tts_vocabulary_path_$engine")
    private fun voiceKey(engine: String) = stringPreferencesKey("tts_neural_voice_$engine")

    fun ttsModelPath(engine: String): Flow<String> = context.settingsDataStore.data.map {
        it[modelKey(engine)] ?: legacy(engine, it[Keys.TTS_MODEL_PATH])
    }

    fun ttsVoicesPath(engine: String): Flow<String> = context.settingsDataStore.data.map {
        it[voicesKey(engine)] ?: legacy(engine, it[Keys.TTS_VOICES_PATH])
    }

    fun ttsVocabularyPath(engine: String): Flow<String> = context.settingsDataStore.data.map {
        it[vocabularyKey(engine)] ?: legacy(engine, it[Keys.TTS_VOCABULARY_PATH])
    }

    fun ttsNeuralVoice(engine: String): Flow<String> = context.settingsDataStore.data.map {
        it[voiceKey(engine)] ?: legacy(engine, it[Keys.TTS_NEURAL_VOICE])
    }

    /** The old, engine-less keys only ever held Kokoro's files. */
    private fun legacy(engine: String, value: String?): String =
        if (engine == LEGACY_ENGINE) value.orEmpty() else ""

    val ttsVolume: Flow<Float> =
        context.settingsDataStore.data.map {
            (it[Keys.TTS_VOLUME] ?: DEFAULT_TTS_VOLUME).coerceIn(0f, 1f)
        }

    /** Whether JARVIS answers out loud at all. */
    val ttsSpeechEnabled: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.TTS_SPEECH_ENABLED] ?: true }

    /** Sentence-by-sentence synthesis; off means one take for the whole reply. */
    val ttsStreamingEnabled: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.TTS_STREAMING] ?: true }

    suspend fun setTtsEngineId(value: String) {
        context.settingsDataStore.edit { it[Keys.TTS_ENGINE_ID] = value.trim() }
    }

    suspend fun setTtsModelPath(engine: String, value: String) {
        context.settingsDataStore.edit { it[modelKey(engine)] = value }
    }

    suspend fun setTtsVoicesPath(engine: String, value: String) {
        context.settingsDataStore.edit { it[voicesKey(engine)] = value }
    }

    suspend fun setTtsVocabularyPath(engine: String, value: String) {
        context.settingsDataStore.edit { it[vocabularyKey(engine)] = value }
    }

    suspend fun setTtsNeuralVoice(engine: String, value: String) {
        context.settingsDataStore.edit { it[voiceKey(engine)] = value }
    }

    suspend fun setTtsVolume(value: Float) {
        context.settingsDataStore.edit { it[Keys.TTS_VOLUME] = value.coerceIn(0f, 1f) }
    }

    suspend fun setTtsSpeechEnabled(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.TTS_SPEECH_ENABLED] = value }
    }

    suspend fun setTtsStreamingEnabled(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.TTS_STREAMING] = value }
    }

    suspend fun setTtsExpressiveness(value: Float) {
        context.settingsDataStore.edit { it[Keys.TTS_EXPRESSIVENESS] = value.coerceIn(0f, 1f) }
    }

    /** Applies a whole delivery preset in one go (Calmo / Naturale / …). */
    suspend fun applySpeechPreset(style: SpeechStyle) {
        val s = style.coerced()
        context.settingsDataStore.edit {
            it[Keys.TTS_SPEECH_RATE] = s.rate.coerceIn(MIN_TTS_RATE, MAX_TTS_RATE)
            it[Keys.TTS_PITCH] = s.pitch.coerceIn(MIN_TTS_PITCH, MAX_TTS_PITCH)
            it[Keys.TTS_PAUSE_SCALE] = s.pauseScale
            it[Keys.TTS_EXPRESSIVENESS] = s.expressiveness
        }
    }

    suspend fun setSpeakBackgroundResponses(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.SPEAK_BACKGROUND_RESPONSES] = value }
    }

    // --- JARVIS Core (PC companion, optional) ---------------------------
    //
    // Host/port/https/timeout/enabled are plain config, so they live in the
    // same DataStore as everything else. The API token is the one genuine
    // secret among them (docs/SECURITY.md §21: "Secrets only in Android
    // Keystore") and is stored separately in Keystore-backed
    // EncryptedSharedPreferences, never in the plain DataStore file.

    /** Whether JARVIS Core is enabled at all. Off by default: no PC is contacted unless the user opts in. */
    val coreEnabled: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.CORE_ENABLED] ?: false }

    /** LAN host/IP of the PC running JARVIS Core. Empty means "not configured" — never a hardcoded default. */
    val coreHost: Flow<String> = context.settingsDataStore.data.map { it[Keys.CORE_HOST] ?: "" }

    val corePort: Flow<Int> = context.settingsDataStore.data.map { it[Keys.CORE_PORT] ?: DEFAULT_CORE_PORT }

    /** Plain HTTP by default — jarvis-core's own README documents plain HTTP as acceptable on a trusted LAN. */
    val coreUseHttps: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.CORE_USE_HTTPS] ?: false }

    val coreTimeoutMs: Flow<Long> = context.settingsDataStore.data.map {
        (it[Keys.CORE_TIMEOUT_MS] ?: DEFAULT_CORE_TIMEOUT_MS).toLong()
    }

    suspend fun setCoreEnabled(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.CORE_ENABLED] = value }
    }

    suspend fun setCoreHost(value: String) {
        context.settingsDataStore.edit { it[Keys.CORE_HOST] = value.trim() }
    }

    suspend fun setCorePort(value: Int) {
        context.settingsDataStore.edit { it[Keys.CORE_PORT] = value.coerceIn(1, 65_535) }
    }

    suspend fun setCoreUseHttps(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.CORE_USE_HTTPS] = value }
    }

    suspend fun setCoreTimeoutMs(value: Long) {
        context.settingsDataStore.edit {
            it[Keys.CORE_TIMEOUT_MS] = value.coerceIn(1_000L, 120_000L).toInt()
        }
    }

    private val encryptedPrefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "jarvis_secure_settings",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val _coreApiToken by lazy {
        MutableStateFlow(encryptedPrefs.getString(CORE_API_TOKEN_KEY, "") ?: "")
    }

    /** Optional bearer token for Core's `Authorization` header. Empty = no token sent (LAN-trust default). */
    val coreApiToken: Flow<String> get() = _coreApiToken

    suspend fun setCoreApiToken(value: String) {
        val trimmed = value.trim()
        encryptedPrefs.edit().putString(CORE_API_TOKEN_KEY, trimmed).apply()
        _coreApiToken.value = trimmed
    }

    companion object {
        const val DEFAULT_NAME = "JARVIS"
        const val DEFAULT_RECORD_SECONDS = 3
        const val DEFAULT_TTS_VOLUME = 1.0f
        /** Engine the pre-Piper preference keys belonged to. */
        const val LEGACY_ENGINE = "kokoro"
        const val DEFAULT_TTS_RATE = 0.95f
        const val DEFAULT_TTS_PITCH = 0.98f
        const val MIN_TTS_RATE = 0.6f
        const val MAX_TTS_RATE = 1.4f
        const val MIN_TTS_PITCH = 0.7f
        const val MAX_TTS_PITCH = 1.3f

        /** jarvis-core's own default (core/config.py Settings.server_port). Not a live address, just a common default to prefill. */
        const val DEFAULT_CORE_PORT = 8000
        const val DEFAULT_CORE_TIMEOUT_MS = 10_000
        private const val CORE_API_TOKEN_KEY = "core_api_token"
    }
}
