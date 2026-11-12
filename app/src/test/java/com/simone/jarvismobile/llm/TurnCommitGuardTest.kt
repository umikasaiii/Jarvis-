package com.simone.jarvismobile.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * HOTFIX 4 — structural guards (JVM, source-reading): the Android wiring around the JVM-tested
 * `TurnLedger`/`ChatTurnRunner` must stay the ONLY way a typed answer reaches the chat. TEST KIND: static guard;
 * it proves the code shape, not runtime behaviour on a device.
 */
class TurnCommitGuardTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "app/src/main/java").isDirectory || File(it, "src/main/java").isDirectory }
        .let { if (File(it, "app/src/main/java").isDirectory) File(it, "app/src/main/java") else File(it, "src/main/java") }

    private fun code(rel: String) = File(root, "com/simone/jarvismobile/$rel").readLines()
        .filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") || it.trim().startsWith("/*") }
        .joinToString("\n")

    private fun body(src: String, start: String, end: String) = src.substringAfter(start).substringBefore(end)

    @Test fun typedAnswersAreCommittedOnlyThroughTheTurnRunner() {
        val c = code("audio/SessionCoordinator.kt")
        val processText = body(c, "private suspend fun processText(", "private suspend fun focusedAnswer")
        assertTrue("turnRunner.run(" in processText)
        assertFalse("appendMessage(" in processText) // the runner's sink is the only writer for a typed turn
        assertFalse("sessionMutex.withLock" in processText) // the runner owns the lane
        assertTrue("ChatSink {" in c && "appendMessage(fromUser, text, taskId)" in c)
    }

    @Test fun stopAndNewConversationInvalidateTurnsBeforeTouchingState() {
        val c = code("audio/SessionCoordinator.kt")
        val stop = body(c, "fun cancelTextGeneration()", "fun cancelTurn(")
        assertTrue(stop.indexOf("turns.stopAll()") in 0 until stop.indexOf("router.cancel()"))
        val nc = body(c, "fun newConversation()", "chatWriter.submit")
        assertTrue(nc.indexOf("turns.newConversation()") in 0 until nc.indexOf("router.resetConversation()"))
        assertTrue("router.cancel()" in nc)
    }

    @Test fun chatPersistenceIsStrictlyOrdered() {
        val c = code("audio/SessionCoordinator.kt")
        val persist = body(c, "private fun persistChat()", "suspend fun restoreChat()")
        assertTrue("chatWriter.submit" in persist)
        assertFalse("scope.launch" in persist)
        val nc = body(c, "fun newConversation()", "private suspend fun speakOut(")
        assertTrue("chatWriter.submit" in nc)
        assertFalse("scope.launch" in nc)
    }

    @Test fun workerNeverRetriesOrNotifiesAStoppedResetOrSupersededTurn() {
        val w = code("background/AssistantTaskWorker.kt")
        assertTrue("is TurnResult.Discarded" in w)
        assertTrue("completeUnlessCancelled" in w)
        assertTrue("newerCompletedCount" in w)
        assertFalse("COMPLETED.name, 100" in w) // the unconditional completion is gone
        val discarded = body(w, "is TurnResult.Discarded", "null -> error")
        assertFalse("retry" in discarded.lowercase())
    }

    @Test fun stopCannotCancelAMessageSentAfterIt() {
        val q = code("background/AssistantTaskQueue.kt")
        assertTrue("cancelActive(cutoffMs" in q && "activeIdsUpTo(cutoffMs)" in q)
        assertFalse("cancelTextGeneration()" in q) // global stop would also hit newer turns; per-task cancelTurn is used
        val vm = code("ui/home/HomeViewModel.kt")
        assertTrue("cancelActive(cutoff)" in vm)
    }

    @Test fun diagnosticsOfATurnCarryNoContent() {
        val core = File(root, "../../../../core/src/main/kotlin/com/simone/jarvismobile/core/chat/TurnLedger.kt")
        assertTrue("core source must be found: ${core.path}", core.isFile)
        val s = core.readText()
        assertFalse("userText" in s || "answer:" in s)
        assertTrue("idHash" in s) // only a hash of the task id is logged
    }
}
