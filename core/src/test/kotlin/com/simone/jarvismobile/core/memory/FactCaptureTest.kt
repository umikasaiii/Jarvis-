package com.simone.jarvismobile.core.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class FactCaptureTest {

    @Test fun capturesName() {
        val c = FactCapture.detect("Mi chiamo Marco")
        assertNotNull(c)
        assertEquals("Mi chiamo Marco", c.fact)
        assertEquals(MemoryKind.PERMANENT, c.kind)
    }

    @Test fun capturesHomeAndTaste() {
        assertNotNull(FactCapture.detect("Abito a Milano"))
        assertNotNull(FactCapture.detect("Preferisco il caffè senza zucchero"))
        assertNotNull(FactCapture.detect("Non mi piace il coriandolo"))
    }

    @Test fun healthFactIsSensitive() {
        val c = FactCapture.detect("Sono allergico alle noci")
        assertNotNull(c)
        assertEquals(MemoryKind.SENSITIVE, c.kind)
    }

    @Test fun ignoresQuestions() {
        assertNull(FactCapture.detect("Mi chiamo così?"))
        assertNull(FactCapture.detect("Cosa mi consigli per cena?"))
        assertNull(FactCapture.detect("Sono allergico alle noci, cosa posso mangiare?"))
    }

    @Test fun ignoresCommands() {
        assertNull(FactCapture.detect("Ricorda che devo comprare il latte"))
        assertNull(FactCapture.detect("Accendi la torcia"))
        assertNull(FactCapture.detect("Dimmi che ore sono"))
    }

    @Test fun ignoresPlainChatter() {
        assertNull(FactCapture.detect("Oggi sono stanco"))
        assertNull(FactCapture.detect("Va bene grazie"))
        assertNull(FactCapture.detect("Che bella giornata"))
    }

    @Test fun neverCapturesSecrets() {
        assertNull(FactCapture.detect("La mia password è ciao1234"))
    }

    @Test fun respectsTemporaryMarker() {
        assertNull(FactCapture.detect("Solo per questa conversazione preferisco il tè"))
    }
}
