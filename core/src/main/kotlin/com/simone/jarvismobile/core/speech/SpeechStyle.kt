package com.simone.jarvismobile.core.speech

/**
 * How JARVIS sounds. A voice is not just a speed and a pitch: what makes speech
 * sound human is mostly *rhythm* — where it breathes, how long it holds a full
 * stop, whether it rushes a list. Those live here as multipliers so a preset
 * changes the whole delivery coherently instead of one slider at a time.
 */
data class SpeechStyle(
    /** Speaking rate passed to the engine. 1.0 is the voice's natural speed. */
    val rate: Float = 1.0f,
    /** Pitch passed to the engine. 1.0 is natural. */
    val pitch: Float = 1.0f,
    /** Scales every pause. >1 sounds calm and deliberate, <1 brisk. */
    val pauseScale: Float = 1.0f,
    /**
     * How much punctuation is dramatised. 0 = flat, machine-like delivery with
     * only the pauses the engine gives for free; 1 = fully expressive, with
     * breathing room around clauses and a real beat between sentences.
     */
    val expressiveness: Float = 0.6f,
) {
    fun coerced(): SpeechStyle = copy(
        rate = rate.coerceIn(MIN_RATE, MAX_RATE),
        pitch = pitch.coerceIn(MIN_PITCH, MAX_PITCH),
        pauseScale = pauseScale.coerceIn(0f, 3f),
        expressiveness = expressiveness.coerceIn(0f, 1f),
    )

    companion object {
        // Bounds keep a mis-set slider from producing chipmunk or drunk speech.
        const val MIN_RATE = 0.6f
        const val MAX_RATE = 1.8f
        const val MIN_PITCH = 0.6f
        const val MAX_PITCH = 1.6f

        /** Slow, low, long pauses: for reading at night or dictating notes. */
        val CALMO = SpeechStyle(rate = 0.92f, pitch = 0.95f, pauseScale = 1.45f, expressiveness = 0.8f)

        /** The default: unhurried but not sleepy. */
        val NATURALE = SpeechStyle(rate = 1.0f, pitch = 1.0f, pauseScale = 1.0f, expressiveness = 0.6f)

        /** Brighter and quicker, with punchy beats. */
        val ESPRESSIVO = SpeechStyle(rate = 1.08f, pitch = 1.08f, pauseScale = 0.85f, expressiveness = 1.0f)

        /** Fast and flat, for when you just want the answer. */
        val RAPIDO = SpeechStyle(rate = 1.25f, pitch = 1.0f, pauseScale = 0.6f, expressiveness = 0.25f)

        val PRESETS: Map<String, SpeechStyle> = linkedMapOf(
            "Calmo" to CALMO,
            "Naturale" to NATURALE,
            "Espressivo" to ESPRESSIVO,
            "Rapido" to RAPIDO,
        )
    }
}

/** One utterance plus the silence that follows it, in milliseconds. */
data class SpeechSegment(val text: String, val pauseMs: Long)
