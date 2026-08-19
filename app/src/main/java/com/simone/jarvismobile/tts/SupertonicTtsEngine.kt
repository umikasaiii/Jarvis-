package com.simone.jarvismobile.tts

import com.simone.jarvismobile.core.tts.SupertonicQuality
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supertonic 3, TEMPORARILY DISABLED at build level (user request, 2026-08-19):
 * the branch could not compile without `app/libs/sherpa-onnx-1.13.5.aar`, which
 * requires two binary artifacts this environment has no network access to fetch
 * (see `app/libs/README.md`), and every other feature on the branch was stuck
 * behind that. Rather than lose the accumulated work, the real sherpa-onnx
 * implementation was replaced with this stub so the app compiles again; the
 * real implementation (native `OfflineTts`/`GenerationConfig` calls) is
 * preserved verbatim in git history (see the commit that introduced this file,
 * and the one that stubbed it) so it can be restored once both binaries are
 * added — restoring is a straight revert of the stubbing commit, not a rewrite.
 *
 * [load] always fails cleanly, so [com.simone.jarvismobile.audio.HybridTtsEngine]'s
 * existing fallback to the Android TTS engine (already in place, unrelated to
 * this stub) takes over exactly as it already does for any other load failure —
 * nothing else on the TTS path needed to change. `SettingsRepository.ttsEngineId`
 * is deliberately left defaulting to Supertonic: the moment the real binaries
 * are added and this stub is reverted, Supertonic starts working again with no
 * settings change needed.
 */
@Singleton
class SupertonicTtsEngine @Inject constructor(
    // Unused while disabled — kept so the constructor shape (and every DI call
    // site that depends on it) doesn't need to change for this stub, and so
    // restoring the real implementation is a plain revert.
    @Suppress("unused") private val provisioner: SupertonicAssetProvisioner,
) : NeuralTtsEngine {

    override val id = NeuralTtsEngines.SUPERTONIC
    override val label = "Supertonic 3 (locale) — temporaneamente disattivato"

    override val requiredAssets: Set<TtsAssetKind> = emptySet()

    override val sampleRate: Int = DEFAULT_SAMPLE_RATE

    override val isLoaded: Boolean = false

    @Volatile private var config: SupertonicConfig = SupertonicConfig.default(false)

    /** Diagnostics-only: kept so the debug A/B panel still compiles; has nothing to affect while disabled. */
    fun setQualityProfile(quality: SupertonicQuality) {
        config = config.copy(quality = quality)
    }

    fun currentProfile(): SupertonicQuality = config.quality

    override suspend fun load(model: File, voices: File?, vocabulary: File?): TtsLoadResult =
        TtsLoadResult.Failed(DISABLED_REASON)

    override fun voices(): List<String> = emptyList()

    override suspend fun peekVoices(model: File?, voices: File?, vocabulary: File?): List<String> = emptyList()

    override fun isReadyToLoad(): Boolean = false

    override suspend fun synthesize(text: String, voice: String, speed: Float): FloatArray? = null

    override fun cancelSynthesis() {}

    override fun release() {}

    private companion object {
        const val DEFAULT_SAMPLE_RATE = 24_000
        const val DISABLED_REASON =
            "Supertonic è temporaneamente disattivato in questa build: mancano i binari " +
                "sherpa-onnx (vedi app/libs/README.md). JARVIS userà la voce Android."
    }
}
