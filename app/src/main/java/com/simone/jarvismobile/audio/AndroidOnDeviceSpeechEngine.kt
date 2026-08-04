package com.simone.jarvismobile.audio

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline STT backed by the Android **on-device** recognizer
 * (`SpeechRecognizer.createOnDeviceSpeechRecognizer`), which runs without network
 * and without any model import. Availability depends on the device shipping an
 * on-device recognition service; when absent we report [SttResult.Unavailable]
 * (and never fall back to a cloud recognizer).
 *
 * SpeechRecognizer must be created and driven on the main thread.
 */
@Singleton
class AndroidOnDeviceSpeechEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) : SpeechToTextEngine {

    private val _partial = MutableStateFlow("")
    override val partial = _partial.asStateFlow()

    @Volatile private var recognizer: SpeechRecognizer? = null

    override fun isAvailable(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
        } else {
            // Pre-33 can't query on-device availability; assume creatable and let
            // transcribe() surface a failure if not.
            SpeechRecognizer.isRecognitionAvailable(context)
        }

    override suspend fun transcribe(languageTag: String): SttResult = withContext(Dispatchers.Main) {
        _partial.value = ""
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
        ) {
            return@withContext SttResult.Unavailable("no_ondevice_recognizer")
        }

        val rec = try {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } catch (e: Exception) {
            return@withContext SttResult.Unavailable("create_failed:${e.javaClass.simpleName}")
        }
        recognizer = rec

        val deferred = CompletableDeferred<SttResult>()
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onPartialResults(partialResults: Bundle?) {
                bestOf(partialResults)?.let { _partial.value = it }
            }

            override fun onResults(results: Bundle?) {
                val text = bestOf(results)
                if (deferred.isActive) {
                    deferred.complete(
                        if (text.isNullOrBlank()) SttResult.NoSpeech else SttResult.Text(text),
                    )
                }
            }

            override fun onError(error: Int) {
                if (!deferred.isActive) return
                // Note: language-not-supported (12) / unavailable (13) are API-33+
                // constants; we avoid referencing them directly and map by number.
                deferred.complete(
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SttResult.NoSpeech
                        12, 13 -> SttResult.Unavailable("language:$languageTag")
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SttResult.Failure("permission")
                        else -> SttResult.Failure("stt_error_$error")
                    },
                )
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }

        val result = try {
            rec.startListening(intent)
            withTimeout(RECOGNITION_TIMEOUT_MS) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            SttResult.NoSpeech
        } catch (e: Exception) {
            Log.w(TAG, "stt_exception ${e.javaClass.simpleName}")
            SttResult.Failure("stt_exception")
        } finally {
            runCatching { rec.destroy() }
            recognizer = null
            _partial.value = ""
        }
        result
    }

    override fun cancel() {
        runCatching { recognizer?.cancel() }
    }

    private fun bestOf(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private companion object {
        const val TAG = "JarvisStt"
        const val RECOGNITION_TIMEOUT_MS = 20_000L
    }
}
