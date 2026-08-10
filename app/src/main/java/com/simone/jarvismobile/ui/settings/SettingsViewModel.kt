package com.simone.jarvismobile.ui.settings

import kotlinx.coroutines.flow.MutableStateFlow
import com.simone.jarvismobile.knowledge.KnowledgeRepository
import com.simone.jarvismobile.core.speech.SpeechStyle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simone.jarvismobile.audio.SessionCoordinator
import com.simone.jarvismobile.audio.TtsVoiceOption
import com.simone.jarvismobile.agenda.AgendaRepository
import com.simone.jarvismobile.data.SettingsRepository
import com.simone.jarvismobile.tts.NeuralTtsEngine
import com.simone.jarvismobile.tts.NeuralTtsRepository
import com.simone.jarvismobile.tts.NeuralTtsState
import com.simone.jarvismobile.tts.PreviewOutcome
import com.simone.jarvismobile.tts.TtsAsset
import com.simone.jarvismobile.tts.TtsAssetKind
import com.simone.jarvismobile.tts.TtsImportResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val coordinator: SessionCoordinator,
    private val agenda: AgendaRepository,
    private val knowledge: KnowledgeRepository,
    private val neural: NeuralTtsRepository,
    private val automationService: com.simone.jarvismobile.automation.AutomationServiceController,
) : ViewModel() {

    val assistantName: StateFlow<String> = settings.assistantName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_NAME)

    val personaPrompt: StateFlow<String> = settings.personaPrompt
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_PERSONA)

    val autoMemoryCapture: StateFlow<Boolean> = settings.autoMemoryCapture
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val recordSeconds: StateFlow<Int> = settings.recordSeconds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_RECORD_SECONDS)

    val useBluetooth: StateFlow<Boolean> = settings.useBluetooth
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val followUpEnabled: StateFlow<Boolean> = settings.followUpEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val responseNotifications: StateFlow<Boolean> = settings.responseNotifications
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val showResponsePreview: StateFlow<Boolean> = settings.showResponsePreview
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val reminderNotifications: StateFlow<Boolean> = settings.reminderNotifications
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val reminderMorningHour: StateFlow<Int> = settings.reminderMorningHour
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 8)

    val wakeWordEnabled: StateFlow<Boolean> = settings.wakeWordEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val wakeWord: StateFlow<String> = settings.wakeWord
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_NAME)

    val automationServiceEnabled: StateFlow<Boolean> = settings.automationServiceEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val autoExpressive: StateFlow<Boolean> = settings.autoExpressive
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val expressiveIntensity: StateFlow<String> = settings.expressiveIntensity
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "media")

    val expressiveManualStyle: StateFlow<String> = settings.expressiveManualStyle
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "naturale")

    val ttsVoiceName: StateFlow<String> = settings.ttsVoiceName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    val selectedVoiceName: StateFlow<String?> = coordinator.selectedVoiceName
    val availableVoices: StateFlow<List<TtsVoiceOption>> = coordinator.availableVoices

    val ttsSpeechRate: StateFlow<Float> = settings.ttsSpeechRate
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SettingsRepository.DEFAULT_TTS_RATE,
        )

    val ttsPitch: StateFlow<Float> = settings.ttsPitch
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SettingsRepository.DEFAULT_TTS_PITCH,
        )

    val speakBackgroundResponses: StateFlow<Boolean> = settings.speakBackgroundResponses
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        viewModelScope.launch { coordinator.refreshVoices() }
    }

    fun setAssistantName(value: String) = viewModelScope.launch { settings.setAssistantName(value) }

    fun setPersonaPrompt(value: String) = viewModelScope.launch { settings.setPersonaPrompt(value) }

    fun resetPersonaPrompt() = viewModelScope.launch { settings.setPersonaPrompt(SettingsRepository.DEFAULT_PERSONA) }

    fun setAutoMemoryCapture(value: Boolean) = viewModelScope.launch { settings.setAutoMemoryCapture(value) }
    fun setRecordSeconds(value: Int) = viewModelScope.launch { settings.setRecordSeconds(value) }
    fun setUseBluetooth(value: Boolean) = viewModelScope.launch { settings.setUseBluetooth(value) }
    fun setFollowUpEnabled(value: Boolean) = viewModelScope.launch { settings.setFollowUpEnabled(value) }
    fun setResponseNotifications(value: Boolean) = viewModelScope.launch { settings.setResponseNotifications(value) }
    fun setShowResponsePreview(value: Boolean) = viewModelScope.launch { settings.setShowResponsePreview(value) }
    fun setReminderNotifications(value: Boolean) = viewModelScope.launch {
        settings.setReminderNotifications(value)
        agenda.reload()
    }
    fun setReminderMorningHour(value: Int) = viewModelScope.launch {
        settings.setReminderMorningHour(value)
        agenda.reload()
    }
    fun setWakeWordEnabled(value: Boolean) = viewModelScope.launch { settings.setWakeWordEnabled(value) }
    fun setWakeWord(value: String) = viewModelScope.launch { settings.setWakeWord(value) }

    fun setAutomationServiceEnabled(value: Boolean) = viewModelScope.launch {
        settings.setAutomationServiceEnabled(value)
        automationService.apply(value)
    }
    fun setAutoExpressive(value: Boolean) = viewModelScope.launch { settings.setAutoExpressive(value) }
    fun setExpressiveIntensity(value: String) = viewModelScope.launch { settings.setExpressiveIntensity(value) }
    fun setExpressiveManualStyle(value: String) = viewModelScope.launch { settings.setExpressiveManualStyle(value) }
    fun refreshVoices() = viewModelScope.launch { coordinator.refreshVoices() }
    fun setTtsVoice(name: String) = viewModelScope.launch {
        settings.setTtsVoiceName(name)
        coordinator.configureVoice(name.ifBlank { null }, ttsSpeechRate.value, ttsPitch.value)
    }
    fun setTtsSpeechRate(value: Float) = viewModelScope.launch {
        settings.setTtsSpeechRate(value)
        coordinator.configureVoice(ttsVoiceName.value.ifBlank { null }, value, ttsPitch.value)
    }
    fun setTtsPitch(value: Float) = viewModelScope.launch {
        settings.setTtsPitch(value)
        coordinator.configureVoice(ttsVoiceName.value.ifBlank { null }, ttsSpeechRate.value, value)
    }
    val ttsPauseScale: StateFlow<Float> = settings.ttsPauseScale
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SpeechStyle.NATURALE.pauseScale)

    val ttsExpressiveness: StateFlow<Float> = settings.ttsExpressiveness
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SpeechStyle.NATURALE.expressiveness)

    fun setTtsPauseScale(value: Float) = viewModelScope.launch {
        settings.setTtsPauseScale(value)
        coordinator.configureVoice(ttsVoiceName.value.ifBlank { null }, ttsSpeechRate.value, ttsPitch.value)
    }

    fun setTtsExpressiveness(value: Float) = viewModelScope.launch {
        settings.setTtsExpressiveness(value)
        coordinator.configureVoice(ttsVoiceName.value.ifBlank { null }, ttsSpeechRate.value, ttsPitch.value)
    }

    /** Applies a whole delivery preset: speed, pitch and rhythm move together. */
    fun applySpeechPreset(style: SpeechStyle) = viewModelScope.launch {
        settings.applySpeechPreset(style)
        coordinator.configureVoice(ttsVoiceName.value.ifBlank { null }, style.rate, style.pitch)
    }

    /** Speaks a sample so the chosen delivery can be judged by ear immediately. */
    fun previewVoice() = viewModelScope.launch { coordinator.previewVoice() }

    // --- Offline library ---------------------------------------------------

    val knowledgeStatus: StateFlow<KnowledgeRepository.Status> = knowledge.status

    private val _knowledgeName = MutableStateFlow<String?>(null)
    val knowledgeName: StateFlow<String?> = _knowledgeName

    fun refreshKnowledgeName() = viewModelScope.launch { _knowledgeName.value = knowledge.libraryName() }

    fun setKnowledgeFolder(uri: android.net.Uri) = viewModelScope.launch {
        knowledge.setLibrary(uri)
        _knowledgeName.value = knowledge.libraryName()
    }

    fun clearKnowledgeFolder() = viewModelScope.launch {
        knowledge.clearLibrary()
        _knowledgeName.value = null
    }

    fun reindexKnowledge() = viewModelScope.launch { knowledge.rebuild() }

    fun setSpeakBackgroundResponses(value: Boolean) = viewModelScope.launch {
        settings.setSpeakBackgroundResponses(value)
    }
    fun resetAudio() = coordinator.resetAudio()
    fun newConversation() = coordinator.newConversation()

    // --- Voce JARVIS (external neural TTS) ---------------------------------
    //
    // Import, selection and parameters only. Nothing here touches the recogniser
    // or the transcription path; the engine swap happens behind
    // TextToSpeechEngine, so the rest of the app is unaware.

    val neuralTts: StateFlow<NeuralTtsState> = neural.state

    val ttsSpeechEnabled: StateFlow<Boolean> = settings.ttsSpeechEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val ttsStreaming: StateFlow<Boolean> = settings.ttsStreamingEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val ttsVolume: StateFlow<Float> = settings.ttsVolume
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_TTS_VOLUME)

    private val _importedTtsAssets = MutableStateFlow<List<TtsAsset>>(emptyList())
    val importedTtsAssets: StateFlow<List<TtsAsset>> = _importedTtsAssets

    private val _ttsBusy = MutableStateFlow(false)
    val ttsBusy: StateFlow<Boolean> = _ttsBusy

    private val _ttsMessage = MutableStateFlow("")
    val ttsMessage: StateFlow<String> = _ttsMessage

    /** id to label, for the engine dropdown. */
    fun availableTtsEngines(): List<Pair<String, String>> =
        neural.engines().map { e: NeuralTtsEngine -> e.id to e.label }

    fun refreshNeuralTts() = viewModelScope.launch {
        neural.refresh()
        _importedTtsAssets.value = neural.importedAssets()
    }

    fun setTtsEngine(id: String) = viewModelScope.launch {
        neural.setEngine(id)
        _ttsMessage.value = ""
    }

    fun importTtsAsset(uri: android.net.Uri, kind: TtsAssetKind) = viewModelScope.launch {
        _ttsBusy.value = true
        _ttsMessage.value = "Copia in corso…"
        when (val result = neural.importAsset(uri, kind)) {
            is TtsImportResult.Ok ->
                _ttsMessage.value = "Importato: ${result.asset.name} (${result.asset.sizeLabel})"
            is TtsImportResult.Incomplete ->
                _ttsMessage.value = "Copia incompleta (${result.copiedBytes}/${result.expectedBytes} byte). " +
                    "Riprova con il file completo."
            is TtsImportResult.Failed ->
                _ttsMessage.value = "Importazione fallita: ${result.reason}"
        }
        _importedTtsAssets.value = neural.importedAssets()
        _ttsBusy.value = false
    }

    fun selectTtsAsset(path: String, kind: TtsAssetKind) = viewModelScope.launch {
        neural.selectAsset(path, kind)
        _ttsMessage.value = ""
    }

    fun clearTtsAsset(kind: TtsAssetKind, deleteFile: Boolean) = viewModelScope.launch {
        neural.clearAsset(kind, deleteFile)
        _importedTtsAssets.value = neural.importedAssets()
    }

    fun setNeuralVoice(voice: String) = viewModelScope.launch {
        neural.setVoice(voice)
        _ttsMessage.value = "Voce selezionata: $voice"
    }

    /**
     * "Prova voce": runs a real synthesis through the imported graph with the
     * selected voice. Deliberately not the generic preview — that one goes
     * through the whole session path and reports "tts_unavailable" for six
     * different reasons. This one says which step failed.
     */
    fun testNeuralVoice() = viewModelScope.launch {
        _ttsBusy.value = true
        _ttsMessage.value = "Sintesi in corso…"
        coordinator.stopSpeaking()
        _ttsMessage.value = when (val outcome = neural.preview()) {
            is PreviewOutcome.Ok ->
                "Riprodotto con %s — %.1f s di audio, generati in %d ms."
                    .format(outcome.voice, outcome.seconds, outcome.elapsedMs)
            is PreviewOutcome.NotLoaded ->
                "Modello non caricato${if (outcome.detail.isBlank()) "" else ": ${outcome.detail}"}"
            is PreviewOutcome.SynthesisFailed ->
                "La sintesi con «${outcome.voice}» non ha prodotto audio. " +
                    "Controlla il file voci e il vocabolario."
            PreviewOutcome.NoVoice -> "Nessuna voce disponibile: importa voices-v1.0.bin."
        }
        neural.refresh()
        _ttsBusy.value = false
    }

    fun setTtsVolume(value: Float) = viewModelScope.launch { settings.setTtsVolume(value) }

    fun setTtsSpeechEnabled(value: Boolean) = viewModelScope.launch {
        settings.setTtsSpeechEnabled(value)
        if (!value) coordinator.stopSpeaking()
    }

    fun setTtsStreaming(value: Boolean) = viewModelScope.launch { settings.setTtsStreamingEnabled(value) }

    /** Drops the session so the next reply rebuilds it from the current files. */
    fun reloadNeuralTts() = viewModelScope.launch {
        _ttsBusy.value = true
        neural.unload()
        _ttsMessage.value = if (neural.ensureLoaded() != null) "Modello caricato." else "Caricamento fallito."
        neural.refresh()
        _ttsBusy.value = false
    }
}
