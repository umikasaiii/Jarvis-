package com.simone.jarvismobile.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Phase 0.9 source guards: one AudioTrack owner, platform TTS never claims a PCM reference, no AEC/consumer wiring. */
class FarEndReferenceRegressionTest {
    private val main = listOf("src/main/java", "app/src/main/java").map(::File).first { it.isDirectory }
    private fun src(rel: String) = File(main, "com/simone/jarvismobile/$rel").readText()

    @Test fun onlyPcmPlayerBuildsAnAudioTrack() {
        val hits = main.walkTopDown().filter { it.extension == "kt" && it.readText().contains("AudioTrack.Builder()") }.map { it.name }.toList()
        assertEquals(listOf("PcmPlayer.kt"), hits)
    }

    @Test fun platformTtsNeverClaimsAvailablePcm() {
        val s = src("audio/AndroidOfflineTtsEngine.kt")
        assertTrue(s.contains("UNAVAILABLE_PLATFORM_TTS"))
        assertFalse(s.contains("AVAILABLE_PCM"))
        assertFalse(s.contains("AudioPlaybackCapture") || s.contains("MediaProjection"))
    }

    @Test fun farEndReferenceIsNotWiredIntoProductionBehavior() {
        val coordinator = src("audio/SessionCoordinator.kt")
        assertFalse(coordinator.contains("farEnd"))
        val player = src("tts/PcmPlayer.kt")
        assertFalse(player.contains("VoiceActivity") || player.contains("Silero"))
    }
}
