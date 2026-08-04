package com.simone.jarvismobile.llm

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device LLM backed by MediaPipe LLM Inference. Runs fully offline on a model
 * file the user imports (`.task`/`.litertlm` produced for MediaPipe). No network,
 * no cloud.
 *
 * Not compiled in the scaffolding container (no Android SDK); built in CI.
 */
@Singleton
class MediaPipeLlmEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) : LlmEngine {

    private val _loadState = MutableStateFlow(LlmLoadState.UNLOADED)
    override val loadState = _loadState.asStateFlow()

    private val _loadedModelName = MutableStateFlow<String?>(null)
    override val loadedModelName = _loadedModelName.asStateFlow()

    private val _lastLoadDetail = MutableStateFlow("")
    override val lastLoadDetail = _lastLoadDetail.asStateFlow()

    @Volatile private var inference: LlmInference? = null

    override suspend fun load(modelPath: String, modelName: String): Boolean =
        withContext(Dispatchers.Default) {
            _loadState.value = LlmLoadState.LOADING
            runCatching { inference?.close() }
            inference = null
            try {
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelPath)
                    .setMaxTokens(MAX_TOKENS)
                    .build()
                inference = LlmInference.createFromOptions(context, options)
                _loadedModelName.value = modelName
                _lastLoadDetail.value = ""
                _loadState.value = LlmLoadState.LOADED
                Log.i(TAG, "llm_loaded")
                true
            } catch (e: Throwable) {
                Log.w(TAG, "llm_load_failed ${e.javaClass.simpleName}")
                _lastLoadDetail.value = "${e.javaClass.simpleName}: ${e.message?.take(220) ?: ""}"
                _loadState.value = LlmLoadState.ERROR
                _loadedModelName.value = null
                false
            }
        }

    override fun unload() {
        runCatching { inference?.close() }
        inference = null
        _loadState.value = LlmLoadState.UNLOADED
        _loadedModelName.value = null
    }

    override suspend fun generate(prompt: String): String? = withContext(Dispatchers.Default) {
        val engine = inference ?: return@withContext null
        try {
            engine.generateResponse(prompt)
        } catch (e: Throwable) {
            Log.w(TAG, "llm_generate_failed ${e.javaClass.simpleName}")
            null
        }
    }

    override fun cancel() {
        // MediaPipe's synchronous generateResponse cannot be interrupted mid-call;
        // cancellation applies to the surrounding coroutine. No-op here.
    }

    private companion object {
        const val TAG = "JarvisLlm"
        const val MAX_TOKENS = 1024
    }
}
