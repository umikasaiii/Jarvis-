package com.simone.jarvismobile.audio.silero

import com.simone.jarvismobile.core.voice.silero.SileroFailure
import com.simone.jarvismobile.core.voice.silero.SileroGraphDescriptor
import com.simone.jarvismobile.core.voice.silero.SileroInferenceBackend
import java.io.File

/** Result of inspecting an ONNX file's graph (the real binary stays authoritative over the manifest). */
sealed interface SileroGraphInspection {
    class Ok(val graph: SileroGraphDescriptor) : SileroGraphInspection
    class Failed(val failure: SileroFailure) : SileroGraphInspection
}

fun interface SileroGraphInspector {
    fun inspect(model: File): SileroGraphInspection
}

sealed interface SileroBackendOpen {
    class Ok(val backend: SileroInferenceBackend) : SileroBackendOpen
    class Failed(val failure: SileroFailure) : SileroBackendOpen
}

fun interface SileroBackendFactory {
    fun open(model: File): SileroBackendOpen
}
