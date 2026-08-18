package com.simone.jarvismobile.core.tts

/**
 * Supertonic's diffusion/flow-matching vocoder trades inference steps for
 * quality: more steps sound better but take longer, so this is exposed as a
 * small closed set of presets rather than a raw slider — the same reasoning
 * as the LLM ECO/BALANCED/QUALITY profiles in `docs/MODELS.md`.
 *
 * Pure so the step counts are pinned by a JVM test rather than only checked by
 * ear on a phone.
 */
enum class SupertonicQuality(val numSteps: Int) {
    FAST(6),
    BALANCED(8),
    QUALITY(12),
}
