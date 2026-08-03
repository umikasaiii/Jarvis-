package com.simone.jarvismobile.core.session

import com.simone.jarvismobile.core.state.ConversationEvent
import com.simone.jarvismobile.core.state.ConversationState
import com.simone.jarvismobile.core.state.ConversationStateMachine

/** Which audio endpoint the session ended up using. */
enum class AudioKind { AIRPODS, BLUETOOTH, PHONE }

/** Result of preparing the audio route for a session. */
sealed interface PrepareOutcome {
    /** Ready to capture; [fellBackToPhone] is true if a BT device was preferred but the phone was used. */
    data class Ready(val kind: AudioKind, val fellBackToPhone: Boolean) : PrepareOutcome
    data object PermissionDenied : PrepareOutcome
    data object BluetoothUnavailable : PrepareOutcome
    data class Failure(val code: String) : PrepareOutcome
}

/** Result of the fixed Phase-1 recording window. */
sealed interface RecordOutcome {
    /** Normal end of the fixed window. */
    data object Completed : RecordOutcome
    /** Window elapsed via timeout — in Phase-1 this is also a normal end. */
    data object Timeout : RecordOutcome
    data object Cancelled : RecordOutcome
    data object FocusLost : RecordOutcome
    data object BluetoothLost : RecordOutcome
    data class Failure(val code: String) : RecordOutcome
}

/** Result of speaking the fixed Phase-1 reply. */
sealed interface SpeakOutcome {
    data object Done : SpeakOutcome
    data object Cancelled : SpeakOutcome
    data class Failure(val code: String) : SpeakOutcome
}

/**
 * The side-effecting operations a Phase-1 session performs, abstracted so the
 * orchestration ([Fase1Flow]) is pure Kotlin and unit-testable with fakes.
 * Android provides the real implementation (AudioRecord + TextToSpeech).
 */
interface Fase1Environment {
    suspend fun prepareAudio(): PrepareOutcome
    suspend fun record(): RecordOutcome
    suspend fun ensureTtsReady(): Boolean
    suspend fun speak(): SpeakOutcome
    suspend fun endSession()
}

/**
 * Drives one Phase-1 session against the real [ConversationStateMachine]:
 *
 *   Idle → PreparingAudio → Listening → FinalizingSpeech → Speaking → FollowUpWindow → Idle
 *
 * There is no STT/retrieval/LLM yet: after recording we take the documented
 * [ConversationEvent.SkipToSpeaking] shortcut and speak a fixed reply. Every
 * failure path leaves the machine in a usable state and always calls
 * [Fase1Environment.endSession].
 */
class Fase1Flow(
    private val machine: ConversationStateMachine,
    private val env: Fase1Environment,
) {
    /** Runs the session and returns the final state. Never throws for expected outcomes. */
    suspend fun run(): ConversationState {
        machine.dispatch(ConversationEvent.StartRequested) // -> PreparingAudio

        when (val prepare = env.prepareAudio()) {
            is PrepareOutcome.Ready -> machine.dispatch(ConversationEvent.AudioReady) // -> Listening
            PrepareOutcome.PermissionDenied -> return finish(ConversationEvent.PermissionDenied)
            PrepareOutcome.BluetoothUnavailable -> return finish(ConversationEvent.BluetoothLost)
            is PrepareOutcome.Failure -> return finish(ConversationEvent.RecoverableFailure(prepare.code))
        }

        when (env.record()) {
            RecordOutcome.Completed, RecordOutcome.Timeout ->
                machine.dispatch(ConversationEvent.SpeechEnded) // -> FinalizingSpeech
            RecordOutcome.Cancelled -> return finish(ConversationEvent.CancelRequested)
            RecordOutcome.FocusLost -> return finish(ConversationEvent.RecoverableFailure("audio_focus_lost"))
            RecordOutcome.BluetoothLost -> return finish(ConversationEvent.BluetoothLost)
            is RecordOutcome.Failure -> return finish(ConversationEvent.RecoverableFailure("record_failed"))
        }

        machine.dispatch(ConversationEvent.SkipToSpeaking) // -> Speaking

        if (!env.ensureTtsReady()) {
            return finish(ConversationEvent.RecoverableFailure("tts_unavailable"))
        }

        when (env.speak()) {
            SpeakOutcome.Done -> machine.dispatch(ConversationEvent.SpeechSynthesisFinished) // -> FollowUpWindow
            SpeakOutcome.Cancelled -> return finish(ConversationEvent.CancelRequested)
            is SpeakOutcome.Failure -> return finish(ConversationEvent.RecoverableFailure("tts_error"))
        }

        machine.dispatch(ConversationEvent.FollowUpTimeout) // -> Idle
        env.endSession()
        return machine.current
    }

    private suspend fun finish(event: ConversationEvent): ConversationState {
        machine.dispatch(event)
        env.endSession()
        return machine.current
    }
}
