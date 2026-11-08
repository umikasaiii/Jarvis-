package com.simone.jarvismobile.ui.livetranslate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simone.jarvismobile.core.translate.ConversationSegment
import com.simone.jarvismobile.core.translate.LiveTranslationState
import com.simone.jarvismobile.core.translate.TranslationLanguage
import com.simone.jarvismobile.core.translate.TranslationMode
import com.simone.jarvismobile.core.translate.SpeakOutcome
import com.simone.jarvismobile.core.translate.TextOutcome
import com.simone.jarvismobile.core.translate.TranslationModels
import com.simone.jarvismobile.core.translate.TranslatorMessages
import com.simone.jarvismobile.core.translate.TranslatorNotice
import com.simone.jarvismobile.translate.LiveTranslatorManager
import com.simone.jarvismobile.translate.LiveTranslatorRepository
import com.simone.jarvismobile.translate.ModelStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The text-translation card (no microphone): what was translated, or why it was not. */
data class TextTranslationUi(
    val busy: Boolean = false,
    val from: TranslationLanguage? = null,
    val to: TranslationLanguage? = null,
    val output: String = "",
    val message: String = "",
)

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
    val notice: StateFlow<TranslatorNotice?> = manager.notice

    private val _text = MutableStateFlow(TextTranslationUi())
    val text: StateFlow<TextTranslationUi> = _text.asStateFlow()

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

    /** The models the current pair needs offline (non-English pairs also need English, the pivot). */
    val requiredModels: StateFlow<List<TranslationLanguage>> = combine(languageA, languageB) { a, b -> TranslationModels.required(a, b) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), listOf(TranslationLanguage.ITALIAN, TranslationLanguage.ENGLISH))

    init {
        viewModelScope.launch { runCatching { repo.refreshModels() } }
    }

    fun setLanguageA(lang: TranslationLanguage) {
        viewModelScope.launch { repo.setPair(lang, languageB.value) }
    }

    fun setLanguageB(lang: TranslationLanguage) {
        viewModelScope.launch { repo.setPair(languageA.value, lang) }
    }

    /** Persists the mode and, if a session is running, switches it live (the turn in flight is dropped). */
    fun setMode(mode: TranslationMode) {
        viewModelScope.launch { repo.setMode(mode) }
        if (manager.session.value != null) manager.changeMode(mode)
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
        if (manager.session.value != null) {
            manager.swap()
            // Persist the swapped pair too, so it survives leaving the screen.
            viewModelScope.launch { manager.session.value?.let { repo.setPair(it.languageA, it.languageB) } }
        } else {
            // No session yet: swapping is just exchanging the saved pair (it used to do nothing at rest).
            viewModelScope.launch { repo.setPair(languageB.value, languageA.value) }
        }
    }

    /**
     * Typed translation: offline, no session, no microphone. [reverse] translates B→A instead of A→B.
     * A missing model is reported, never silently downloaded.
     */
    fun translateText(input: String, reverse: Boolean) {
        val from = if (reverse) languageB.value else languageA.value
        val to = if (reverse) languageA.value else languageB.value
        _text.value = TextTranslationUi(busy = true, from = from, to = to)
        viewModelScope.launch {
            val out = runCatching { manager.translateText(input, from, to) }.getOrElse { TextOutcome.Failed(it.javaClass.simpleName) }
            _text.value = when (out) {
                is TextOutcome.Ok -> TextTranslationUi(from = from, to = to, output = out.text)
                TextOutcome.Empty -> TextTranslationUi(from = from, to = to, message = "Scrivi il testo da tradurre.")
                TextOutcome.TooLong -> TextTranslationUi(from = from, to = to, message = "Testo troppo lungo (massimo 4000 caratteri).")
                is TextOutcome.ModelMissing -> TextTranslationUi(from = from, to = to, message = TranslatorMessages.modelMissing(out.languages))
                is TextOutcome.Failed -> TextTranslationUi(from = from, to = to, message = TranslatorMessages.notice(TranslatorNotice.TranslateFailed(out.code)))
            }
        }
    }

    fun clearText() { _text.value = TextTranslationUi() }

    /** Reads the typed translation aloud; says so when no offline voice exists for the language. */
    fun speakText() {
        val t = _text.value
        val lang = t.to ?: return
        if (t.output.isBlank()) return
        viewModelScope.launch {
            val r = runCatching { manager.speakText(t.output, lang) }.getOrDefault(SpeakOutcome.FAILED)
            val msg = when (r) {
                SpeakOutcome.SPOKEN -> ""
                SpeakOutcome.NO_VOICE -> TranslatorMessages.notice(TranslatorNotice.NoVoice(lang))
                SpeakOutcome.FAILED -> TranslatorMessages.notice(TranslatorNotice.SpeakFailed)
            }
            if (msg.isNotEmpty()) _text.value = _text.value.copy(message = msg)
        }
    }

    fun dismissNotice() = manager.clearNotice()

    fun pause() = manager.pause()
    fun resume() = manager.resume()
    fun stop() = manager.stop()
    fun clear() = manager.clearTranscript()
}
