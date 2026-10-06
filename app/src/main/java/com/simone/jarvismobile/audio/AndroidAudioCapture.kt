package com.simone.jarvismobile.audio

import android.content.Context
import com.simone.jarvismobile.core.voice.PcmCaptureEngine
import com.simone.jarvismobile.core.voice.PcmCaptureMode
import com.simone.jarvismobile.core.voice.VoiceCaptureFailure
import com.simone.jarvismobile.core.voice.VoiceCaptureOutcome
import com.simone.jarvismobile.core.voice.VoiceCaptureSnapshot
import com.simone.jarvismobile.core.voice.VoicePcmFrame
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [AudioCapture] backed by the canonical [PcmCaptureEngine] over the single
 * [AudioRecordPcmSourceFactory]. Live Voice Phase 0.7: the fixed-window [capture] keeps its
 * exact legacy behavior (RMS level only, PCM discarded) but is now a thin wrapper over the
 * same capture primitive that exposes bounded PCM frames. Production conversation STT is
 * still Android SpeechRecognizer; nothing here runs concurrently with it.
 *
 * Not compiled in the scaffolding container (no Android SDK); built in CI.
 */
@Singleton
class AndroidAudioCapture @Inject constructor(
    @ApplicationContext context: Context,
) : AudioCapture {

    private val engine = PcmCaptureEngine(
        factory = AudioRecordPcmSourceFactory(context),
        readDispatcher = Dispatchers.Default,
    )

    override val micLevel: StateFlow<Float> = engine.micLevel
    override val lastDetail: StateFlow<String> = engine.lastDetail
    override val frames: SharedFlow<VoicePcmFrame> = engine.frames
    override val captureSnapshot: StateFlow<VoiceCaptureSnapshot> = engine.snapshot

    override fun cancel() = engine.cancel()

    override suspend fun capture(durationMs: Long): CaptureResult = toLegacy(engine.run(durationMs))

    override suspend fun captureContinuous(mode: PcmCaptureMode): VoiceCaptureOutcome = engine.run(null, mode)

    private fun toLegacy(o: VoiceCaptureOutcome): CaptureResult = when (o.failure) {
        null -> CaptureResult.COMPLETED
        VoiceCaptureFailure.PERMISSION_DENIED -> CaptureResult.PERMISSION_DENIED
        else -> CaptureResult.FAILED
    }
}
