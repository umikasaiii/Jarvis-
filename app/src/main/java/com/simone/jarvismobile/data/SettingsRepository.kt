package com.simone.jarvismobile.data

import com.simone.jarvismobile.core.speech.SpeechStyle
import com.simone.jarvismobile.core.translate.TranslationAudioOutput
import com.simone.jarvismobile.core.translate.TranslationLanguage
import com.simone.jarvismobile.core.translate.TranslationMode
import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
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
        val PERSONA = stringPreferencesKey("persona_prompt")
        val AUTO_MEMORY_CAPTURE = booleanPreferencesKey("auto_memory_capture")
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
        val WAKE_WORD_ENABLED = booleanPreferencesKey("wake_word_enabled")
        val WAKE_WORD = stringPreferencesKey("wake_word")
        val AUTO_EXPRESSIVE = booleanPreferencesKey("tts_auto_expressive")
        val EXPRESSIVE_INTENSITY = stringPreferencesKey("tts_expressive_intensity")
        val EXPRESSIVE_MANUAL_STYLE = stringPreferencesKey("tts_expressive_manual_style")

        // --- Widget & notifications (Interfaccia) -----------------------
        val WIDGET_SHOW_STATUS = booleanPreferencesKey("widget_show_status")
        val WIDGET_STYLE = stringPreferencesKey("widget_style")
        val WIDGET_TRANSPARENCY = floatPreferencesKey("widget_transparency")
        val NOTIF_SOUND = booleanPreferencesKey("notif_sound")
        val NOTIF_VIBRATION = booleanPreferencesKey("notif_vibration")

        // Offline navigation region catalogue (manifest URL).
        val NAV_MANIFEST_URL = stringPreferencesKey("nav_manifest_url")

        // Document import (Memory & Knowledge).
        val DOC_SAVE_DEFAULT = booleanPreferencesKey("doc_save_to_vault_default")
        val DOC_AUTO_INDEX = booleanPreferencesKey("doc_auto_index")
        val DOC_DEDUP = booleanPreferencesKey("doc_dedup")
        val DOC_OCR_IMAGES = booleanPreferencesKey("doc_ocr_images")

        // --- Live Translator (Phase: live translation) ------------------
        val TRANSLATE_LANG_A = stringPreferencesKey("translate_lang_a")
        val TRANSLATE_LANG_B = stringPreferencesKey("translate_lang_b")
        val TRANSLATE_MODE = stringPreferencesKey("translate_mode")
        val TRANSLATE_TTS = booleanPreferencesKey("translate_tts")
        val TRANSLATE_WIFI_ONLY = booleanPreferencesKey("translate_wifi_only")
        val TRANSLATE_AUDIO_OUTPUT = stringPreferencesKey("translate_audio_output")

        // --- Backup & sync (local-first evening backup) -----------------
        val BACKUP_ENABLED = booleanPreferencesKey("backup_enabled")
        val BACKUP_HOUR = intPreferencesKey("backup_hour")
        val BACKUP_MINUTE = intPreferencesKey("backup_minute")
        val BACKUP_WIFI_ONLY = booleanPreferencesKey("backup_wifi_only")
        val BACKUP_CHARGING_ONLY = booleanPreferencesKey("backup_charging_only")
        val BACKUP_MIN_BATTERY = intPreferencesKey("backup_min_battery")
        val BACKUP_RETENTION_DAILY = intPreferencesKey("backup_retention_daily")
        val BACKUP_RETENTION_WEEKLY = intPreferencesKey("backup_retention_weekly")
        val BACKUP_RETENTION_MONTHLY = intPreferencesKey("backup_retention_monthly")
        val BACKUP_CLOUD_ENABLED = booleanPreferencesKey("backup_cloud_enabled")
        val BACKUP_CLOUD_PROVIDER = stringPreferencesKey("backup_cloud_provider")

        // --- external neural voice (Phase 4b) ---------------------------
        val TTS_ENGINE_ID = stringPreferencesKey("tts_engine_id")
        val TTS_MODEL_PATH = stringPreferencesKey("tts_model_path")
        val TTS_VOICES_PATH = stringPreferencesKey("tts_voices_path")
        val TTS_VOCABULARY_PATH = stringPreferencesKey("tts_vocabulary_path")
        val TTS_NEURAL_VOICE = stringPreferencesKey("tts_neural_voice")
        val TTS_VOLUME = floatPreferencesKey("tts_volume")
        val TTS_SPEECH_ENABLED = booleanPreferencesKey("tts_speech_enabled")
        val TTS_STREAMING = booleanPreferencesKey("tts_streaming")
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

    /**
     * Free-text personality instruction prepended to the system prompt. It shapes
     * tone and character (e.g. "asciutto, ironico, mai servile"), so it takes
     * effect on the next conversation (the live one keeps its seeded prompt). Kept
     * short on purpose: a long persona eats the small model's context.
     */
    val personaPrompt: Flow<String> =
        context.settingsDataStore.data.map { it[Keys.PERSONA] ?: DEFAULT_PERSONA }

    /**
     * Whether JARVIS offers to remember durable personal facts it hears in normal
     * conversation ("mi chiamo…", "sono allergico a…"). It only ever *offers*; the
     * save still goes through an explicit confirmation. On by default.
     */
    val autoMemoryCapture: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.AUTO_MEMORY_CAPTURE] ?: true }

    suspend fun setPersonaPrompt(value: String) {
        context.settingsDataStore.edit {
            val trimmed = value.trim().take(600)
            if (trimmed.isBlank()) it.remove(Keys.PERSONA) else it[Keys.PERSONA] = trimmed
        }
    }

    suspend fun setAutoMemoryCapture(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.AUTO_MEMORY_CAPTURE] = value }
    }

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

    /**
     * Foreground-only wake word. Off by default — this is opt-in and only ever
     * listens while the app is open and visible; it is never a background mic
     * (docs/PRIVACY.md). Blank word falls back to the assistant's name.
     */
    val wakeWordEnabled: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.WAKE_WORD_ENABLED] ?: false }

    val wakeWord: Flow<String> =
        context.settingsDataStore.data.map {
            it[Keys.WAKE_WORD]?.takeIf { w -> w.isNotBlank() } ?: DEFAULT_NAME
        }

    /**
     * Contextual expressivity: JARVIS chooses tone/rhythm from the reply's
     * meaning. On by default. Off falls back to a fixed style. Intensity is
     * "bassa"/"media"/"alta"; the manual style is one of the tone labels.
     */
    val autoExpressive: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.AUTO_EXPRESSIVE] ?: true }

    val expressiveIntensity: Flow<String> =
        context.settingsDataStore.data.map { it[Keys.EXPRESSIVE_INTENSITY] ?: "media" }

    val expressiveManualStyle: Flow<String> =
        context.settingsDataStore.data.map { it[Keys.EXPRESSIVE_MANUAL_STYLE] ?: "naturale" }

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

    suspend fun setWakeWordEnabled(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.WAKE_WORD_ENABLED] = value }
    }

    suspend fun setWakeWord(value: String) {
        context.settingsDataStore.edit { it[Keys.WAKE_WORD] = value.trim() }
    }

    suspend fun setAutoExpressive(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.AUTO_EXPRESSIVE] = value }
    }

    suspend fun setExpressiveIntensity(value: String) {
        context.settingsDataStore.edit { it[Keys.EXPRESSIVE_INTENSITY] = value }
    }

    suspend fun setExpressiveManualStyle(value: String) {
        context.settingsDataStore.edit { it[Keys.EXPRESSIVE_MANUAL_STYLE] = value }
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

    // --- Widget & notifications -----------------------------------------

    /** Whether the 2×1 control widget shows the live status line. */
    val widgetShowStatus: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.WIDGET_SHOW_STATUS] ?: true }

    /** "compatto" or "standard". */
    val widgetStyle: Flow<String> =
        context.settingsDataStore.data.map { it[Keys.WIDGET_STYLE] ?: "standard" }

    /** Widget background opacity, 0..1. */
    val widgetTransparency: Flow<Float> =
        context.settingsDataStore.data.map { (it[Keys.WIDGET_TRANSPARENCY] ?: 1f).coerceIn(0.2f, 1f) }

    val notifSound: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.NOTIF_SOUND] ?: true }

    val notifVibration: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.NOTIF_VIBRATION] ?: true }

    suspend fun setWidgetShowStatus(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.WIDGET_SHOW_STATUS] = value }
    }

    suspend fun setWidgetStyle(value: String) {
        context.settingsDataStore.edit { it[Keys.WIDGET_STYLE] = value }
    }

    suspend fun setWidgetTransparency(value: Float) {
        context.settingsDataStore.edit { it[Keys.WIDGET_TRANSPARENCY] = value.coerceIn(0.2f, 1f) }
    }

    suspend fun setNotifSound(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.NOTIF_SOUND] = value }
    }

    suspend fun setNotifVibration(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.NOTIF_VIBRATION] = value }
    }

    // --- Document import (Memory & Knowledge) ----------------------------

    /** Default for the chat "+" import: copy into the vault vs attach only. */
    val docSaveToVaultDefault: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.DOC_SAVE_DEFAULT] ?: false }

    /** Whether imported documents are indexed for retrieval (vs attached only). */
    val docAutoIndex: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.DOC_AUTO_INDEX] ?: true }

    /** Whether identical files (same SHA-256) raise the duplicate prompt. */
    val docDedup: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.DOC_DEDUP] ?: true }

    /** Whether images are OCR'd on import (off by default: OCR is opt-in). */
    val docOcrImages: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.DOC_OCR_IMAGES] ?: false }

    /** Persisted URL of the offline-map region catalogue (manifest). */
    val navManifestUrl: Flow<String> =
        context.settingsDataStore.data.map { it[Keys.NAV_MANIFEST_URL] ?: "" }

    suspend fun setNavManifestUrl(value: String) {
        context.settingsDataStore.edit { it[Keys.NAV_MANIFEST_URL] = value }
    }

    suspend fun setDocSaveToVaultDefault(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.DOC_SAVE_DEFAULT] = value }
    }

    suspend fun setDocAutoIndex(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.DOC_AUTO_INDEX] = value }
    }

    suspend fun setDocDedup(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.DOC_DEDUP] = value }
    }

    suspend fun setDocOcrImages(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.DOC_OCR_IMAGES] = value }
    }

    // --- Live Translator -------------------------------------------------
    // Only preferences are persisted, never a transcript or any translated
    // content (docs/PRIVACY.md): the last language pair, the preferred mode,
    // whether to speak, Wi-Fi-only model downloads and the audio sink.

    val translateLanguageA: Flow<TranslationLanguage> =
        context.settingsDataStore.data.map {
            it[Keys.TRANSLATE_LANG_A]?.let(TranslationLanguage::fromCode) ?: TranslationLanguage.ITALIAN
        }

    val translateLanguageB: Flow<TranslationLanguage> =
        context.settingsDataStore.data.map {
            it[Keys.TRANSLATE_LANG_B]?.let(TranslationLanguage::fromCode) ?: TranslationLanguage.ENGLISH
        }

    val translateMode: Flow<TranslationMode> =
        context.settingsDataStore.data.map {
            it[Keys.TRANSLATE_MODE]?.let { name -> runCatching { TranslationMode.valueOf(name) }.getOrNull() }
                ?: TranslationMode.AUTO
        }

    val translateTtsEnabled: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.TRANSLATE_TTS] ?: true }

    val translateWifiOnly: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.TRANSLATE_WIFI_ONLY] ?: true }

    val translateAudioOutput: Flow<TranslationAudioOutput> =
        context.settingsDataStore.data.map {
            it[Keys.TRANSLATE_AUDIO_OUTPUT]?.let { name ->
                runCatching { TranslationAudioOutput.valueOf(name) }.getOrNull()
            } ?: TranslationAudioOutput.AUTO
        }

    suspend fun setTranslatePair(a: TranslationLanguage, b: TranslationLanguage) {
        context.settingsDataStore.edit {
            it[Keys.TRANSLATE_LANG_A] = a.code
            it[Keys.TRANSLATE_LANG_B] = b.code
        }
    }

    suspend fun setTranslateMode(mode: TranslationMode) {
        context.settingsDataStore.edit { it[Keys.TRANSLATE_MODE] = mode.name }
    }

    suspend fun setTranslateTtsEnabled(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.TRANSLATE_TTS] = value }
    }

    suspend fun setTranslateWifiOnly(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.TRANSLATE_WIFI_ONLY] = value }
    }

    suspend fun setTranslateAudioOutput(value: TranslationAudioOutput) {
        context.settingsDataStore.edit { it[Keys.TRANSLATE_AUDIO_OUTPUT] = value.name }
    }

    // --- Backup & sync ---------------------------------------------------
    // Local-first, offline evening backup of JARVIS's own data. Off by default;
    // the cloud is an optional later copy, never the source of truth.

    /** Whether the scheduled evening backup runs at all. */
    val backupEnabled: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.BACKUP_ENABLED] ?: false }

    /** Hour of day (0..23) for the scheduled backup. Default 23 (23:30). */
    val backupHour: Flow<Int> =
        context.settingsDataStore.data.map { (it[Keys.BACKUP_HOUR] ?: DEFAULT_BACKUP_HOUR).coerceIn(0, 23) }

    /** Minute of hour (0..59) for the scheduled backup. Default 30 (23:30). */
    val backupMinute: Flow<Int> =
        context.settingsDataStore.data.map { (it[Keys.BACKUP_MINUTE] ?: DEFAULT_BACKUP_MINUTE).coerceIn(0, 59) }

    /** Only run when on un-metered Wi-Fi (matters for the cloud upload). */
    val backupWifiOnly: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.BACKUP_WIFI_ONLY] ?: true }

    /** Only run while charging. Off by default so a nightly local backup still runs. */
    val backupChargingOnly: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.BACKUP_CHARGING_ONLY] ?: false }

    /** Skip the backup below this battery percentage (0 = no floor). */
    val backupMinBattery: Flow<Int> =
        context.settingsDataStore.data.map { (it[Keys.BACKUP_MIN_BATTERY] ?: DEFAULT_BACKUP_MIN_BATTERY).coerceIn(0, 100) }

    val backupRetentionDaily: Flow<Int> =
        context.settingsDataStore.data.map { (it[Keys.BACKUP_RETENTION_DAILY] ?: DEFAULT_RETENTION_DAILY).coerceIn(0, 60) }

    val backupRetentionWeekly: Flow<Int> =
        context.settingsDataStore.data.map { (it[Keys.BACKUP_RETENTION_WEEKLY] ?: DEFAULT_RETENTION_WEEKLY).coerceIn(0, 52) }

    val backupRetentionMonthly: Flow<Int> =
        context.settingsDataStore.data.map { (it[Keys.BACKUP_RETENTION_MONTHLY] ?: DEFAULT_RETENTION_MONTHLY).coerceIn(0, 36) }

    /** Whether to also sync an encrypted copy to a cloud provider after the local backup. */
    val backupCloudEnabled: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.BACKUP_CLOUD_ENABLED] ?: false }

    /** Id of the selected cloud provider, or empty when none. */
    val backupCloudProvider: Flow<String> =
        context.settingsDataStore.data.map { it[Keys.BACKUP_CLOUD_PROVIDER] ?: "" }

    suspend fun setBackupEnabled(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.BACKUP_ENABLED] = value }
    }

    suspend fun setBackupTime(hour: Int, minute: Int) {
        context.settingsDataStore.edit {
            it[Keys.BACKUP_HOUR] = hour.coerceIn(0, 23)
            it[Keys.BACKUP_MINUTE] = minute.coerceIn(0, 59)
        }
    }

    suspend fun setBackupWifiOnly(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.BACKUP_WIFI_ONLY] = value }
    }

    suspend fun setBackupChargingOnly(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.BACKUP_CHARGING_ONLY] = value }
    }

    suspend fun setBackupMinBattery(value: Int) {
        context.settingsDataStore.edit { it[Keys.BACKUP_MIN_BATTERY] = value.coerceIn(0, 100) }
    }

    suspend fun setBackupRetention(daily: Int, weekly: Int, monthly: Int) {
        context.settingsDataStore.edit {
            it[Keys.BACKUP_RETENTION_DAILY] = daily.coerceIn(0, 60)
            it[Keys.BACKUP_RETENTION_WEEKLY] = weekly.coerceIn(0, 52)
            it[Keys.BACKUP_RETENTION_MONTHLY] = monthly.coerceIn(0, 36)
        }
    }

    suspend fun setBackupCloudEnabled(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.BACKUP_CLOUD_ENABLED] = value }
    }

    suspend fun setBackupCloudProvider(value: String) {
        context.settingsDataStore.edit { it[Keys.BACKUP_CLOUD_PROVIDER] = value.trim() }
    }

    companion object {
        const val DEFAULT_NAME = "JARVIS"
        /** A deliberately opinionated default so JARVIS has a character out of the box. */
        const val DEFAULT_PERSONA =
            "Hai un carattere asciutto e sicuro, con un tocco di ironia british alla Tony Stark. " +
                "Dai del tu, vai dritto al punto, niente frasi di circostanza o servili. " +
                "Sei competente e leale; se qualcosa non si può fare lo dici con garbo, senza girarci intorno."
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
        const val DEFAULT_BACKUP_HOUR = 23
        const val DEFAULT_BACKUP_MINUTE = 30
        const val DEFAULT_BACKUP_MIN_BATTERY = 20
        const val DEFAULT_RETENTION_DAILY = 7
        const val DEFAULT_RETENTION_WEEKLY = 4
        const val DEFAULT_RETENTION_MONTHLY = 6
    }
}
