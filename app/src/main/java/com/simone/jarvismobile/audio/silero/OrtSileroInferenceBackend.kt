package com.simone.jarvismobile.audio.silero

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.simone.jarvismobile.core.voice.silero.SileroElementType
import com.simone.jarvismobile.core.voice.silero.SileroFailure
import com.simone.jarvismobile.core.voice.silero.SileroGraphContract
import com.simone.jarvismobile.core.voice.silero.SileroGraphDescriptor
import com.simone.jarvismobile.core.voice.silero.SileroInferenceBackend
import com.simone.jarvismobile.core.voice.silero.SileroInferenceResult
import com.simone.jarvismobile.core.voice.silero.SileroModelManifest
import com.simone.jarvismobile.core.voice.silero.SileroTensorDescriptor
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Production Silero backend on the project's existing onnxruntime-android (the same process-wide
 * [OrtEnvironment] Kokoro/Piper use — NEVER closed here, only this backend's own session).
 * Single-threaded CPU session: the model is tiny and runs once per 32 ms frame.
 */
class OrtSileroInferenceBackend private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
) : SileroInferenceBackend {

    override fun run(input: FloatArray, state: FloatArray, sampleRateHz: Long): SileroInferenceResult {
        val tensors = ArrayList<OnnxTensor>(3)
        return try {
            val inputT = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, input.size.toLong())).also { tensors += it }
            val stateT = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(state),
                longArrayOf(SileroModelManifest.STATE_LAYERS.toLong(), 1, SileroModelManifest.STATE_HIDDEN.toLong()),
            ).also { tensors += it }
            val srT = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(sampleRateHz)), longArrayOf()).also { tensors += it } // int64 scalar
            session.run(
                mapOf(SileroGraphContract.INPUT to inputT, SileroGraphContract.STATE to stateT, SileroGraphContract.SR to srT),
            ).use { extract(it) }
        } catch (e: Exception) {
            SileroInferenceResult.Failed(SileroFailure.INFERENCE_FAILED)
        } finally {
            tensors.forEach { runCatching { it.close() } }
        }
    }

    private fun extract(result: OrtSession.Result): SileroInferenceResult {
        val out = result.get(SileroGraphContract.OUTPUT).orElse(null) as? OnnxTensor
        val next = result.get(SileroGraphContract.STATE_N).orElse(null) as? OnnxTensor
        if (out == null || next == null) return SileroInferenceResult.Failed(SileroFailure.INVALID_OUTPUT)
        val p = out.floatBuffer
        val s = next.floatBuffer
        if (p.remaining() != 1 || s.remaining() != SileroModelManifest.STATE_FLOATS) {
            return SileroInferenceResult.Failed(SileroFailure.INVALID_OUTPUT)
        }
        val nextState = FloatArray(SileroModelManifest.STATE_FLOATS)
        s.get(nextState)
        return SileroInferenceResult.Ok(p.get(0), nextState)
    }

    override fun close() {
        runCatching { session.close() } // the shared OrtEnvironment is deliberately left open
    }

    companion object {
        private fun newOptions() = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
        }

        val factory = SileroBackendFactory { model ->
            try {
                val env = OrtEnvironment.getEnvironment()
                val session = newOptions().use { env.createSession(model.absolutePath, it) }
                SileroBackendOpen.Ok(OrtSileroInferenceBackend(env, session))
            } catch (e: Exception) {
                SileroBackendOpen.Failed(SileroFailure.ORT_LOAD_FAILED)
            } catch (e: UnsatisfiedLinkError) {
                SileroBackendOpen.Failed(SileroFailure.ORT_LOAD_FAILED)
            }
        }

        /** Reads the real graph (names/types/shapes) of [model] with ORT; the session is closed right away. */
        val inspector = SileroGraphInspector { model ->
            try {
                val env = OrtEnvironment.getEnvironment()
                newOptions().use { options ->
                    env.createSession(model.absolutePath, options).use { session ->
                        fun describe(info: Map<String, ai.onnxruntime.NodeInfo>) = info.map { (name, node) ->
                            val t = node.info as? TensorInfo
                            SileroTensorDescriptor(
                                name = name,
                                elementType = when (t?.type) {
                                    OnnxJavaType.FLOAT -> SileroElementType.FLOAT32
                                    OnnxJavaType.INT64 -> SileroElementType.INT64
                                    else -> SileroElementType.OTHER
                                },
                                shape = t?.shape?.toList() ?: listOf(-2L),
                            )
                        }
                        SileroGraphInspection.Ok(SileroGraphDescriptor(describe(session.inputInfo), describe(session.outputInfo)))
                    }
                }
            } catch (e: Exception) {
                SileroGraphInspection.Failed(SileroFailure.ORT_LOAD_FAILED)
            } catch (e: UnsatisfiedLinkError) {
                SileroGraphInspection.Failed(SileroFailure.ORT_LOAD_FAILED)
            }
        }
    }
}
