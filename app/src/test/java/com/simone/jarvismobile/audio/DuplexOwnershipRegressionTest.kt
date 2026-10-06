package com.simone.jarvismobile.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * LV-R1 — source-scan guards (plain JVM, same technique as SingleCaptureOwnerRegressionTest) for the
 * duplex ownership rules: one capture owner, no SpeechRecognizer overlap, AudioRecord released before
 * the recognizer can start, AEC mode never stacks the platform communication source, JNI contract intact.
 */
class DuplexOwnershipRegressionTest {

    private fun root(): File = listOf(File("src/main/java/com/simone/jarvismobile"), File("app/src/main/java/com/simone/jarvismobile"))
        .firstOrNull { it.isDirectory } ?: error("cannot locate app source root from ${File(".").absolutePath}")

    private fun repoRoot(): File = listOf(File("../native/aec3"), File("native/aec3")).firstOrNull { it.isDirectory }?.parentFile?.parentFile
        ?: error("cannot locate repo root")

    private fun code(file: File): List<String> =
        file.readLines().filter { val t = it.trim(); !t.startsWith("*") && !t.startsWith("//") && !t.startsWith("/*") }

    private fun allKt() = root().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    private fun file(name: String) = allKt().first { it.name == name }

    @Test
    fun `duplex controller never creates a recorder player or speech recognizer`() {
        val lines = code(file("DuplexMonitorController.kt"))
        val forbidden = listOf("AudioRecord(", "AudioTrack(", "SpeechRecognizer", "createSpeechRecognizer", "requestAudioFocus", "setCommunicationDevice", "setMode(")
        val offenders = lines.filter { l -> forbidden.any { l.contains(it) } }
        assertTrue("duplex controller must only coordinate: $offenders", offenders.isEmpty())
    }

    @Test
    fun `speech recognizer wake word and translator never reference the duplex path`() {
        val offenders = allKt().filter { it.name in setOf("AndroidOnDeviceSpeechEngine.kt", "WakeWordEngine.kt", "LiveTranslatorManager.kt") }
            .filter { f -> code(f).any { it.contains("duplex", ignoreCase = true) || it.contains("NativeAec3") } }.map { it.name }
        assertTrue("overlap risk in: $offenders", offenders.isEmpty())
    }

    @Test
    fun `speakOut joins the duplex job under NonCancellable before returning`() {
        val text = code(file("SessionCoordinator.kt")).joinToString("\n")
        assertTrue(text.contains("duplex.runDuringSpeech(tts)"))
        assertTrue("the AudioRecord must be released before the follow-up STT can start", Regex("""NonCancellable\)\s*\{\s*duplexJob\.cancelAndJoin\(\)""").containsMatchIn(text))
    }

    @Test
    fun `acoustic interrupt is private and only reachable as the controller callback`() {
        val lines = code(file("SessionCoordinator.kt"))
        assertTrue(lines.any { it.contains("private fun acousticInterrupt()") })
        val uses = lines.filter { it.contains("acousticInterrupt()") && !it.contains("fun acousticInterrupt") }
        assertEquals(1, uses.size)
        assertTrue(uses.single().contains("runDuringSpeech"))
    }

    @Test
    fun `controller stops TTS only after a verified release`() {
        val text = code(file("DuplexMonitorController.kt")).joinToString("\n")
        val release = text.indexOf("stopAndAwaitRelease()")
        val callback = text.indexOf("onConfirmed()")
        assertTrue(release in 0 until callback)
        assertTrue(text.contains("if (release == DuplexReleaseResult.RELEASED) onConfirmed()"))
    }

    @Test
    fun `acoustic barge in is default off`() {
        val text = code(file("SettingsRepository.kt")).joinToString("\n")
        assertTrue(Regex("""ACOUSTIC_BARGE_IN\]\s*\?:\s*false""").containsMatchIn(text))
    }

    @Test
    fun `AEC mode skips the platform communication source while STANDARD keeps the legacy order`() {
        val text = code(file("AudioRecordPcmSource.kt")).joinToString("\n")
        val raw = text.substringAfter("PcmCaptureMode.ECHO_CONTROLLED_RAW -> intArrayOf(").substringBefore(")")
        assertFalse(raw.contains("VOICE_COMMUNICATION"))
        assertTrue(raw.contains("AudioSource.MIC"))
        val std = text.substringAfter("PcmCaptureMode.STANDARD -> intArrayOf(").substringBefore(")")
        assertTrue(std.indexOf("VOICE_COMMUNICATION") < std.indexOf("AudioSource.MIC"))
    }

    @Test
    fun `exactly one System loadLibrary and the JNI symbols match the Kotlin class`() {
        val loads = allKt().flatMap { f -> code(f).filter { it.contains("System.loadLibrary(") }.map { f.name } }
        assertEquals(listOf("NativeAec3.kt"), loads)
        val cpp = File(repoRoot(), "native/aec3/jni/jarvis_aec3.cpp").readText()
        val kt = file("NativeAec3.kt").readText()
        for (fn in listOf("nativeCreate", "nativeProcessReverse", "nativeProcessNear", "nativeDestroy")) {
            assertTrue("$fn missing in cpp", cpp.contains("Java_com_simone_jarvismobile_voice_aec_NativeAec3_$fn"))
            assertTrue("$fn missing in Kotlin", kt.contains("fun $fn("))
        }
        assertTrue(kt.contains("package com.simone.jarvismobile.voice.aec"))
        val map = File(repoRoot(), "native/aec3/jni/exports.map").readText()
        assertTrue(map.contains("Java_com_simone_jarvismobile_voice_aec_NativeAec3_*"))
    }

    @Test
    fun `AEC build never substitutes the platform echo canceller or the full libwebrtc`() {
        val all = allKt().flatMap { code(it) }.joinToString("\n")
        assertFalse(all.contains("AcousticEchoCanceler"))
        assertFalse(all.contains("org.webrtc"))
        val gradle = File(repoRoot(), "app/build.gradle.kts").readText()
        assertFalse(gradle.contains("webrtc-sdk"))
        assertFalse(gradle.contains("google-webrtc"))
    }

    @Test
    fun `provenance pins the official source identity`() {
        val p = File(repoRoot(), "native/aec3/PROVENANCE.json").readText()
        assertTrue(p.contains("35e86b986d02ea15f3d04741a1a5a735ba399bc0fac0ee089c39480e35fc3253"))
        assertTrue(p.contains("846fe90a289f58b7c9303a635142aa2c7caa93e5"))
        val fetch = File(repoRoot(), "native/aec3/fetch_source.sh").readText()
        assertTrue(fetch.contains("35e86b986d02ea15f3d04741a1a5a735ba399bc0fac0ee089c39480e35fc3253"))
        assertFalse("no unofficial mirror", fetch.contains("github.com"))
    }
}
