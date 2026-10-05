package com.simone.jarvismobile.core.voice

enum class VadTurnEvent { SPEECH_STARTED, SPEECH_ENDED }

/**
 * Explicit hysteresis configuration, in FRAMES (no timers). Production values are
 * UNQUALIFIED: [PLACEHOLDER_UNQUALIFIED] exists only as a test/placeholder default and
 * must not be tuned by guessing — qualification belongs to the Silero runtime phase.
 */
data class VadTurnConfig(val minSpeechFrames: Int, val minSilenceFrames: Int) {
    init {
        require(minSpeechFrames in 1..MAX_FRAMES) { "minSpeechFrames out of bounds" }
        require(minSilenceFrames in 1..MAX_FRAMES) { "minSilenceFrames out of bounds" }
    }

    companion object {
        const val MAX_FRAMES = 10_000
        val PLACEHOLDER_UNQUALIFIED = VadTurnConfig(minSpeechFrames = 3, minSilenceFrames = 8)
    }
}

/**
 * Pure deterministic speech-boundary policy: a sequence of bounded [VadObservation]s in,
 * stable logical events out. Not Silero inference; not connected to any runtime path.
 *
 * Rules:
 *  - NON_SPEECH state: [VadTurnConfig.minSpeechFrames] consecutive SPEECH => one SPEECH_STARTED.
 *  - SPEECH state: [VadTurnConfig.minSilenceFrames] consecutive NON_SPEECH => one SPEECH_ENDED.
 *  - UNKNOWN is no evidence: it resets the pending run (never ends/starts speech, never counts).
 *  - Sequence: seq <= last seen is a stale/duplicate frame and is ignored; a forward gap
 *    (seq > last + 1) resets pending evidence runs, keeps the logical state, and the frame is
 *    then processed normally.
 *  - Counters reset every time they cross a threshold or lose evidence, so they are bounded
 *    by the (bounded) config. Not thread-safe: one owner feeds it sequentially.
 */
class VadTurnPolicy(private val config: VadTurnConfig) {
    private var inSpeech = false
    private var speechRun = 0
    private var silenceRun = 0
    private var lastSequence: Long? = null

    val isSpeechActive: Boolean get() = inSpeech

    fun reset() {
        inSpeech = false
        speechRun = 0
        silenceRun = 0
        lastSequence = null
    }

    fun observe(sequence: Long, observation: VadObservation): VadTurnEvent? {
        val last = lastSequence
        if (last != null) {
            if (sequence <= last) return null
            if (sequence > last + 1) {
                speechRun = 0
                silenceRun = 0
            }
        }
        lastSequence = sequence

        return when (observation) {
            VadObservation.UNKNOWN -> {
                speechRun = 0
                silenceRun = 0
                null
            }
            VadObservation.SPEECH -> {
                silenceRun = 0
                if (inSpeech) null else {
                    speechRun++
                    if (speechRun >= config.minSpeechFrames) {
                        inSpeech = true
                        speechRun = 0
                        VadTurnEvent.SPEECH_STARTED
                    } else null
                }
            }
            VadObservation.NON_SPEECH -> {
                speechRun = 0
                if (!inSpeech) null else {
                    silenceRun++
                    if (silenceRun >= config.minSilenceFrames) {
                        inSpeech = false
                        silenceRun = 0
                        VadTurnEvent.SPEECH_ENDED
                    } else null
                }
            }
        }
    }
}
