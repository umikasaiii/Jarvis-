package com.simone.jarvismobile.core.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoicePcmFrameTest {
    @Test fun copiesAtBoundaryAndIsImmutableToConsumers() {
        val src = shortArrayOf(1, 2, 3, 4)
        val f = VoicePcmFrame.copyOf(src, 4, 16_000, 0)!!
        src[0] = 99
        assertEquals(1, f.sampleAt(0).toInt())
        val out = f.copyOfSamples(); out[1] = 77
        assertEquals(2, f.sampleAt(1).toInt())
    }

    @Test fun contractBoundsRejected() {
        val src = ShortArray(8)
        assertNull(VoicePcmFrame.copyOf(src, 0, 16_000, 0))
        assertNull(VoicePcmFrame.copyOf(src, 9, 16_000, 0))
        assertNull(VoicePcmFrame.copyOf(ShortArray(5000), 5000, 16_000, 0))
        assertNull(VoicePcmFrame.copyOf(src, 4, 0, 0))
        assertNull(VoicePcmFrame.copyOf(src, 4, 16_000, -1))
        assertNotNull(VoicePcmFrame.copyOf(src, 8, 16_000, 0))
    }

    @Test fun levelMatchesLegacyFormulaAndSilenceIsZero() {
        assertEquals(0f, VoicePcmFrame.copyOf(ShortArray(16), 16, 16_000, 0)!!.normalizedLevel())
        val loud = VoicePcmFrame.copyOf(ShortArray(16) { 20_000 }, 16, 16_000, 0)!!
        assertEquals(1f, loud.normalizedLevel())
        val mid = VoicePcmFrame.copyOf(ShortArray(16) { 2_048 }, 16, 16_000, 0)!!
        assertEquals(0.25f, mid.normalizedLevel(), 0.001f)
    }

    @Test fun toStringNeverExposesSamples() {
        val s = VoicePcmFrame.copyOf(shortArrayOf(12345, 23456), 2, 16_000, 7)!!.toString()
        assertFalse(s.contains("12345") || s.contains("23456"))
        assertTrue(s.contains("seq=7"))
    }

    @Test fun noTextOrDeviceFieldsInFrameOrSnapshotModels() {
        val names = (VoicePcmFrame::class.java.declaredFields + VoiceCaptureSnapshot::class.java.declaredFields)
            .map { it.type }
        assertTrue(names.none { it == String::class.java })
    }
}
