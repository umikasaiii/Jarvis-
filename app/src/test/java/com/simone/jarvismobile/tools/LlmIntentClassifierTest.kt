package com.simone.jarvismobile.tools

import com.simone.jarvismobile.llm.LlmEngine
import com.simone.jarvismobile.llm.LlmLoadState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmIntentClassifierTest {
    @Test
    fun highConfidenceToolMayExecute() = runTest {
        val understanding = LlmIntentClassifier(FakeLlm("battery_status|96"))
            .understand("È in carica in questo momento?", "Batteria al 80 per cento")

        assertEquals("battery_status", understanding.intent)
        assertTrue(understanding.mayExecute)
        assertEquals("battery_status", (understanding.match as Match.Run).call.name)
    }

    @Test
    fun lowConfidenceToolIsKeptOutOfExecution() = runTest {
        val understanding = LlmIntentClassifier(FakeLlm("flashlight|35"))
            .understand("Non so se parlavo della luce o di altro")

        assertFalse(understanding.mayExecute)
        assertTrue(understanding.needsReasoning)
    }

    @Test
    fun reasoningIntentEscalatesWithoutInventingATool() = runTest {
        val understanding = LlmIntentClassifier(FakeLlm("ragiona|94"))
            .understand("Confronta due modi per fare questo lavoro")

        assertNull(understanding.match)
        assertTrue(understanding.needsReasoning)
    }

    private class FakeLlm(private val answer: String) : LlmEngine {
        override val loadState: StateFlow<LlmLoadState> = MutableStateFlow(LlmLoadState.LOADED)
        override val loadedModelName: StateFlow<String?> = MutableStateFlow("fake")
        override val lastLoadDetail: StateFlow<String> = MutableStateFlow("")
        override suspend fun load(modelPath: String, modelName: String) = true
        override fun unload() = Unit
        override suspend fun generate(prompt: String): String = answer
        override suspend fun chat(userText: String, systemPrompt: String): String = answer
        override fun resetConversation() = Unit
        override fun cancel() = Unit
    }
}
