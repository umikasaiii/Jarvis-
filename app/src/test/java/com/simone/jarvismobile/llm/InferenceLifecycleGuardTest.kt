package com.simone.jarvismobile.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * HOTFIX 3 — structural guards (JVM, source-reading, no Android/model): the wiring that the JVM-tested core
 * [com.simone.jarvismobile.core.llm.InferenceSlot] relies on must stay in place in the Android layer.
 * TEST KIND: static guard. It proves the code shape, not runtime behaviour on a device.
 */
class InferenceLifecycleGuardTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "app/src/main/java").isDirectory || File(it, "src/main/java").isDirectory }
        .let { if (File(it, "app/src/main/java").isDirectory) File(it, "app/src/main/java") else File(it, "src/main/java") }

    private fun src(rel: String) = File(root, "com/simone/jarvismobile/$rel").readText()

    private fun code(s: String) = s.lineSequence().filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") || it.trim().startsWith("/*") }.joinToString("\n")

    @Test fun engineNeverClosesNativeResourcesDirectly() {
        val e = code(src("llm/LitertLmEngine.kt"))
        // The only native close calls live inside the adapter classes the slot drives (after native returned).
        assertFalse("conversation?.close()" in e)
        assertFalse("chatMutex" in e)
        assertFalse("engine?.close()" in e)
        assertTrue("slot?.reset(" in e)
        assertTrue("old?.shutdown()" in e || "old.shutdown()" in e)
    }

    @Test fun everyGenerationEntryPointGoesThroughTheSlot() {
        val e = code(src("llm/LitertLmEngine.kt"))
        assertTrue("it.once(prompt" in e) // generate()
        assertTrue("it.chat(userText" in e) // chat()
        assertTrue("it.once(userText" in e) // chatStateless()
        assertFalse("createConversation" in e.substringBefore("private class LiteRtNativeEngine"))
    }

    @Test fun wedgedEngineIsReplacedNotAwaitedForever() {
        val e = code(src("llm/LitertLmEngine.kt"))
        assertTrue("onWedged = " in e)
        assertTrue("recoverFromWedge" in e)
        assertTrue("force = true" in e)
    }

    @Test fun newConversationAbandonsTheAnswerInFlight() {
        val vm = code(src("ui/home/HomeViewModel.kt"))
        val body = vm.substringAfter("fun onNewConversation()").substringBefore("fun onSendText")
        assertTrue("coordinator.cancelTextGeneration()" in body)
        assertTrue("taskQueue.cancelActive(" in body)
        assertTrue(body.indexOf("cancelTextGeneration") < body.indexOf("coordinator.newConversation()"))
    }

    @Test fun orphanedQueueRowsDoNotBlockNewerMessages() {
        assertTrue("updatedAt >= :staleBefore" in src("background/AssistantTaskDao.kt"))
        assertTrue("QueueLiveness.staleBefore" in src("background/AssistantTaskWorker.kt"))
    }

    @Test fun diagnosticsCarryNoConversationContent() {
        val d = code(src("llm/InferenceDiagnostics.kt"))
        // The recorder API has no parameter that could carry prompt/reply text.
        assertFalse("prompt" in d.lowercase().replace("// ", ""))
        assertFalse("userText" in d)
        assertTrue("PREVIOUS_EXIT" in d && "boot_count" in d)
    }

    @Test fun workerStaysADurableForegroundTaskWithoutWakeLocks() {
        val w = code(src("background/AssistantTaskWorker.kt"))
        assertTrue("setForeground(" in w)
        assertFalse("WakeLock" in w || "newWakeLock" in w)
        assertFalse("WAKE_LOCK" in code(File(root, "../AndroidManifest.xml").readText()))
    }
}
