package com.simone.jarvismobile.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real [AudioRouteManager] backed by [AudioManager].
 *
 * The strategy (docs/ARCHITECTURE.md §8):
 *  1. Switch to MODE_IN_COMMUNICATION so Bluetooth SCO / the modern
 *     communication-device API is used for voice capture.
 *  2. Prefer a Bluetooth endpoint that looks like AirPods; otherwise use the
 *     best available; otherwise the built-in phone mic/earpiece.
 *  3. Request transient audio focus and report exactly which endpoint is live.
 *
 * NOTE: this class was written against the public AudioManager API but has NOT
 * been compiled in this container (no Android SDK — see CLAUDE.md "Environment").
 * It is exercised on-device per docs/DEVICE_TEST_HONOR_200.md.
 */
@Singleton
class AndroidAudioRouteManager @Inject constructor(
    @ApplicationContext private val context: Context,
) : AudioRouteManager {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _routeState = MutableStateFlow(AudioRouteState())
    override val routeState = _routeState.asStateFlow()

    private var previousMode: Int = AudioManager.MODE_NORMAL
    private var focusRequest: AudioFocusRequest? = null

    override suspend fun beginSession(preferBluetooth: Boolean): AudioEndpoint {
        previousMode = audioManager.mode

        // Phone-only path: do the ABSOLUTE MINIMUM — no audio-focus request, no
        // audio-mode change, no Bluetooth/communication-device APIs. This makes
        // capture behave exactly like a bare AudioRecord (the working Diagnostics
        // "Test microfono"); requesting communication focus/mode was blocking the
        // built-in mic on MagicOS.
        if (!preferBluetooth) {
            val phone = AudioEndpoint(id = -1, productName = "", kind = AudioDeviceKind.PHONE, isSource = true)
            val phoneOut = AudioEndpoint(id = -1, productName = "", kind = AudioDeviceKind.PHONE, isSource = false)
            _routeState.update {
                AudioRouteState(
                    input = phone,
                    output = phoneOut,
                    requestedCommunicationDevice = phone,
                    communicationDeviceApplied = false,
                    sampleRate = preferredSampleRate(),
                    channelCount = 1,
                    hasAudioFocus = false,
                    bluetoothConnected = false,
                    airPodsDetected = false,
                    usingBluetoothInput = false,
                )
            }
            Log.i(TAG, "begin_session phone_only minimal")
            return phone
        }

        // Bluetooth path: request focus + switch to communication mode so BT SCO /
        // the modern communication-device API routes voice capture to the headset.
        val focusGranted = requestFocus()
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        val input = selectInputEndpoint(preferBluetooth)
        val applied = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            applyCommunicationDevice(input)
        } else {
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = input.kind.isBluetooth()
            input.kind.isBluetooth()
        }

        val output = currentOutputEndpoint()
        val endpoints = availableEndpoints()
        _routeState.update {
            it.copy(
                input = input,
                output = output,
                requestedCommunicationDevice = input,
                communicationDeviceApplied = applied,
                sampleRate = preferredSampleRate(),
                channelCount = 1,
                hasAudioFocus = focusGranted,
                bluetoothConnected = endpoints.any { e -> e.kind.isBluetooth() },
                airPodsDetected = endpoints.any { e -> e.kind == AudioDeviceKind.AIRPODS },
                usingBluetoothInput = applied && input.kind.isBluetooth(),
                lastError = if (!focusGranted) "audio_focus_denied" else null,
            )
        }
        // Redacted technical log only: device kind, temporary id, selection result.
        // No product name is logged (may reveal a person's device name).
        Log.i(
            TAG,
            "begin_session focus=$focusGranted input_kind=${input.kind} " +
                "input_id=${input.id} comm_applied=$applied output_kind=${output?.kind}",
        )
        return input
    }

    override fun endSession() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = false
        }
        abandonFocus()
        audioManager.mode = previousMode
        _routeState.update {
            AudioRouteState(
                bluetoothConnected = it.bluetoothConnected,
                airPodsDetected = it.airPodsDetected,
            )
        }
        Log.i(TAG, "end_session route_restored")
    }

    override fun availableEndpoints(): List<AudioEndpoint> {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .map { it.toEndpoint(isSource = false) }
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .map { it.toEndpoint(isSource = true) }
        return outputs + inputs
    }

    // --- internals -------------------------------------------------------

    @RequiresApi(Build.VERSION_CODES.S)
    private fun applyCommunicationDevice(target: AudioEndpoint): Boolean {
        val device = audioManager.availableCommunicationDevices
            .firstOrNull { it.id == target.id }
            ?: audioManager.availableCommunicationDevices
                .firstOrNull { classify(it.type) == target.kind }
            ?: return false
        return audioManager.setCommunicationDevice(device)
    }

    private fun selectInputEndpoint(preferBluetooth: Boolean): AudioEndpoint {
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .map { it.toEndpoint(isSource = true) }
        if (preferBluetooth) {
            inputs.firstOrNull { it.kind == AudioDeviceKind.AIRPODS }?.let { return it }
            inputs.firstOrNull { it.kind == AudioDeviceKind.BLUETOOTH_HEADSET }?.let { return it }
            inputs.firstOrNull { it.kind == AudioDeviceKind.WIRED_HEADSET }?.let { return it }
        }
        return inputs.firstOrNull { it.kind == AudioDeviceKind.PHONE }
            ?: inputs.firstOrNull()
            ?: AudioEndpoint(-1, "builtin", AudioDeviceKind.PHONE, isSource = true)
    }

    private fun currentOutputEndpoint(): AudioEndpoint? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.communicationDevice?.let { return it.toEndpoint(isSource = false) }
        }
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .map { it.toEndpoint(isSource = false) }
            .firstOrNull { it.kind.isBluetooth() }
    }

    private fun requestFocus(): Boolean {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .setWillPauseWhenDucked(true)
            .build()
        focusRequest = request
        return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private fun preferredSampleRate(): Int =
        audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 16_000

    private fun AudioDeviceInfo.toEndpoint(isSource: Boolean): AudioEndpoint =
        AudioEndpoint(
            id = id,
            productName = productName?.toString().orEmpty(),
            kind = classify(type, productName?.toString().orEmpty()),
            isSource = isSource,
        )

    private fun classify(type: Int, productName: String = ""): AudioDeviceKind = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> {
            if (productName.contains("AirPods", ignoreCase = true)) AudioDeviceKind.AIRPODS
            else AudioDeviceKind.BLUETOOTH_HEADSET
        }
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET -> AudioDeviceKind.WIRED_HEADSET
        AudioDeviceInfo.TYPE_BUILTIN_MIC,
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioDeviceKind.PHONE
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioDeviceKind.SPEAKER
        else -> AudioDeviceKind.UNKNOWN
    }

    private companion object {
        const val TAG = "JarvisAudioRoute"
    }
}

private fun AudioDeviceKind.isBluetooth(): Boolean =
    this == AudioDeviceKind.AIRPODS || this == AudioDeviceKind.BLUETOOTH_HEADSET
