package com.simone.jarvismobile.audio

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.tryEmit

/** Signals emitted from the foreground service toward the active session UI. */
enum class SessionSignal { INTERRUPT }

/**
 * Tiny process-wide event bus bridging [ListeningService] notification actions
 * to the running session (a full DI-scoped session coordinator replaces this in
 * phase 4). Kept intentionally minimal and side-effect free.
 */
object SessionBus {
    private val _signals = MutableSharedFlow<SessionSignal>(extraBufferCapacity = 4)
    val signals: SharedFlow<SessionSignal> = _signals

    fun emitInterrupt() {
        _signals.tryEmit(SessionSignal.INTERRUPT)
    }
}
