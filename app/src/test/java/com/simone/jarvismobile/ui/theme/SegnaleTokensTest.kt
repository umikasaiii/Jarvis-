package com.simone.jarvismobile.ui.theme

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.TextUnitType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 12 §V.1/§V.14-16. Plain
 * JVM tests — [androidx.compose.ui.graphics.Color]/[androidx.compose.ui.unit.Dp]
 * are pure Kotlin value types with no Android framework dependency, so
 * these run under `:app:testDebugUnitTest` without Robolectric — but this
 * whole module still cannot compile in this sandbox (no Android SDK), so
 * this file is written and CI-verified only, same convention as every other
 * `app/` test in this project (`OnlineParameterRegressionTest`,
 * `RemoteAiEngineTest`, etc.).
 */
class SegnaleTokensTest {

    @Test
    fun `SEGNALE color tokens match the exact specified hex values`() {
        assertEquals(0xFF070A0E.toInt(), SegnaleColors.background.toArgb())
        assertEquals(0xFF10161C.toInt(), SegnaleColors.surface.toArgb())
        assertEquals(0xFF192129.toInt(), SegnaleColors.elevated.toArgb())
        assertEquals(0xFF36414B.toInt(), SegnaleColors.borderInactive.toArgb())
        assertEquals(0xFF697884.toInt(), SegnaleColors.borderControl.toArgb())
        assertEquals(0xFFF04452.toInt(), SegnaleColors.borderActive.toArgb())
        assertEquals(0xFFB9273A.toInt(), SegnaleColors.redDeep.toArgb())
        assertEquals(0xFFFF4B55.toInt(), SegnaleColors.energyRed.toArgb())
        assertEquals(0xFF5A1622.toInt(), SegnaleColors.ambientLight.toArgb())
        assertEquals(0xFFEAB866.toInt(), SegnaleColors.warning.toArgb())
        assertEquals(0xFF78CCAD.toInt(), SegnaleColors.success.toArgb())
        assertEquals(0xFFFF9D93.toInt(), SegnaleColors.error.toArgb())
        assertEquals(0xFF88BFE7.toInt(), SegnaleColors.remote.toArgb())
        assertEquals(0xFFF3F5F7.toInt(), SegnaleColors.textPrimary.toArgb())
        assertEquals(0xFFACB7C2.toInt(), SegnaleColors.textSecondary.toArgb())
        assertEquals(0xFF8995A2.toInt(), SegnaleColors.textMuted.toArgb())
        assertEquals(0xFF070A0E.toInt(), SegnaleColors.onEnergy.toArgb())
    }

    @Test
    fun `minimum interaction size constant is at least 48dp`() {
        assertTrue(SegnaleTouchTarget.standard.value >= 48f)
    }

    @Test
    fun `primary action target is at least 56dp`() {
        assertTrue(SegnaleTouchTarget.primary.value >= 56f)
    }

    @Test
    fun `driving interaction target is at least 64dp`() {
        assertTrue(SegnaleTouchTarget.driving.value >= 64f)
    }

    @Test
    fun `touch targets are ordered standard less than primary less than driving`() {
        assertTrue(SegnaleTouchTarget.standard.value < SegnaleTouchTarget.primary.value)
        assertTrue(SegnaleTouchTarget.primary.value < SegnaleTouchTarget.driving.value)
    }

    @Test
    fun `typography roles use sp units so Android font scaling applies`() {
        // A regression here would mean someone hardcoded a fixed dp size for
        // text, breaking §E's 1.0x/1.3x/2.0x font-scale acceptance — sp
        // values scale with the user setting, dp does not.
        assertEquals(TextUnitType.Sp, SegnaleTypography.body.fontSize.type)
        assertEquals(TextUnitType.Sp, SegnaleTypography.title.fontSize.type)
        assertEquals(TextUnitType.Sp, SegnaleTypography.caption.fontSize.type)
    }
}
