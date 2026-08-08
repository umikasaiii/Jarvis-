package com.simone.jarvismobile.tts

import android.net.Uri
import android.util.Log
import com.simone.jarvismobile.core.tts.VoiceBankReader
import com.simone.jarvismobile.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

enum class NeuralTtsStatus {
    /** No external engine selected — the Android voice is in charge. */
    OFF,

    /** Selected, but a required file is missing. */
    INCOMPLETE,

    /** Files are in place; the session has not been built yet. */
    READY_TO_LOAD,
    LOADING,
    LOADED,
    ERROR,
}

/** What "Prova voce" actually did, so the screen can say something specific. */
sealed interface PreviewOutcome {
    data class Ok(val voice: String, val seconds: Float, val elapsedMs: Long) : PreviewOutcome
    /** The session could not be built; [detail] is the engine's reason. */
    data class NotLoaded(val detail: String) : PreviewOutcome
    data class SynthesisFailed(val voice: String) : PreviewOutcome
    data object NoVoice : PreviewOutcome
}

/** Everything the Settings screen needs to draw the section in one object. */
data class NeuralTtsState(
    val engineId: String = NeuralTtsEngines.NONE,
    val engineLabel: String = "",
    val status: NeuralTtsStatus = NeuralTtsStatus.OFF,
    val model: TtsAsset? = null,
    val voicesFile: TtsAsset? = null,
    val vocabulary: TtsAsset? = null,
    val voices: List<String> = emptyList(),
    val selectedVoice: String = "",
    val vocabularySource: String = "",
    val detail: String = "",
)

/**
 * Owns the external voice: which engine, which files, which voice, and the
 * loaded session.
 *
 * Loading is lazy and sticky. The session is built the first time something asks
 * to speak and then kept for the life of the process, so consecutive replies pay
 * the cost once. Changing any file or switching engine tears it down; nothing
 * else does.
 *
 * The repository never plays audio and never touches the recogniser. It is the
 * configuration and the model, nothing more.
 */
