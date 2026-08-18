package com.simone.jarvismobile.tts

import com.simone.jarvismobile.core.tts.SupertonicQuality

/**
 * Every tunable Supertonic parameter in one place, so nothing is hardcoded
 * inline in [SupertonicTtsEngine]. Defaults match the spec's "Impostazioni
 * iniziali" exactly; [numSteps] is the only field the quality preset
 * ([SupertonicQuality]) overrides per call.
 */
data class SupertonicConfig(
    val language: String = "it",
    val provider: String = "cpu",
    val numThreads: Int = 4,
    val speed: Float = 1.0f,
    val quality: SupertonicQuality = SupertonicQuality.BALANCED,
    /** Debug logging inside the native sherpa-onnx session. Always off in release. */
    val debug: Boolean = false,
) {
    val numSteps: Int get() = quality.numSteps

    companion object {
        /** The one place a build decides "debug is only ever on in a debug build". */
        fun default(isDebugBuild: Boolean): SupertonicConfig = SupertonicConfig(debug = isDebugBuild)
    }
}

/** The seven files a Supertonic bundle ships, by their fixed, non-renameable name. */
object SupertonicAssetFiles {
    const val DURATION_PREDICTOR = "duration_predictor.int8.onnx"
    const val TEXT_ENCODER = "text_encoder.int8.onnx"
    const val VECTOR_ESTIMATOR = "vector_estimator.int8.onnx"
    const val VOCODER = "vocoder.int8.onnx"
    const val TTS_JSON = "tts.json"
    const val UNICODE_INDEXER = "unicode_indexer.bin"
    const val VOICE = "voice.bin"

    val ALL = listOf(
        DURATION_PREDICTOR, TEXT_ENCODER, VECTOR_ESTIMATOR, VOCODER,
        TTS_JSON, UNICODE_INDEXER, VOICE,
    )

    /** Where the bundle ships in the APK (spec: `app/src/main/assets/models/supertonic3/`). */
    const val ASSET_DIR = "models/supertonic3"
}
