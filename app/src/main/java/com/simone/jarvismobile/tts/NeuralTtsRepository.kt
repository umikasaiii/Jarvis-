package com.simone.jarvismobile.tts

import android.net.Uri
import android.util.Log
import com.simone.jarvismobile.data.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

enum class NeuralTtsStatus {
    /** No external engine selected — the Android voice is in charge. */
    OFF,

    /** Selected, but a required file is missing. */
    INCOMPLETE,

    /** Files are in place. NOT ready: the runtime has not been initialised. */
    FILES_READY,
    LOADING,

    /** Runtime and model initialised successfully. Only this one means "Pronto". */
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

/** One file slot as the current engine defines it. */
data class TtsSlot(
    val kind: TtsAssetKind,
    val label: String,
    val required: Boolean,
    val asset: TtsAsset?,
)

/** Everything the Settings screen needs to draw the section in one object. */
data class NeuralTtsState(
    val engineId: String = NeuralTtsEngines.NONE,
    val engineLabel: String = "",
    val status: NeuralTtsStatus = NeuralTtsStatus.OFF,
    /** Only the slots the selected engine actually uses, in display order. */
    val slots: List<TtsSlot> = emptyList(),
    val voices: List<String> = emptyList(),
    val selectedVoice: String = "",
    val sampleRate: Int = 0,
    val vocabularySource: String = "",
    val detail: String = "",
) {
    fun slot(kind: TtsAssetKind): TtsAsset? = slots.firstOrNull { it.kind == kind }?.asset

    /** True only after a real initialisation of runtime and model. */
    val isReady: Boolean get() = status == NeuralTtsStatus.LOADED
}

/**
 * Owns the external voice: which engine, which files, which voice, and the
 * loaded session.
 *
 * Three engines live here — Supertonic, Kokoro and Piper — and the two
 * file-based ones do not share files. Each declares the slots it needs and
 * its file paths are stored under its own keys, so switching engines can
 * never hand one engine the other's model. A Piper voice is a model plus its
 * `.onnx.json`; Kokoro's `voices-v1.0.bin` belongs to Kokoro alone and is
 * never offered to Piper. Supertonic needs no slots at all — see its own
 * class doc for why.
 *
 * Loading is lazy and sticky: the session is built the first time something asks
 * to speak and then kept for the life of the process, so consecutive replies pay
 * the cost once. Changing any file or switching engine tears it down.
 *
 * Status is honest about initialisation. Having the files on disk is
 * [NeuralTtsStatus.FILES_READY], not "ready" — only a successful
 * [NeuralTtsEngine.load] produces [NeuralTtsStatus.LOADED].
 */
@Singleton
class NeuralTtsRepository @Inject constructor(
    private val settings: SettingsRepository,
    private val assets: TtsAssetStore,
    private val supertonic: SupertonicTtsEngine,
    private val kokoro: KokoroTtsEngine,
    private val piper: PiperTtsEngine,
    private val player: PcmPlayer,
    private val focus: AudioFocusGate,
) {
    private val mutex = Mutex()

    private val _state = MutableStateFlow(NeuralTtsState())
    val state: StateFlow<NeuralTtsState> = _state.asStateFlow()

    /**
     * All engines this build offers, in the order Settings shows them.
     * Supertonic first: it is the bundled default (spec — "Supertonic 3 deve
     * diventare il TTS principale"), Kokoro/Piper stay available but are no
     * longer implied as the primary pick by list order.
     */
    fun engines(): List<NeuralTtsEngine> = listOf(supertonic, kokoro, piper)

    /** Diagnostics-only direct handle, for the FAST/BALANCED/QUALITY debug comparison. */
    fun supertonicEngine(): SupertonicTtsEngine = supertonic

    /**
     * Asks every loaded engine to stop generating, immediately. Called from
     * [HybridTtsEngine][com.simone.jarvismobile.audio.HybridTtsEngine].stop() —
     * cheap and safe to call across all engines rather than just the selected
     * one, since [NeuralTtsEngine.cancelSynthesis] is a no-op on an engine that
     * is not mid-synthesis (Kokoro/Piper always; the currently unselected
     * engines here).
     */
    fun cancelActiveSynthesis() {
        engines().forEach { if (it.isLoaded) it.cancelSynthesis() }
    }

    private fun engineFor(id: String): NeuralTtsEngine? = engines().firstOrNull { it.id == id }

    // --- file resolution ---------------------------------------------------

    private suspend fun pathFor(engine: String, kind: TtsAssetKind): String = when (kind) {
        TtsAssetKind.MODEL -> settings.ttsModelPath(engine).first()
        TtsAssetKind.VOICES -> settings.ttsVoicesPath(engine).first()
        TtsAssetKind.VOCABULARY -> settings.ttsVocabularyPath(engine).first()
    }

    private suspend fun fileFor(engine: String, kind: TtsAssetKind): File? =
        assets.fileFor(pathFor(engine, kind))

    private suspend fun setPath(engine: String, kind: TtsAssetKind, path: String) = when (kind) {
        TtsAssetKind.MODEL -> settings.setTtsModelPath(engine, path)
        TtsAssetKind.VOICES -> settings.setTtsVoicesPath(engine, path)
        TtsAssetKind.VOCABULARY -> settings.setTtsVocabularyPath(engine, path)
    }

    /** True when the selected engine has every file it declared as required. */
    suspend fun isConfigured(): Boolean {
        val id = settings.ttsEngineId.first()
        val engine = engineFor(id) ?: return false
        return engine.requiredAssets.all { fileFor(id, it) != null }
    }

    // --- state -------------------------------------------------------------

    /** Re-reads the configuration and publishes it without touching the session. */
    suspend fun refresh() {
        val id = settings.ttsEngineId.first()
        val engine = engineFor(id)
        if (engine == null) {
            _state.value = NeuralTtsState()
            return
        }

        val kinds = TtsAssetKind.entries.filter {
            it in engine.requiredAssets || it in engine.optionalAssets
        }
        val slots = kinds.map { kind ->
            TtsSlot(
                kind = kind,
                label = engine.assetLabel(kind),
                required = kind in engine.requiredAssets,
                asset = assets.describe(pathFor(id, kind), kind),
            )
        }
        val complete = slots.filter { it.required }.all { it.asset != null } && engine.isReadyToLoad()
        val loaded = engine.isLoaded

        val status = when {
            !complete -> NeuralTtsStatus.INCOMPLETE
            loaded -> NeuralTtsStatus.LOADED
            _state.value.status == NeuralTtsStatus.ERROR && _state.value.engineId == id ->
                NeuralTtsStatus.ERROR
            else -> NeuralTtsStatus.FILES_READY
        }

        // The voice list does not need the graph: Kokoro's names are entries in
        // the pack and Piper's are in its config, so both can be shown as soon
        // as the files land rather than after a session has been built.
        val names = if (loaded) {
            engine.voices()
        } else {
            engine.peekVoices(
                model = fileFor(id, TtsAssetKind.MODEL),
                voices = fileFor(id, TtsAssetKind.VOICES),
                vocabulary = fileFor(id, TtsAssetKind.VOCABULARY),
            )
        }

        _state.value = NeuralTtsState(
            engineId = id,
            engineLabel = engine.label,
            status = status,
            slots = slots,
            voices = names,
            selectedVoice = resolveVoice(id, settings.ttsNeuralVoice(id).first(), names),
            sampleRate = if (loaded) engine.sampleRate else 0,
            vocabularySource = if (loaded) _state.value.vocabularySource else "",
            detail = if (_state.value.engineId == id) _state.value.detail else "",
        )
    }

    /**
     * Keeps the stored choice when the pack still has it, and otherwise falls
     * back — a voice that vanished (a different pack, a renamed file) must not
     * leave the selector pointing at nothing.
     */
    private suspend fun resolveVoice(engine: String, stored: String, available: List<String>): String {
        if (available.isEmpty()) return stored
        if (stored in available) return stored
        val fallback = preferredItalian(available) ?: available.first()
        settings.setTtsNeuralVoice(engine, fallback)
        return fallback
    }

    /**
     * Kokoro names Italian voices `if_*` (female) and `im_*` (male). Prefer an
     * Italian one over an English default, but do not prefer a gender — that is
     * the user's choice, and picking one silently would override it.
     */
    private fun preferredItalian(voices: List<String>): String? =
        voices.firstOrNull { it.startsWith("if_") || it.startsWith("im_") }
            ?: voices.firstOrNull { it.startsWith("it_") }

    // --- loading -----------------------------------------------------------

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

            // A self-contained engine (Supertonic: bundled, requiredAssets is
            // empty) resolves its own files internally and never had a MODEL
            // slot to check here — requiring one unconditionally would report
            // "file mancanti" for an engine that has nothing to import.
            val model = if (TtsAssetKind.MODEL in engine.requiredAssets) {
                fileFor(id, TtsAssetKind.MODEL) ?: run {
                    _state.value = _state.value.copy(status = NeuralTtsStatus.INCOMPLETE)
                    return@withLock null
                }
            } else {
                SELF_CONTAINED_PLACEHOLDER
            }
            val voices = fileFor(id, TtsAssetKind.VOICES)
            val vocabulary = fileFor(id, TtsAssetKind.VOCABULARY)

            _state.value = _state.value.copy(status = NeuralTtsStatus.LOADING)
            when (val result = engine.load(model, voices, vocabulary)) {
                is TtsLoadResult.Ok -> {
                    val info = result.info
                    val stored = settings.ttsNeuralVoice(id).first()
                    val voice = stored.takeIf { it in info.voices }
                        ?: preferredItalian(info.voices)
                        ?: info.voices.firstOrNull().orEmpty()
                    if (voice != stored) settings.setTtsNeuralVoice(id, voice)
                    _state.value = _state.value.copy(
                        engineId = id,
                        engineLabel = engine.label,
                        status = NeuralTtsStatus.LOADED,
                        voices = info.voices,
                        selectedVoice = voice,
                        sampleRate = info.sampleRate,
                        vocabularySource = info.vocabularySource,
                        detail = info.detail,
                    )
                    Log.i(TAG, "neural_tts_loaded engine=$id voices=${info.voices.size}")
                    engine
                }
                is TtsLoadResult.Failed -> {
                    _state.value = _state.value.copy(
                        engineId = id,
                        status = NeuralTtsStatus.ERROR,
                        detail = result.reason,
                    )
                    Log.w(TAG, "neural_tts_load_failed engine=$id reason=${result.reason}")
                    null
                }
            }
        }
    }

    // --- configuration -----------------------------------------------------

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

    /** Points a slot of the CURRENT engine at an already-imported file. */
    suspend fun selectAsset(path: String, kind: TtsAssetKind) {
        val id = settings.ttsEngineId.first()
        if (id.isBlank()) return
        setPath(id, kind, path)
        unload()
        refresh()
    }

    /** Forgets a slot; with [deleteFile] the copy in app storage goes too. */
    suspend fun clearAsset(kind: TtsAssetKind, deleteFile: Boolean) {
        val id = settings.ttsEngineId.first()
        if (id.isBlank()) return
        val path = pathFor(id, kind)
        if (deleteFile && path.isNotBlank()) assets.delete(path)
        selectAsset("", kind)
    }

    suspend fun setVoice(voice: String) {
        val id = settings.ttsEngineId.first()
        settings.setTtsNeuralVoice(id, voice)
        _state.value = _state.value.copy(selectedVoice = voice)
    }

    fun importedAssets(): List<TtsAsset> = assets.list()

    /**
     * The persisted voice for the engine in use, read fresh rather than from a
     * cached snapshot: a process that has just started has no snapshot, and
     * would otherwise speak with whatever the pack happens to list first.
     */
    suspend fun currentVoice(): String {
        val id = settings.ttsEngineId.first()
        if (id.isBlank()) return ""
        return settings.ttsNeuralVoice(id).first().ifBlank { _state.value.selectedVoice }
    }

    // --- trying it out -----------------------------------------------------

    /**
     * Synthesises a fixed Italian sentence with [voice] through the real graph
     * and plays it. This is the only honest way to answer "does the voice work?"
     * — it exercises initialisation, phonemisation, inference and playback in
     * the same order a reply does, and reports which step gave way.
     *
     * Entirely offline: nothing on this path opens a socket.
     */
    suspend fun preview(voice: String = ""): PreviewOutcome {
        val engine = ensureLoaded() ?: return PreviewOutcome.NotLoaded(_state.value.detail)
        val id = engine.id
        val chosen = voice.ifBlank { settings.ttsNeuralVoice(id).first() }
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

    /** Drops every session. The files and the choice stay. */
    fun unload() {
        engines().forEach { it.release() }
        _state.value = _state.value.copy(
            status = when {
                _state.value.engineId.isBlank() -> NeuralTtsStatus.OFF
                _state.value.status == NeuralTtsStatus.INCOMPLETE -> NeuralTtsStatus.INCOMPLETE
                else -> NeuralTtsStatus.FILES_READY
            },
            sampleRate = 0,
        )
    }

    private companion object {
        const val TAG = "JarvisNeuralTts"

        /** Exercises the shaping rules and a few awkward Italian sounds. */
        const val SAMPLE =
            "Buonasera Simone. Sono JARVIS: alle 15:30 hai la revisione dell'auto, " +
                "e la batteria è al 68 per cento."

        /**
         * Passed to [NeuralTtsEngine.load] for an engine whose [NeuralTtsEngine.requiredAssets]
         * does not include [TtsAssetKind.MODEL] — a self-contained, bundled engine
         * (Supertonic) resolves its real files itself and never reads this value;
         * it exists only so `load()`'s non-nullable `model: File` parameter has
         * something to bind to.
         */
        val SELF_CONTAINED_PLACEHOLDER = File("")
    }
}
