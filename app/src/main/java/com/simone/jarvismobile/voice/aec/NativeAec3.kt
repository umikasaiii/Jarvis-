package com.simone.jarvismobile.voice.aec

/**
 * LV-R1 — the ONLY JNI surface of the standalone WebRTC AudioProcessing (AEC3) built from the pinned
 * pulseaudio/webrtc-audio-processing v2.1 source (see native/aec3/PROVENANCE.json). Four entry points,
 * nothing else: create / processReverse (far-end) / processNear (near-end) / destroy.
 * Blocks are EXACT 10 ms. No audio is stored, logged or sent anywhere.
 */
object NativeAec3 {
    /** True only if the native library was packaged and loaded. Never throws. */
    val isLoaded: Boolean by lazy {
        try {
            System.loadLibrary("jarvis_aec3")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    /** Opaque handle or 0 on failure. */
    @JvmStatic external fun nativeCreate(nearRateHz: Int, farRateHz: Int): Long

    /** 0 = ok; negative = bad arguments; positive = WebRTC error code. */
    @JvmStatic external fun nativeProcessReverse(handle: Long, samples: FloatArray): Int

    @JvmStatic external fun nativeProcessNear(handle: Long, input: ShortArray, output: ShortArray): Int

    @JvmStatic external fun nativeDestroy(handle: Long)
}
