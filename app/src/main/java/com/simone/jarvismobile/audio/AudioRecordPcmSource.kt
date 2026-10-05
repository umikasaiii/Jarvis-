package com.simone.jarvismobile.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.simone.jarvismobile.core.security.LogRedactor
import com.simone.jarvismobile.core.voice.PcmSource
import com.simone.jarvismobile.core.voice.PcmSourceFactory
import com.simone.jarvismobile.core.voice.PcmSourceOpenResult
import com.simone.jarvismobile.core.voice.VoiceCaptureFailure

/**
 * Live Voice Phase 0.7 — the ONLY `AudioRecord` in the app. Opening logic is the unchanged
 * legacy [AndroidAudioCapture] one (VOICE_COMMUNICATION -> MIC -> DEFAULT fallback, 16 kHz
 * mono PCM16, buffer = 2 x min). It deliberately does NOT touch audio focus, audio mode,
 * communication device or [AndroidAudioRouteManager.beginSession] (MagicOS workaround).
 *
 * Not compiled in the scaffolding container (no Android SDK); built in CI.
 */
class AudioRecordPcmSourceFactory(private val context: Context) : PcmSourceFactory {

    override fun open(): PcmSourceOpenResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) return PcmSourceOpenResult.Failed(VoiceCaptureFailure.PERMISSION_DENIED, "permission_denied")

        val sampleRate = 16_000
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "audio_record min_buffer_invalid=$minBuffer")
            return PcmSourceOpenResult.Failed(VoiceCaptureFailure.INITIALIZATION_FAILED, "min_buffer_invalid=$minBuffer")
        }
        val bufferSize = minBuffer * 2

        // Some devices/ROMs fail to initialize VOICE_COMMUNICATION when no
        // communication device is active; fall back to MIC then DEFAULT.
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT,
        )
        val states = StringBuilder()
        for (source in sources) {
            val candidate = try {
                AudioRecord(source, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
            } catch (e: SecurityException) {
                Log.w(TAG, LogRedactor.redact("audio_record security_exception ${e.message}"))
                return PcmSourceOpenResult.Failed(VoiceCaptureFailure.PERMISSION_DENIED, "security_exception")
            } catch (e: IllegalArgumentException) {
                states.append("src$source:iae ")
                Log.w(TAG, "audio_record bad_config source=$source ${e.message}")
                null
            }
            if (candidate != null && candidate.state == AudioRecord.STATE_INITIALIZED) {
                return PcmSourceOpenResult.Opened(AudioRecordPcmSource(candidate, sampleRate, "source=$source"))
            }
            states.append("src$source:st${candidate?.state} ")
            candidate?.release()
        }
        Log.w(TAG, "audio_record no_source_initialized $states")
        return PcmSourceOpenResult.Failed(VoiceCaptureFailure.INITIALIZATION_FAILED, "no_source_init ${states.toString().trim()}")
    }

    private class AudioRecordPcmSource(
        private val record: AudioRecord,
        override val sampleRateHz: Int,
        override val description: String,
    ) : PcmSource {
        override fun start(): Boolean = try {
            record.startRecording()
            true // legacy behavior: no recordingState gate
        } catch (e: IllegalStateException) {
            Log.w(TAG, "audio_record illegal_state ${e.message}")
            false
        }

        override fun read(dest: ShortArray, count: Int): Int = try {
            record.read(dest, 0, count)
        } catch (e: IllegalStateException) {
            -1
        }

        override fun stop() { runCatching { record.stop() } }
        override fun release() { runCatching { record.release() } }
    }

    private companion object {
        const val TAG = "JarvisAudioCapture"
    }
}
