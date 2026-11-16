package com.simone.jarvismobile.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * M0 — source-level guards (JVM) that the publication gate is CONNECTED on every covered path of [SessionCoordinator]. They prove
 * wiring, not behaviour: the behavioural proofs run in `:core` against the real `InferenceSlot` (native-boundary budget) and the
 * real policy/orchestrator. A real SessionCoordinator test needs the Hilt graph and an Android runtime and is NOT claimed here.
 */
class PublicationWiringGuardTest {
    private val root: File = listOf(File("src/main/java/com/simone/jarvismobile"), File("app/src/main/java/com/simone/jarvismobile"))
        .firstOrNull { it.isDirectory } ?: error("app sources not found from ${File(".").absolutePath}")
    private val c = File(root, "audio/SessionCoordinator.kt").readText()

    private fun body(from: String, to: String) = c.substringAfter(from).substringBefore(to)

    @Test fun chatTurnIsPublishedOnlyThroughTheGate() {
        val chat = body("private suspend fun chatReply(", "private class ChatPublicationContext")
        assertTrue("publishChatTurn(" in chat)
        assertFalse("GroundingVerifier.verify" in c) // the old "answer + trailing note" verdict path is gone
        assertTrue("AttemptLedger(nativeCounting = true)" in chat)
        assertTrue("withContext(AttemptBudget(attemptLedger)" in chat) // + the per-request TokenLedgerContext (M3)
        assertTrue("retrievalFailed" in chat) // a read error is tracked apart from "no result"
    }

    @Test fun oneOrchestratorAndNoSecondVerifier() {
        val pub = body("private suspend fun publishChatTurn", "private fun uncoveredNote")
        assertTrue("PublicationOrchestrator(" in pub)
        assertTrue("decideToolOnly(" in pub) // tool receipts restated by the model are checked, not trusted
        assertTrue("RetrievalState.ERROR" in pub)
        assertTrue("invalidateSlot(" in pub)
        val gen = body("private suspend fun generateAndPublish", "private fun plainFooter")
        assertTrue("PublicationOrchestrator(" in gen && "AttemptBudget(ledger)" in gen)
    }

    @Test fun focusedAndDocumentPathsUseTheSameHelper() {
        assertTrue("generateAndPublish(" in body("private suspend fun focusedAnswer", "private fun fillSlot"))
        assertTrue("generateAndPublish(" in body("private suspend fun documentAnswer", "private suspend fun classicAnswer"))
    }

    @Test fun knownExcludedPathsAreStillTheOnesDocumented() {
        // tryRemoteChat is the one chat path that bypasses the gate; the docs must keep saying so until M3 changes it
        val docs = listOf(File("docs/RELIABILITY.md"), File("../docs/RELIABILITY.md")).first { it.isFile }.readText()
        assertTrue("tryRemoteChat" in docs)
    }
}
