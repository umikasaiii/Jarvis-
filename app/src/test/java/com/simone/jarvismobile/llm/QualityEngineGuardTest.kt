package com.simone.jarvismobile.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * HOTFIX 5 — structural guards (JVM, source-reading): the Classic chat path must keep going through the shared,
 * JVM-tested quality stage, and the Conversational engine must stay untouched. TEST KIND: static guard; proves code
 * shape, not model quality.
 */
class QualityEngineGuardTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "app/src/main/java").isDirectory || File(it, "src/main/java").isDirectory }
        .let { if (File(it, "app/src/main/java").isDirectory) File(it, "app/src/main/java") else File(it, "src/main/java") }

    private fun code(rel: String) = File(root, "com/simone/jarvismobile/$rel").readLines()
        .filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") || it.trim().startsWith("/*") }
        .joinToString("\n")

    @Test fun classicChatGoesThroughTheValidatedGenerationStage() {
        val c = code("audio/SessionCoordinator.kt")
        val chatReply = c.substringAfter("private suspend fun chatReply(").substringBefore("private fun chatHistoryForSeed")
        assertTrue("ValidatedGeneration.run(" in chatReply)
        assertFalse("AssistantReplyCleaner.clean(" in chatReply) // cleaning happens inside the stage, once
        assertTrue("router.chat(message, system, slot, seed = seed)" in chatReply)
        // Bounded retry: one retry branch only, and it runs on a fresh conversation.
        assertTrue(Regex("""router\.chat\(message, retrySystem, slot, seed = retrySeed\)""").findAll(chatReply).count() == 1)
        assertTrue("router.resetConversation(slot)" in chatReply)
    }

    @Test fun historyIsASeedNotATranscriptInTheSystemBlock() {
        val c = code("audio/SessionCoordinator.kt")
        val build = c.substringAfter("private fun buildSystem(").substringBefore("suspend fun sendText(")
        assertTrue("ClassicPromptComposer.compose(" in build)
        assertFalse("Simone: " in build || "Tu: " in build)
        val e = code("llm/LitertLmEngine.kt")
        assertTrue("initialMessages = initial" in e)
        assertTrue("SamplerConfig(topK = it.topK, topP = it.topP, temperature = it.temperature)" in e)
    }

    @Test fun conversationalEngineAndItsPromptAreUntouchedByTheClassicQualityStage() {
        listOf("engine/JarvisBrain.kt", "engine/ConversationalJarvisEngine.kt", "engine/ContextAssembler.kt").forEach {
            val s = code(it)
            assertFalse("$it must not depend on the classic quality stage", "ClassicPromptComposer" in s || "ValidatedGeneration" in s)
        }
    }

    @Test fun traceRecordingNeverReceivesTextFields() {
        val c = code("audio/SessionCoordinator.kt")
        val rec = c.substringAfter("private fun recordTrace(").substringBefore("private suspend fun offerFactCapture(")
        assertTrue("transcript.length" in rec && "TokenEstimator.estimate(message)" in rec)
        assertFalse("DebugTextProbe" in rec) // the only text sink is the explicit in-memory probe, outside the trace
        assertTrue(Regex("""DebugTextProbe\.capture\(""").findAll(c).count() == 1) // one in-memory capture point
        val diag = code("llm/InferenceDiagnostics.kt")
        assertFalse("DebugTextProbe" in diag) // never persisted
    }

    @Test fun systemAssetIsCompactAndKeepsTheRequiredRules() {
        val asset = File(root, "../assets/prompts/jarvis_system_it.md").readText()
        assertTrue("asset must stay compact, was ${asset.length}", asset.length < 2_000)
        listOf("JARVIS", "italiano", "Fonti", "cita la fonte", "non inventare", "Calcoli", "offline").forEach {
            assertTrue("missing '$it'", asset.contains(it, ignoreCase = true))
        }
        assertFalse("REGOLA FONDAMENTALE" in asset) // the long negative-rule block is gone
    }
}
