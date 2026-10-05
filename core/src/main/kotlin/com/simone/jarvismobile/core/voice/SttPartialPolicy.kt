package com.simone.jarvismobile.core.voice

/**
 * Live Voice Phase 0.6 — the pure decision made for each
 * `RecognitionListener.onPartialResults()` callback, extracted from
 * `AndroidOnDeviceSpeechEngine` so it is exercised by real JVM tests.
 *
 * [isCurrentGeneration] is the existing `attemptGeneration` fence
 * (`myGeneration == attemptGeneration.get()`), the only identity mechanism:
 * no time window, no second counter. A callback from an abandoned retry
 * attempt (or a superseded invocation) is never current, so it can neither
 * overwrite the user-facing partial text nor become causal evidence.
 *
 * The decision never carries text into diagnostics: [updateUiWith] is for
 * the pre-existing partial StateFlow only; [emitObserved] is the bare fact
 * that a non-blank partial was seen, at most once per attempt
 * ([alreadyObservedThisAttempt]).
 */
object SttPartialPolicy {
    data class Decision(
        /** Non-null: set the existing partial StateFlow to this value. */
        val updateUiWith: String?,
        /** True: emit exactly one PARTIAL observation for this attempt. */
        val emitObserved: Boolean,
    )

    private val REJECT = Decision(updateUiWith = null, emitObserved = false)

    fun decide(
        isCurrentGeneration: Boolean,
        bestPartial: String?,
        alreadyObservedThisAttempt: Boolean,
    ): Decision {
        if (!isCurrentGeneration || bestPartial == null) return REJECT
        return Decision(
            updateUiWith = bestPartial,
            emitObserved = !alreadyObservedThisAttempt && bestPartial.isNotBlank(),
        )
    }
}
