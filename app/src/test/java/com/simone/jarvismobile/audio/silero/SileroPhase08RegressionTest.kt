package com.simone.jarvismobile.audio.silero

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Live Voice Phase 0.8 — source-scan guards (plain JVM). Silero is a diagnostics-only VAD engine:
 * it must NOT touch conversation, STT, TTS, barge-in or the microphone owners, and no model binary
 * may be committed or bundled.
 */
class SileroPhase08RegressionTest {
    private fun roots() = listOf(File("src/main/java/com/simone/jarvismobile"), File("app/src/main/java/com/simone/jarvismobile"))
    private fun root(): File = roots().firstOrNull { it.isDirectory } ?: error("cannot locate app source root from ${File(".").absolutePath}")
    private fun repoRoot(): File = generateSequence(File(".").absoluteFile) { it.parentFile }.first { File(it, "core").isDirectory && File(it, "app").isDirectory }
    private fun code(file: File) = file.readLines().filter { val t = it.trim(); !t.startsWith("*") && !t.startsWith("//") && !t.startsWith("/*") }
    private fun allKt() = root().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    private val sileroFiles get() = allKt().filter { "silero" in it.path.lowercase().replace('\\', '/') || it.name.startsWith("Silero") }

    @Test fun `no model binary is committed or bundled`() {
        val bad = repoRoot().walkTopDown().onEnter { it.name !in setOf(".git", "build", ".gradle", "node_modules") }
            .filter { it.isFile && (it.extension == "onnx") }.map { it.path }.toList()
        assertTrue("model binary in repo: $bad", bad.isEmpty())
        assertTrue(File(repoRoot(), ".gitignore").readText().lines().any { it.trim() == "*.onnx" })
    }

    @Test fun `silero code never controls conversation tts stt or barge-in`() {
        val forbidden = listOf("tts.stop", "interruptAndListen", "ConversationEvent", "SpeechRecognizer", "beginSession", "requestAudioFocus", "setMode(", "setCommunicationDevice", "AudioRecord(")
        val offenders = sileroFiles.flatMap { f -> code(f).filter { l -> forbidden.any { l.contains(it) } }.map { f.name + ": " + it.trim() } }
        assertTrue("Silero must stay diagnostics-only: $offenders", offenders.isEmpty())
    }

    @Test fun `production voice paths never reference silero`() {
        val protectedFiles = setOf("SessionCoordinator.kt", "AndroidOnDeviceSpeechEngine.kt", "WakeWordController.kt", "WakeWordEngine.kt", "LiveTranslatorManager.kt", "HybridTtsEngine.kt", "ListeningService.kt")
        val offenders = allKt().filter { it.name in protectedFiles }.filter { f -> code(f).any { it.contains("Silero", ignoreCase = true) } }.map { it.name }
        assertTrue("Silero leaked into production voice: $offenders", offenders.isEmpty())
    }

    @Test fun `only the qualification service consumes the canonical frames for silero`() {
        val users = sileroFiles.filter { f -> code(f).any { it.contains("audioCapture.frames") } }.map { it.name }
        assertEquals(listOf("SileroVadQualificationService.kt"), users)
    }

    @Test fun `silero never persists pcm or probabilities`() {
        val forbidden = listOf("FileOutputStream", "writeBytes", "@Dao", "@Entity", "HttpURLConnection", "OkHttp")
        // SileroModelStore legitimately writes the MODEL file (not audio): exempt only that file from file-write checks.
        val offenders = sileroFiles.filter { it.name != "SileroModelStore.kt" }.flatMap { f -> code(f).filter { l -> forbidden.any { l.contains(it) } }.map { f.name } }
        assertTrue("persistence/network in silero code: $offenders", offenders.isEmpty())
    }

    @Test fun `model is app-private and imported never downloaded`() {
        val store = File(root(), "audio/silero/SileroModelStore.kt").readText()
        assertTrue(store.contains("java.io") || store.contains("File("))
        assertTrue(sileroFiles.none { f -> code(f).any { it.contains("http://") || it.contains("https://") || it.contains("DownloadManager") } })
    }
}
