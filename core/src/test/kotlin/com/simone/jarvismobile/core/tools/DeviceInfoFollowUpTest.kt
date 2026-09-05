package com.simone.jarvismobile.core.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * § FASE 2A.8 RELEASE GATE A/C — pins the exact real-device scenario: "Che
 * differenza c'è tra RAM e VRAM?" then "Quanta ne ho nel telefono?" must
 * resolve to RAM (answerable), never silently guess VRAM (not answerable).
 */
class DeviceInfoFollowUpTest {

    @Test
    fun `a knowledge question mentioning both RAM and ROM still prefers ram`() {
        assertEquals("ram", DeviceInfoFollowUp.extractTopic("Cosa cambia tra RAM e ROM?"))
    }

    @Test
    fun `when both RAM and VRAM are mentioned, ram is preferred - the one DeviceInfo can actually answer`() {
        val topic = DeviceInfoFollowUp.extractTopic("Che differenza c'è tra RAM e VRAM?")
        assertEquals("ram", topic)
    }

    @Test
    fun `a knowledge question about neither RAM nor any known metric extracts no topic`() {
        assertNull(DeviceInfoFollowUp.extractTopic("Chi ha inventato il telefono?"))
    }

    @Test
    fun `ram resolves to a real, answerable DeviceInfo metric`() {
        assertEquals("ram", DeviceInfoFollowUp.resolveDeviceInfoMetric("ram"))
    }

    @Test
    fun `vram resolves to nothing - Android exposes no reliable value, never silently substituted with ram`() {
        assertNull(DeviceInfoFollowUp.resolveDeviceInfoMetric("vram"))
    }

    @Test
    fun `storage and rom both resolve to the same real storage metric`() {
        assertEquals("storage", DeviceInfoFollowUp.resolveDeviceInfoMetric("storage"))
        assertEquals("storage", DeviceInfoFollowUp.resolveDeviceInfoMetric("rom"))
    }

    @Test
    fun `quanta ne ho is recognized as a bare partitive follow-up`() {
        assertTrue(DeviceInfoFollowUp.looksLikePartitiveFollowUp("Quanta ne ho nel telefono?"))
        assertTrue(DeviceInfoFollowUp.looksLikePartitiveFollowUp("Quanto ne ho?"))
    }

    @Test
    fun `a question naming its own metric is not a bare partitive follow-up`() {
        assertFalse(DeviceInfoFollowUp.looksLikePartitiveFollowUp("Quanta RAM ho nel telefono?"))
    }

    @Test
    fun `ordinary conversation is never mistaken for a partitive follow-up`() {
        assertFalse(DeviceInfoFollowUp.looksLikePartitiveFollowUp("Ciao, come stai?"))
    }
}
