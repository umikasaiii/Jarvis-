package com.simone.jarvismobile.ui.livetranslate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simone.jarvismobile.core.translate.ConversationSegment
import com.simone.jarvismobile.core.translate.LiveTranslationState
import com.simone.jarvismobile.core.translate.TranslationLanguage
import com.simone.jarvismobile.core.translate.TranslationMode
import com.simone.jarvismobile.translate.LiveTranslatorManager
import com.simone.jarvismobile.translate.LiveTranslatorRepository
import com.simone.jarvismobile.translate.ModelStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Everything the Live Translator screen renders, in one immutable snapshot. */
data class LiveTranslatorUi(
    val languageA: TranslationLanguage = TranslationLanguage.ITALIAN,
    val languageB: TranslationLanguage = TranslationLanguage.ENGLISH,
    val mode: TranslationMode = TranslationMode.AUTO,
    val ttsEnabled: Boolean = true,
    val wifiOnly: Boolean = true,
    val modelStatuses: Map<TranslationLanguage, ModelStatus> = emptyMap(),
)

@HiltViewModel
class LiveTranslatorViewModel @Inject constructor(
    private val manager: LiveTranslatorManager,
    private val repo: LiveTranslatorRepository,
) : ViewModel() {

    val state: StateFlow<LiveTranslationState> = manager.state
    val session = manager.session
    val segments: StateFlow<List<ConversationSegment>> = manager.segments
    val partial: StateFlow<String> = manager.partial
    val listeningLanguage: StateFlow<TranslationLanguage?> = manager.listeningLanguage
    val openScreenRequest: StateFlow<Int> = manager.openScreenRequest
    val audioDetail: StateFlow<String> = manager.audioDetail

    val languageA: StateFlow<TranslationLanguage> = repo.languageA
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TranslationLanguage.ITALIAN)
    val languageB: StateFlow<TranslationLanguage> = repo.languageB
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TranslationLanguage.ENGLISH)
    val mode: StateFlow<TranslationMode> = repo.mode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TranslationMode.AUTO)
    val ttsEnabled: StateFlow<Boolean> = repo.ttsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val wifiOnly: StateFlow<Boolean> = repo.wifiOnly
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val modelStatuses: StateFlow<Map<TranslationLanguage, ModelStatus>> = repo.modelStatuses

    init {
        viewModelScope.launch { runCatching { repo.refreshModels() } }
    }

    fun setLanguageA(lang: TranslationLanguage) {
        viewModelScope.launch { repo.setPair(lang, languageB.value) }
    }

    fun setLanguageB(lang: TranslationLanguage) {
        viewModelScope.launch { repo.setPair(languageA.value, lang) }
    }

    fun setMode(mode: TranslationMode) {
        viewModelScope.launch { repo.setMode(mode) }
    }

    fun setTtsEnabled(value: Boolean) {
        viewModelScope.launch { repo.setTtsEnabled(value) }
    }

    fun setWifiOnly(value: Boolean) {
        viewModelScope.launch { repo.setWifiOnly(value) }
    }

    fun downloadModel(lang: TranslationLanguage) {
        viewModelScope.launch { runCatching { repo.download(lang) } }
    }

    fun deleteModel(lang: TranslationLanguage) {
        viewModelScope.launch { runCatching { repo.delete(lang) } }
    }

    fun start() {
        viewModelScope.launch {
            val session = repo.buildSession(languageA.value, languageB.value, mode.value)
            manager.start(session)
        }
    }

    fun testVoice(language: TranslationLanguage) = manager.testVoice(language)
    fun pushToTalk(source: TranslationLanguage) = manager.pushToTalk(source)
    fun swap() {
        manager.swap()
        // Persist the swapped pair too, so it survives leaving the screen.
        viewModelScope.launch { manager.session.value?.let { repo.setPair(it.languageA, it.languageB) } }
    }

    fun pause() = manager.pause()
    fun resume() = manager.resume()
    fun stop() = manager.stop()
    fun clear() = manager.clearTranscript()
}
