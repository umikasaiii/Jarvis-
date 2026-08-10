package com.simone.jarvismobile.core.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class MemoryTextTest {

    @Test fun inflectionsCollapseToTheSameStem() {
        assertEquals(MemoryText.normalize("gatto"), MemoryText.normalize("gatti"))
        assertEquals(MemoryText.normalize("gatto"), MemoryText.normalize("gatta"))
        assertEquals(MemoryText.normalize("preferisco"), MemoryText.normalize("preferisci"))
    }

    @Test fun synonymsShareAConcept() {
        assertEquals(MemoryText.normalize("macchina"), MemoryText.normalize("automobile"))
        assertEquals(MemoryText.normalize("casa"), MemoryText.normalize("abitazione"))
        assertEquals(MemoryText.normalize("allergico"), MemoryText.normalize("allergie"))
        assertEquals(MemoryText.normalize("lavoro"), MemoryText.normalize("professione"))
        assertEquals(MemoryText.normalize("moglie"), MemoryText.normalize("marito"))
    }

    @Test fun unrelatedWordsStayApart() {
        assertNotEquals(MemoryText.normalize("casa"), MemoryText.normalize("cassa"))
        assertNotEquals(MemoryText.normalize("gatto"), MemoryText.normalize("cane"))
    }

    @Test fun shortWordsAreLeftAlone() {
        assertEquals("ram", MemoryText.normalize("ram"))
        assertEquals("pc", MemoryText.normalize("pc"))
    }
}
