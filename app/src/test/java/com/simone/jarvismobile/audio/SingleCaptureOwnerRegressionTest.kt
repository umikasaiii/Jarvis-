package com.simone.jarvismobile.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Live Voice Phase 0.7 — source-scan guard (plain JVM, same technique as
 * OnlineParameterRegressionTest) for the single-capture-owner rules:
 *  - exactly one `AudioRecord(` construction exists in app/src/main;
 *  - the canonical PCM capture is NOT consumed by the SpeechRecognizer wake-word / STT /
 *    translator / session paths yet (no dual capture next to SpeechRecognizer);
 *  - `captureContinuous(` has no production caller yet and the new capture path never
 *    touches audio focus / audio mode / the route manager session (MagicOS workaround).
 */
class SingleCaptureOwnerRegressionTest {

    private fun root(): File = listOf(
        File("src/main/java/com/simone/jarvismobile"),
        File("app/src/main/java/com/simone/jarvismobile"),
    ).firstOrNull { it.isDirectory }
        ?: error("cannot locate app source root from ${File(".").absolutePath}")

    private fun code(file: File): List<String> =
        file.readLines().filter { val t = it.trim(); !t.startsWith("*") && !t.startsWith("//") && !t.startsWith("/*") }

    private fun allKt() = root().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `exactly one AudioRecord construction in app main`() {
        val hits = allKt().flatMap { f -> code(f).filter { Regex("""(?<![A-Za-z.])AudioRecord\(""").containsMatchIn(it) }.map { f.name } }
        assertEquals(listOf("AudioRecordPcmSource.kt"), hits)
    }

    @Test
    fun `captureContinuous has no production caller yet`() {
        // Phase 0.8: the ONLY allowed caller is the manual, diagnostics-only Silero qualification service
        // (it proves the mic is free first; see SileroPhase08RegressionTest).
        val callers = allKt().filter { it.name !in setOf("AudioCapture.kt", "AndroidAudioCapture.kt", "SileroVadQualificationService.kt") }
            .filter { f -> code(f).any { it.contains("captureContinuous(") } }.map { it.name }
        assertTrue("unexpected captureContinuous callers: $callers", callers.isEmpty())
    }

    @Test
    fun `speech recognizer paths never consume the canonical PCM frames`() {
        val protectedFiles = setOf("AndroidOnDeviceSpeechEngine.kt", "WakeWordEngine.kt", "WakeWordController.kt", "LiveTranslatorManager.kt", "SessionCoordinator.kt")
        val offenders = allKt().filter { it.name in protectedFiles }
            .filter { f -> code(f).any { it.contains("audioCapture.frames") || it.contains("PcmCaptureEngine") || it.contains("captureSnapshot") } }
            .map { it.name }
        assertTrue("dual-capture risk in: $offenders", offenders.isEmpty())
    }

    @Test
    fun `new capture path never touches audio focus mode or route session`() {
        val files = allKt().filter { it.name in setOf("AudioRecordPcmSource.kt", "AndroidAudioCapture.kt") }
        assertEquals(2, files.size)
        val forbidden = listOf("beginSession", "requestAudioFocus", "setMode(", "setCommunicationDevice", "MODE_IN_COMMUNICATION", "AudioFocusGate")
        val offenders = files.flatMap { f -> code(f).filter { l -> forbidden.any { l.contains(it) } }.map { "${f.name}: ${it}" } }
        assertTrue("MagicOS workaround violated: $offenders", offenders.isEmpty())
    }
}
