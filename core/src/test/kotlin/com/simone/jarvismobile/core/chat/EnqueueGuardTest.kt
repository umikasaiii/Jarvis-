package com.simone.jarvismobile.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnqueueGuardTest {
    @Test fun aDoubleTapOfTheSameMessageIsEnqueuedOnce() {
        val active = listOf("a" to "che ore sono?")
        assertEquals(EnqueueGuard.Decision.Duplicate("a"), EnqueueGuard.decide(active, "  che ore   sono? "))
    }

    @Test fun aDifferentMessageIsAcceptedEvenWhileOneIsRunning() {
        assertEquals(EnqueueGuard.Decision.Accept, EnqueueGuard.decide(listOf("a" to "ciao"), "e domani?"))
    }

    @Test fun sameTextAfterCompletionIsAccepted() {
        assertEquals(EnqueueGuard.Decision.Accept, EnqueueGuard.decide(emptyList(), "ciao"))
    }

    @Test fun emptyMessagesAreRejected() {
        assertEquals(EnqueueGuard.Decision.Empty, EnqueueGuard.decide(emptyList(), "   "))
    }

    @Test fun failureMessageIsActionableAndMentionsNoNetwork() {
        val m = EnqueueGuard.failureMessage("text_crash_IllegalStateException")
        assertTrue("Nuova conversazione" in m && "Modelli" in m)
        assertTrue("rete" !in m.lowercase())
    }
}
