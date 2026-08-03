package com.simone.jarvismobile.audio

import kotlinx.coroutines.flow.StateFlow

/** Human-facing classification of an audio endpoint. */
enum class AudioDeviceKind { AIRPODS, BLUETOOTH_HEADSET, WIRED_HEADSET, PHONE, SPEAKER, UNKNOWN }

/** A resolved input/output endpoint with just enough detail for diagnostics. */
data class AudioEndpoint(
    val id: Int,
    val productName: String,
    val kind: AudioDeviceKind,
    val isSource: Boolean,
)

/**
 * Live snapshot of the audio route, surfaced on the diagnostics screen
 * (docs/ARCHITECTURE.md §8). Every field reflects what Android is ACTUALLY
 * doing — we never claim the AirPods mic is in use when the phone mic is.
 */
data class AudioRouteState(
    val input: AudioEndpoint? = null,
    val output: AudioEndpoint? = null,
    val requestedCommunicationDevice: AudioEndpoint? = null,
    val communicationDeviceApplied: Boolean = false,
    val sampleRate: Int = 0,
    val channelCount: Int = 0,
    val hasAudioFocus: Boolean = false,
    val bluetoothConnected: Boolean = false,
    /** A Bluetooth endpoint that looks like AirPods is present. */
    val airPodsDetected: Boolean = false,
    /** True only when input is actually routed through a Bluetooth device. */
    val usingBluetoothInput: Boolean = false,
    /** Last technical audio error code (redacted; no personal data). */
    val lastError: String? = null,
)

/**
 * Routes capture/playback toward the best available device (AirPods when truly
 * present, otherwise the phone) and reports the real state.
 *
 * Implementations must:
 *  - enumerate input/output devices and classify them;
 *  - attempt setCommunicationDevice() on compatible endpoints;
 *  - request/abandon audio focus correctly;
 *  - react to connect/disconnect while a session is active;
 *  - restore the previous route when the session ends;
 *  - fall back to the phone without crashing.
 */
interface AudioRouteManager {

    val routeState: StateFlow<AudioRouteState>

    /**
     * Prepares communication-mode audio for a session, preferring [preferBluetooth]
     * endpoints (AirPods). Returns the endpoint actually selected as input.
     */
    suspend fun beginSession(preferBluetooth: Boolean = true): AudioEndpoint

    /** Releases the communication device, audio focus and restores prior routing. */
    fun endSession()

    /** Current available endpoints (for the diagnostics screen). */
    fun availableEndpoints(): List<AudioEndpoint>
}
