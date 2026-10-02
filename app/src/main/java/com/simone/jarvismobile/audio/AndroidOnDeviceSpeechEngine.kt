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
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicLong
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

    private val _speechEvents = MutableSharedFlow<SttSpeechEvent>(replay = 0, extraBufferCapacity = 4)
    override val speechEvents: SharedFlow<SttSpeechEvent> = _speechEvents.asSharedFlow()

    private val _attemptSummaries = MutableSharedFlow<SttAttemptSummary>(replay = 0, extraBufferCapacity = 4)
    override val attemptSummaries: SharedFlow<SttAttemptSummary> = _attemptSummaries.asSharedFlow()

    @Volatile private var recognizer: SpeechRecognizer? = null

    /**
     * Live Voice Phase 0.3 (now also fencing [RecognitionListener.onReadyForSpeech]
     * since Phase 0.5) — monotonically increasing generation, bumped once
     * per internal recognizer attempt ([attempt] below), whether that
     * attempt belongs to a brand-new [transcribe] invocation or one of this
     * engine's own internal transient retries of the same invocation. One
     * counter does both levels of fencing at once: a late
     * [RecognitionListener] callback compares the generation it captured at
     * its own attempt's start against [attemptGeneration]'s current value
     * and only emits when they still match — deterministically rejecting a
     * stale callback (from an abandoned retry attempt, or from an entirely
     * different, already-superseded invocation) the instant a newer attempt
     * has begun, never by a time window. A stale `onReadyForSpeech` from an
     * abandoned retry is fenced exactly the same way as a stale
     * `onBeginningOfSpeech`/`onEndOfSpeech` — same comparison, same
     * guarantee. Instance-scoped (never a companion object), so
     * [RecognizerWakeWordEngine]'s own separate instance of this engine has
     * its own independent counter. This is the only place that reads/writes
     * it; [System.nanoTime] itself is never read here — see
     * [VoiceTurnDiagnosticsRecorder], the sole clock owner.
     */
    private val attemptGeneration = AtomicLong(0L)

    override fun isAvailable(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
        } else {
            // Pre-33 can't query on-device availability; assume creatable and let
            // transcribe() surface a failure if not.
            SpeechRecognizer.isRecognitionAvailable(context)
        }

    /**
     * On a cold start the recognition service is not yet bound, so the first few
     * `startListening` calls return a transient client/busy/server error before it
     * warms up — the orb "went to error a few times, then started". A single retry
     * wasn't enough: the service can need most of a second to bind. So retry the
     * transient errors several times with a growing settle (0 → 250 → 500 → 900 →
     * 1400 ms) and a fresh recognizer each time, so the first press just works. A
     * real no-speech / permission / unavailable / language result is returned as-is
     * the moment it appears — those are never retried.
     */
    override suspend fun transcribe(languageTag: String, invocationId: String): SttResult {
        // Live Voice Phase 0.5 — purely observational: counts how many times
        // this loop actually calls attempt() for THIS invocation, changes
        // nothing about the loop itself (same backoff list, same transient-
        // code check, same early return). Emitted once, synchronously,
        // right before returning — see SttAttemptSummary's own doc comment
        // for why this is deliberately not threaded through speechEvents.
        var attemptsMade = 0
        var lastTransient: SttResult.Failure? = null
        for (settle in RETRY_BACKOFF_MS) {
            if (settle > 0) delay(settle)
            attemptsMade++
            val r = attempt(languageTag, invocationId)
            if (r !is SttResult.Failure || r.code !in TRANSIENT_CODES) {
                _attemptSummaries.tryEmit(SttAttemptSummary(invocationId, attemptsMade))
                return r
            }
            lastTransient = r
        }
        _attemptSummaries.tryEmit(SttAttemptSummary(invocationId, attemptsMade))
        return lastTransient ?: SttResult.Failure("stt_unknown")
    }

    private suspend fun attempt(languageTag: String, invocationId: String): SttResult = withContext(Dispatchers.Main) {
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

        // Live Voice Phase 0.3 — this specific attempt's own generation,
        // captured once here and compared against attemptGeneration's live
        // value inside the listener callbacks below; see attemptGeneration's
        // own doc comment for what this fences against.
        val myGeneration = attemptGeneration.incrementAndGet()

        val deferred = CompletableDeferred<SttResult>()
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (myGeneration == attemptGeneration.get()) {
                    _speechEvents.tryEmit(SttSpeechEvent(invocationId, SttSpeechEvent.Type.READY))
                }
            }

            override fun onBeginningOfSpeech() {
                if (myGeneration == attemptGeneration.get()) {
                    _speechEvents.tryEmit(SttSpeechEvent(invocationId, SttSpeechEvent.Type.STARTED))
                }
            }

            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                if (myGeneration == attemptGeneration.get()) {
                    _speechEvents.tryEmit(SttSpeechEvent(invocationId, SttSpeechEvent.Type.ENDED))
                }
            }

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
        // Settle delays before each attempt. The first is immediate; the rest grow
        // to give the recognition service time to bind on a cold start. Up to five
        // attempts total, ~3 s of retries in the worst case.
        val RETRY_BACKOFF_MS = listOf(0L, 250L, 500L, 900L, 1400L)
        // Transient recognizer errors worth retrying on a cold start:
        // ERROR_CLIENT (5), ERROR_RECOGNIZER_BUSY (8), ERROR_SERVER (4),
        // ERROR_SERVER_DISCONNECTED (11), and the create/exception paths —
        // never NoSpeech, permission, language or unavailable.
        val TRANSIENT_CODES = setOf(
            "stt_error_5", "stt_error_8", "stt_error_4", "stt_error_11",
            "stt_exception", "stt_unknown",
        )
    }
}
