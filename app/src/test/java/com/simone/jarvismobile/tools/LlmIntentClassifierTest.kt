package com.simone.jarvismobile.tools

import com.simone.jarvismobile.llm.ClassifierEngineProvider
import com.simone.jarvismobile.llm.LlmEngine
import com.simone.jarvismobile.llm.LlmLoadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmIntentClassifierTest {
    @Test
    fun highConfidenceToolMayExecute() = runTest {
        val understanding = LlmIntentClassifier(ClassifierEngineProvider { FakeLlm("battery_status|96") })
            .understand("È in carica in questo momento?", "Batteria al 80 per cento")

        assertEquals("battery_status", understanding.intent)
        assertTrue(understanding.mayExecute)
        assertEquals("battery_status", (understanding.match as Match.Run).call.name)
    }

    @Test
    fun lowConfidenceToolIsKeptOutOfExecution() = runTest {
        val understanding = LlmIntentClassifier(ClassifierEngineProvider { FakeLlm("flashlight|35") })
            .understand("Non so se parlavo della luce o di altro")

        assertFalse(understanding.mayExecute)
        assertTrue(understanding.needsReasoning)
    }

    @Test
    fun reasoningIntentEscalatesWithoutInventingATool() = runTest {
        val understanding = LlmIntentClassifier(ClassifierEngineProvider { FakeLlm("ragiona|94") })
            .understand("Confronta due modi per fare questo lavoro")

        assertNull(understanding.match)
        assertTrue(understanding.needsReasoning)
    }

    @Test
    fun phoneDraftUsesOnlyTheNumberFromTheUser() = runTest {
        val understanding = LlmIntentClassifier(ClassifierEngineProvider { FakeLlm("prepare_call|97") })
            .understand("Potresti telefonare al +39 333 123 4567?")

        assertTrue(understanding.mayExecute)
        val match = understanding.match as Match.Run
        assertEquals("prepare_call", match.call.name)
        assertEquals(
            "+393331234567",
            match.call.arguments["number"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun cancellationIsNeverTurnedIntoAClassifierMiss() = runTest {
        var propagated = false
        try {
            LlmIntentClassifier(ClassifierEngineProvider { FakeLlm("", CancellationException("stop")) })
                .understand("Accendi qualcosa")
        } catch (_: CancellationException) {
            propagated = true
        }
        assertTrue(propagated)
    }

    private class FakeLlm(
        private val answer: String,
        private val failure: Throwable? = null,
    ) : LlmEngine {
        override val loadState: StateFlow<LlmLoadState> = MutableStateFlow(LlmLoadState.LOADED)
        override val loadedModelName: StateFlow<String?> = MutableStateFlow("fake")
        override val lastLoadDetail: StateFlow<String> = MutableStateFlow("")
        override val generating: StateFlow<Boolean> = MutableStateFlow(false)
        override suspend fun load(modelPath: String, modelName: String) = true
        override fun unload() = Unit
        override suspend fun generate(prompt: String, timeoutSeconds: Long): String {
            failure?.let { throw it }
            return answer
        }
        override suspend fun chat(userText: String, systemPrompt: String, timeoutSeconds: Long): String = answer
        override fun resetConversation() = Unit
        override fun cancel() = Unit
    }
}