@Singleton
class NeuralTtsRepository @Inject constructor(
    private val settings: SettingsRepository,
    private val assets: TtsAssetStore,
    private val kokoro: KokoroTtsEngine,
    private val player: PcmPlayer,
    private val focus: AudioFocusGate,
) {
    private val mutex = Mutex()

    private val _state = MutableStateFlow(NeuralTtsState())
    val state: StateFlow<NeuralTtsState> = _state.asStateFlow()

    private fun engineFor(id: String): NeuralTtsEngine? = when (id) {
        NeuralTtsEngines.KOKORO -> kokoro
        else -> null
    }

    /** All engines this build offers, for the picker. */
    fun engines(): List<NeuralTtsEngine> = listOf(kokoro)

    /** True when the user has chosen an external engine and its files are present. */
    suspend fun isConfigured(): Boolean {
        val id = settings.ttsEngineId.first()
        val engine = engineFor(id) ?: return false
        val model = assets.fileFor(settings.ttsModelPath.first()) ?: return false
        val needsVoices = TtsAssetKind.VOICES in engine.requiredAssets
        val voices = assets.fileFor(settings.ttsVoicesPath.first())
        return model.isFile && (!needsVoices || voices != null)
    }

    /** Re-reads the configuration and publishes it without touching the session. */
    suspend fun refresh() {
        val id = settings.ttsEngineId.first()
        val engine = engineFor(id)
        val model = assets.describe(settings.ttsModelPath.first(), TtsAssetKind.MODEL)
        val voicesFile = assets.describe(settings.ttsVoicesPath.first(), TtsAssetKind.VOICES)
        val vocabulary = assets.describe(settings.ttsVocabularyPath.first(), TtsAssetKind.VOCABULARY)
        val loadedEngine = engine?.takeIf { it.isLoaded }
        val loaded = loadedEngine != null

        val status = when {
            engine == null -> NeuralTtsStatus.OFF
            model == null || (TtsAssetKind.VOICES in engine.requiredAssets && voicesFile == null) ->
                NeuralTtsStatus.INCOMPLETE
            loaded -> NeuralTtsStatus.LOADED
            _state.value.status == NeuralTtsStatus.ERROR -> NeuralTtsStatus.ERROR
            else -> NeuralTtsStatus.READY_TO_LOAD
        }
        // The voice list does not need the graph: the names are entries in the
        // pack, so they can be shown the moment the file is imported rather than
        // after a ~90 MB session has been built.
        val names = loadedEngine?.voices()
            ?: voicesFile?.let { readVoiceNames(it.path) }
            ?: emptyList()

        _state.value = _state.value.copy(
            engineId = id,
            engineLabel = engine?.label.orEmpty(),
            status = status,
            model = model,
            voicesFile = voicesFile,
            vocabulary = vocabulary,
            voices = names.ifEmpty { _state.value.voices },
            selectedVoice = resolveVoice(settings.ttsNeuralVoice.first(), names),
        )
    }

    /** Reads only the names out of the pack; cheap enough to do on every refresh. */
    private suspend fun readVoiceNames(path: String): List<String>? = withContext(Dispatchers.IO) {
        val file = assets.fileFor(path) ?: return@withContext null
        runCatching { file.inputStream().use { VoiceBankReader.readNames(it) } }
            .onFailure { Log.w(TAG, "voice_names_failed ${it.javaClass.simpleName}") }
            .getOrNull()
    }

    /**
     * Keeps the stored choice when the pack still has it, and otherwise falls
     * back — a voice that vanished (a different pack, a renamed file) must not
     * leave the selector pointing at nothing.
     */
    private suspend fun resolveVoice(stored: String, available: List<String>): String {
        if (available.isEmpty()) return stored
        if (stored in available) return stored
        val fallback = preferredItalian(available) ?: available.first()
        settings.setTtsNeuralVoice(fallback)
        return fallback
    }

    /**
     * Builds the session if it is not up yet. Safe to call before every reply —
     * it is a flag check once loaded.
     */
    suspend fun ensureLoaded(): NeuralTtsEngine? {
        val id = settings.ttsEngineId.first()
        val engine = engineFor(id) ?: return null
        if (engine.isLoaded) return engine
        return mutex.withLock {
            if (engine.isLoaded) return@withLock engine
            val model = assets.fileFor(settings.ttsModelPath.first()) ?: run {
                _state.value = _state.value.copy(status = NeuralTtsStatus.INCOMPLETE)
                return@withLock null
            }
            val voices = assets.fileFor(settings.ttsVoicesPath.first())
            val vocabulary = assets.fileFor(settings.ttsVocabularyPath.first())

            _state.value = _state.value.copy(status = NeuralTtsStatus.LOADING)
            when (val result = engine.load(model, voices, vocabulary)) {
                is TtsLoadResult.Ok -> {
                    val info = result.info
                    // A voice chosen before the pack was read may not exist in it.
                    val stored = settings.ttsNeuralVoice.first()
                    val voice = stored.takeIf { it in info.voices }
                        ?: preferredItalian(info.voices)
                        ?: info.voices.firstOrNull().orEmpty()
                    if (voice != stored) settings.setTtsNeuralVoice(voice)
                    _state.value = _state.value.copy(
                        status = NeuralTtsStatus.LOADED,
                        voices = info.voices,
                        selectedVoice = voice,
                        vocabularySource = info.vocabularySource,
                        detail = info.detail,
                    )
                    Log.i(TAG, "neural_tts_loaded engine=$id voices=${info.voices.size}")
                    engine
                }
                is TtsLoadResult.Failed -> {
                    _state.value = _state.value.copy(
                        status = NeuralTtsStatus.ERROR,
                        detail = result.reason,
                    )
                    Log.w(TAG, "neural_tts_load_failed engine=$id reason=${result.reason}")
                    null
                }
            }
        }
    }

    /**
     * Kokoro names Italian voices `if_*` (female) and `im_*` (male). Prefer an
     * Italian one over an English default, but do not prefer a gender — that is
     * the user's choice, and picking one silently would override it.
     */
    private fun preferredItalian(voices: List<String>): String? =
        voices.firstOrNull { it.startsWith("if_") || it.startsWith("im_") }

    suspend fun setEngine(id: String) {
        settings.setTtsEngineId(id)
        unload()
        refresh()
    }

    suspend fun importAsset(uri: Uri, kind: TtsAssetKind): TtsImportResult {
        val result = assets.import(uri, kind)
        if (result is TtsImportResult.Ok) selectAsset(result.asset.path, kind)
        return result
    }

    /** Points a slot at an already-imported file (the model-manager path). */
    suspend fun selectAsset(path: String, kind: TtsAssetKind) {
        when (kind) {
            TtsAssetKind.MODEL -> settings.setTtsModelPath(path)
            TtsAssetKind.VOICES -> settings.setTtsVoicesPath(path)
            TtsAssetKind.VOCABULARY -> settings.setTtsVocabularyPath(path)
        }
        unload()
        refresh()
    }

    /** Forgets a slot; with [deleteFile] the copy in app storage goes too. */
    suspend fun clearAsset(kind: TtsAssetKind, deleteFile: Boolean) {
        val path = when (kind) {
            TtsAssetKind.MODEL -> settings.ttsModelPath.first()
            TtsAssetKind.VOICES -> settings.ttsVoicesPath.first()
            TtsAssetKind.VOCABULARY -> settings.ttsVocabularyPath.first()
        }
        if (deleteFile && path.isNotBlank()) assets.delete(path)
        selectAsset("", kind)
    }

    suspend fun setVoice(voice: String) {
        settings.setTtsNeuralVoice(voice)
        _state.value = _state.value.copy(selectedVoice = voice)
    }

    /**
     * Synthesises a fixed Italian sentence with [voice] through the real graph
     * and plays it. This is the only honest way to answer "does the voice work?"
     * — it exercises loading, phonemisation, inference and playback in the same
     * order a reply does, and reports which step gave way.
     *
     * Entirely offline: nothing on this path opens a socket.
     */
    suspend fun preview(voice: String = ""): PreviewOutcome {
        val engine = ensureLoaded() ?: return PreviewOutcome.NotLoaded(_state.value.detail)
        val chosen = voice.ifBlank { settings.ttsNeuralVoice.first() }
            .ifBlank { _state.value.voices.firstOrNull().orEmpty() }
        if (chosen.isBlank()) return PreviewOutcome.NoVoice

        val speed = settings.ttsSpeechRate.first()
        val volume = settings.ttsVolume.first()
        val startedAt = System.currentTimeMillis()
        val pcm = engine.synthesize(SAMPLE, chosen, speed)
        if (pcm == null || pcm.isEmpty()) return PreviewOutcome.SynthesisFailed(chosen)
        val elapsed = System.currentTimeMillis() - startedAt

        focus.acquire { player.stop() }
        try {
            player.start(engine.sampleRate)
            player.setVolume(volume)
            player.write(pcm)
            player.drain()
        } finally {
            focus.release()
        }
        return PreviewOutcome.Ok(chosen, pcm.size.toFloat() / engine.sampleRate, elapsed)
    }

    fun importedAssets(): List<TtsAsset> = assets.list()

    /** Drops the session. The files and the choice stay. */
    fun unload() {
        engines().forEach { it.release() }
        _state.value = _state.value.copy(
            status = if (_state.value.engineId.isBlank()) NeuralTtsStatus.OFF else NeuralTtsStatus.READY_TO_LOAD,
        )
    }

    private companion object {
        const val TAG = "JarvisNeuralTts"

        /** Exercises the shaping rules and a few awkward Italian sounds. */
        const val SAMPLE =
            "Buonasera Simone. Sono JARVIS: alle 15:30 hai la revisione dell'auto, " +
                "e la batteria è al 68 per cento."
    }
}
