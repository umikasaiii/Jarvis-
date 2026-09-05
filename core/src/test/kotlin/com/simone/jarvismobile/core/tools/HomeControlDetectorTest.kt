package com.simone.jarvismobile.core.tools

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * § FASE 2A.6 §2/§9 — "luce della camera" must never be mistaken for
 * "torcia del telefono": these two are meant to never overlap.
 */
class HomeControlDetectorTest {

    @Test
    fun `room lighting commands are recognized`() {
        assertTrue(HomeControlDetector.looksLikeUnsupportedHomeControl("Accendi la luce della camera"))
        assertTrue(HomeControlDetector.looksLikeUnsupportedHomeControl("Spegni la luce del salotto"))
        assertTrue(HomeControlDetector.looksLikeUnsupportedHomeControl("Accendi la lampada in cucina"))
    }

    @Test
    fun `climate and shutter commands are recognized`() {
        assertTrue(HomeControlDetector.looksLikeUnsupportedHomeControl("Alza il termostato"))
        assertTrue(HomeControlDetector.looksLikeUnsupportedHomeControl("Abbassa le tapparelle"))
        assertTrue(HomeControlDetector.looksLikeUnsupportedHomeControl("Chiudi il garage"))
    }

    @Test
    fun `phone flashlight commands are never flagged as home control`() {
        assertFalse(HomeControlDetector.looksLikeUnsupportedHomeControl("Accendi la torcia"))
        assertFalse(HomeControlDetector.looksLikeUnsupportedHomeControl("Spegni il flash"))
    }

    @Test
    fun `ordinary conversation is never flagged`() {
        assertFalse(HomeControlDetector.looksLikeUnsupportedHomeControl("Ciao, come stai?"))
        assertFalse(HomeControlDetector.looksLikeUnsupportedHomeControl("Che tempo fa domani?"))
    }
}
