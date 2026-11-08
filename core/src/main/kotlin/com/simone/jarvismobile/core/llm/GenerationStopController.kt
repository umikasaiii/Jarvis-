package com.simone.jarvismobile.core.llm

import java.util.concurrent.atomic.AtomicReference

enum class StopReason { USER, TIMEOUT }

class GenerationCancelled : RuntimeException("generation_cancelled")
class GenerationTimedOut : RuntimeException("generation_timeout")

/**
 * BETA 2/8 — the single-writer stop decision of one in-flight generation, extracted from the engine so it
 * is testable: the FIRST reason wins, the native cancel runs at most once, and a later timeout can never
 * turn a user cancellation into a "timeout" error (or the opposite).
 */
class GenerationStopController(private val cancelNative: () -> Unit) {
    private val reason = AtomicReference<StopReason?>(null)

    /** @return true when this call is the one that stopped the generation. */
    fun request(stopReason: StopReason): Boolean {
        if (!reason.compareAndSet(null, stopReason)) return false
        runCatching { cancelNative() }
        return true
    }

    val stopReason: StopReason? get() = reason.get()

    fun throwIfStopped() {
        when (reason.get()) {
            StopReason.USER -> throw GenerationCancelled()
            StopReason.TIMEOUT -> throw GenerationTimedOut()
            null -> Unit
        }
    }
}
