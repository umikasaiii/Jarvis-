package com.simone.jarvismobile.ui.components.segnale

import com.simone.jarvismobile.core.segnale.DataStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 12 §V.7/§V.17.
 * `DataStatus.toPresentation()` is the presentation mapper §I requires —
 * these tests pin that it stays a pure function of the already-typed
 * `DataStatus` (never a keyword/string guess, §I) and carries no domain
 * payload (§V.7: message/agenda/health content has no field to travel
 * through here). Written, CI-verified only (no Android SDK in this
 * sandbox) — same convention as [com.simone.jarvismobile.ui.theme.SegnaleTokensTest].
 */
class DataStatusViewTest {

    @Test
    fun `every DataStatus produces a distinct, non-blank title`() {
        val titles = DataStatus.entries.map { it.toPresentation().title }
        titles.forEach { assertFalse(it.isBlank()) }
        assertEquals(titles.size, titles.toSet().size)
    }

    @Test
    fun `SUCCESS_DATA and SUCCESS_EMPTY have distinct accessibility descriptions`() {
        val data = DataStatus.SUCCESS_DATA.toPresentation()
        val empty = DataStatus.SUCCESS_EMPTY.toPresentation()
        assertNotEquals(data.accessibilityDescription, empty.accessibilityDescription)
    }

    @Test
    fun `accessibility description names the STATE, never a visual shape`() {
        // §H's own example: "Avvia conversazione" (good) vs. "Pulsante
        // cerchio rosso" (bad) — none of these strings may describe a
        // color/shape word instead of what happened.
        val shapeWords = listOf("cerchio", "quadrato", "rosso", "verde", "blu", "icona")
        DataStatus.entries.forEach { status ->
            val description = status.toPresentation().accessibilityDescription.lowercase()
            shapeWords.forEach { word ->
                assertFalse("status=$status description=$description contains shape word '$word'", description.contains(word))
            }
        }
    }

    @Test
    fun `every presentation carries no field beyond icon, title, accent and description`() {
        // Structural guarantee: DataStatusPresentation is a fixed 4-field
        // data class (icon/title/accent/accessibilityDescription) — there is
        // no field through which agenda/health/message content could be
        // attached. Verified by reflection over the data class components.
        val presentation = DataStatus.SUCCESS_DATA.toPresentation()
        val fieldCount = presentation::class.java.declaredFields.count { !it.isSynthetic }
        assertTrue("expected exactly 4 declared fields, found $fieldCount", fieldCount == 4)
    }

    @Test
    fun `PERMISSION_MISSING and DATA_UNAVAILABLE map to distinct presentations`() {
        val permissionMissing = DataStatus.PERMISSION_MISSING.toPresentation()
        val dataUnavailable = DataStatus.DATA_UNAVAILABLE.toPresentation()
        assertNotEquals(permissionMissing.title, dataUnavailable.title)
        assertNotEquals(permissionMissing.accessibilityDescription, dataUnavailable.accessibilityDescription)
    }
}
