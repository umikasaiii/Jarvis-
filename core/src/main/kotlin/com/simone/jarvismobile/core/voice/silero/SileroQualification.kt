package com.simone.jarvismobile.core.voice.silero

import com.simone.jarvismobile.core.voice.VadObservation
import com.simone.jarvismobile.core.voice.VadTurnConfig
import com.simone.jarvismobile.core.voice.VadTurnEvent
import com.simone.jarvismobile.core.voice.VadTurnPolicy
import com.simone.jarvismobile.core.voice.VoicePcmFrame

/** Bounded outcome of one manual Silero qualification run. No per-frame list, no PCM, no transcript. */
data class SileroQualificationResult(
    val modelId: String,
    val modelReady: Boolean,
    val framesProcessed: Long,
    val framesUnknown: Long,
    val framesSpeech: Long,
    val framesNonSpeech: Long,
    val speechStartedCount: Int,
    val speechEndedCount: Int,
    val maxProbability: Float?,
    val meanProbability: Float?,
    val averageInferenceMs: Double?,
    val maxInferenceMs: Double?,
    val captureFramesDropped: Long,
    val captureFailure: String?,
    val vadFailure: SileroFailure?,
    val thresholdStatus: String = SileroVadConfig.THRESHOLD_STATUS,
)

/**
 * One diagnostics-only qualification session: resets the engine, then feeds every frame through
 * engine -> VadTurnPolicy and keeps bounded counters. Not production voice: nothing here reacts to the events.
 */
class SileroQualificationSession(
    private val engine: SileroVadEngine,
    turnConfig: VadTurnConfig = VadTurnConfig.PLACEHOLDER_UNQUALIFIED,
) {
    private val policy = VadTurnPolicy(turnConfig)
    private var speech = 0L
    private var nonSpeech = 0L
    private var started = 0
    private var ended = 0

    init {
        engine.reset()
        policy.reset()
    }

    suspend fun onFrame(frame: VoicePcmFrame) {
        val obs = engine.process(frame)
        when (obs) {
            VadObservation.SPEECH -> speech++
            VadObservation.NON_SPEECH -> nonSpeech++
            VadObservation.UNKNOWN -> Unit
        }
        when (policy.observe(frame.sequence, obs)) {
            VadTurnEvent.SPEECH_STARTED -> started++
            VadTurnEvent.SPEECH_ENDED -> ended++
            null -> Unit
        }
    }

    fun result(captureFramesDropped: Long, captureFailure: String?, modelId: String = SileroModelManifest.CANONICAL.modelId): SileroQualificationResult {
        val s = engine.stats()
        return SileroQualificationResult(
            modelId = modelId, modelReady = true,
            framesProcessed = s.framesOffered, framesUnknown = s.framesUnknown,
            framesSpeech = speech, framesNonSpeech = nonSpeech,
            speechStartedCount = started, speechEndedCount = ended,
            maxProbability = s.maxProbability, meanProbability = s.meanProbability,
            averageInferenceMs = s.averageInferenceMs, maxInferenceMs = if (s.framesInferred > 0) s.maxInferenceMs else null,
            captureFramesDropped = captureFramesDropped, captureFailure = captureFailure, vadFailure = s.lastFailure,
        )
    }
}

/** Why a VAD qualification run may not start. The microphone must provably be free. */
enum class SileroQualificationAvailability {
    AVAILABLE, MODEL_NOT_READY, CONVERSATION_ACTIVE, WAKE_WORD_ENABLED, WAKE_WORD_LISTENING, TRANSLATOR_ACTIVE, CAPTURE_ACTIVE,
}

/** Pure mic-ownership gate: AudioRecord and SpeechRecognizer must never run at once. */
object SileroQualificationGate {
    fun check(
        modelReady: Boolean,
        conversationIdle: Boolean,
        wakeWordEnabled: Boolean,
        wakeWordListening: Boolean,
        translatorActive: Boolean,
        captureActive: Boolean,
    ): SileroQualificationAvailability = when {
        !modelReady -> SileroQualificationAvailability.MODEL_NOT_READY
        !conversationIdle -> SileroQualificationAvailability.CONVERSATION_ACTIVE
        wakeWordListening -> SileroQualificationAvailability.WAKE_WORD_LISTENING
        wakeWordEnabled -> SileroQualificationAvailability.WAKE_WORD_ENABLED
        translatorActive -> SileroQualificationAvailability.TRANSLATOR_ACTIVE
        captureActive -> SileroQualificationAvailability.CAPTURE_ACTIVE
        else -> SileroQualificationAvailability.AVAILABLE
    }
}
