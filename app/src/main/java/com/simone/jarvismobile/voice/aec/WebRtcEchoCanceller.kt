package com.simone.jarvismobile.voice.aec

import com.simone.jarvismobile.core.voice.EchoBlockResult
import com.simone.jarvismobile.core.voice.EchoCanceller
import com.simone.jarvismobile.core.voice.EchoCancellerFailure
import com.simone.jarvismobile.core.voice.EchoCancellerState
import com.simone.jarvismobile.core.voice.EchoRatePolicy
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

sealed interface EchoCancellerCreation {
    class Ready(val canceller: EchoCanceller) : EchoCancellerCreation
    class Unavailable(val failure: EchoCancellerFailure) : EchoCancellerCreation
}

/** Real AEC3 engine behind the platform-free [EchoCanceller] contract. Render and capture may run on different threads. */
class WebRtcEchoCanceller private constructor(
    private var handle: Long,
    override val nearBlockSamples: Int,
    override val farBlockSamples: Int,
) : EchoCanceller {
    private val lock = ReentrantReadWriteLock()
    @Volatile override var state: EchoCancellerState = EchoCancellerState.READY
        private set
    private val scratchOut = ThreadLocal.withInitial { ShortArray(nearBlockSamples) }

    override fun feedFarEnd(block: FloatArray): EchoBlockResult = lock.read {
        if (state != EchoCancellerState.READY) return EchoBlockResult.Failed(EchoCancellerFailure.CLOSED)
        if (block.size != farBlockSamples) return EchoBlockResult.Failed(EchoCancellerFailure.INVALID_BLOCK)
        val rc = NativeAec3.nativeProcessReverse(handle, block)
        if (rc == 0) EchoBlockResult.Accepted else EchoBlockResult.Failed(EchoCancellerFailure.NATIVE_PROCESS_FAILED)
    }

    override fun processNearEnd(block: ShortArray): EchoBlockResult = lock.read {
        if (state != EchoCancellerState.READY) return EchoBlockResult.Failed(EchoCancellerFailure.CLOSED)
        if (block.size != nearBlockSamples) return EchoBlockResult.Failed(EchoCancellerFailure.INVALID_BLOCK)
        val out = scratchOut.get()!!
        val rc = NativeAec3.nativeProcessNear(handle, block, out)
        if (rc == 0) EchoBlockResult.Ok(out.copyOf()) else EchoBlockResult.Failed(EchoCancellerFailure.NATIVE_PROCESS_FAILED)
    }

    override fun close() = lock.write {
        if (state == EchoCancellerState.CLOSED) return@write
        state = EchoCancellerState.CLOSED
        if (handle != 0L) NativeAec3.nativeDestroy(handle)
        handle = 0L
    }

    companion object {
        /** Creates one engine for a 16 kHz near-end and [farRateHz] far-end. Unsupported rates are refused, never approximated. */
        fun create(farRateHz: Int): EchoCancellerCreation {
            if (!EchoRatePolicy.farRateSupported(farRateHz)) return EchoCancellerCreation.Unavailable(EchoCancellerFailure.UNSUPPORTED_FAR_RATE)
            if (!NativeAec3.isLoaded) return EchoCancellerCreation.Unavailable(EchoCancellerFailure.NATIVE_LIBRARY_MISSING)
            val handle = try {
                NativeAec3.nativeCreate(EchoRatePolicy.NEAR_RATE_HZ, farRateHz)
            } catch (_: UnsatisfiedLinkError) {
                return EchoCancellerCreation.Unavailable(EchoCancellerFailure.NATIVE_LIBRARY_MISSING)
            }
            if (handle == 0L) return EchoCancellerCreation.Unavailable(EchoCancellerFailure.INIT_FAILED)
            return EchoCancellerCreation.Ready(
                WebRtcEchoCanceller(
                    handle,
                    EchoRatePolicy.blockSamples(EchoRatePolicy.NEAR_RATE_HZ)!!,
                    EchoRatePolicy.blockSamples(farRateHz)!!,
                ),
            )
        }
    }
}
