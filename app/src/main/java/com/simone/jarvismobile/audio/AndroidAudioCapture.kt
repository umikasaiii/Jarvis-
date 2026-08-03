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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Real [AudioCapture] backed by [AudioRecord] using the VOICE_COMMUNICATION
 * source so it follows the selected communication device. The PCM buffer is read
 * only to compute an RMS level and is never stored.
 *
 * Not compiled in the scaffolding container (no Android SDK); built in CI.
 */
@Singleton
class AndroidAudioCapture @Inject constructor(
    @ApplicationContext private val context: Context,
) : AudioCapture {

    private val _micLevel = MutableStateFlow(0f)
    override val micLevel = _micLevel.asStateFlow()

    private val cancelled = AtomicBoolean(false)

    override fun cancel() {
        cancelled.set(true)
    }

    override suspend fun capture(durationMs: Long): CaptureResult = withContext(Dispatchers.Default) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext CaptureResult.PERMISSION_DENIED
        }
        cancelled.set(false)

        val sampleRate = 16_000
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "audio_record min_buffer_invalid=$minBuffer")
            return@withContext CaptureResult.FAILED
        }
        val bufferSize = minBuffer * 2

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        } catch (e: SecurityException) {
            Log.w(TAG, LogRedactor.redact("audio_record security_exception ${e.message}"))
            return@withContext CaptureResult.PERMISSION_DENIED
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "audio_record bad_config ${e.message}")
            return@withContext CaptureResult.FAILED
        }

        try {
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "audio_record not_initialized state=${record.state}")
                return@withContext CaptureResult.FAILED
            }
            record.startRecording()
            val readBuffer = ShortArray(bufferSize / 2)
            val deadline = System.nanoTime() + durationMs * 1_000_000L
            while (System.nanoTime() < deadline && coroutineContext.isActive && !cancelled.get()) {
                val n = record.read(readBuffer, 0, readBuffer.size)
                if (n > 0) _micLevel.value = rms(readBuffer, n)
            }
            record.stop()
            _micLevel.value = 0f
            if (cancelled.get()) CaptureResult.FAILED else CaptureResult.COMPLETED
        } catch (e: IllegalStateException) {
            Log.w(TAG, "audio_record illegal_state ${e.message}")
            CaptureResult.FAILED
        } finally {
            // Discard everything: release the recorder; the buffer goes out of scope.
            runCatching { record.release() }
            _micLevel.value = 0f
        }
    }

    private fun rms(buffer: ShortArray, length: Int): Float {
        var sum = 0.0
        for (i in 0 until length) {
            val s = buffer[i].toDouble()
            sum += s * s
        }
        val rms = sqrt(sum / length)
        // Normalize against full-scale 16-bit (32768) with a gentle ceiling.
        return min(1f, (rms / 32768.0 * 4.0).toFloat())
    }

    private companion object {
        const val TAG = "JarvisAudioCapture"
    }
}
