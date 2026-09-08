package com.simone.jarvismobile.corebridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * § JARVIS Implementation Master Plan PASSAGGIO 11 §F1 — Event Bridge is
 * architecturally DEFERRED: `jarvis-protocol` defines no event-ingestion
 * endpoint, so nothing on Core's side can consume what this module
 * produces yet ([EVENT_BRIDGE_REMOTE_TRANSPORT_ENABLED] stays `false`).
 * This checkpoint removed Event Bridge's periodic retry-flush job and its
 * `APP_STARTED` producer from `JarvisApplication`'s normal startup path —
 * a plain file-content scan (no Android/Robolectric needed) pins both
 * halves of that fix so a future edit cannot silently re-add either
 * without deliberately doing so.
 */
class EventBridgeStartupRegressionTest {

    @Test
    fun `JarvisApplication onCreate no longer calls eventBridgeScheduler sync or eventBridge publish`() {
        val text = jarvisApplicationSource().readText()

        assertFalse(
            "JarvisApplication calls eventBridgeScheduler.sync() again — Event Bridge is deferred " +
                "(no concrete consumer, jarvis-protocol has no event-ingestion endpoint); booking its " +
                "periodic retry-flush job from normal startup was the exact resource-for-nothing this " +
                "checkpoint removed. If a real consumer now exists, this test should be removed deliberately.",
            text.contains("eventBridgeScheduler.sync()"),
        )
        assertFalse(
            "JarvisApplication calls eventBridge.publish(...) again from normal startup — same reasoning.",
            text.contains("eventBridge.publish("),
        )
    }

    @Test
    fun `EventBridgeScheduler still guards its periodic job on remote transport being enabled`() {
        val text = eventBridgeSchedulerSource().readText()
        assertTrue(
            "EventBridgeScheduler.sync() should still refuse to book its periodic job while remote " +
                "transport is disabled, regardless of which caller invokes it.",
            text.contains("EVENT_BRIDGE_REMOTE_TRANSPORT_ENABLED"),
        )
    }

    private fun jarvisApplicationSource(): File = resolve("src/main/java/com/simone/jarvismobile/JarvisApplication.kt")
    private fun eventBridgeSchedulerSource(): File =
        resolve("src/main/java/com/simone/jarvismobile/corebridge/EventBridgeScheduler.kt")

    private fun resolve(relative: String): File {
        val candidates = listOf(File(relative), File("app/$relative"))
        val found = candidates.firstOrNull { it.isFile }
        checkNotNull(found) {
            "Could not locate $relative from working directory ${File(".").absolutePath} — " +
                "this test cannot silently pass without reading the real file."
        }
        return found
    }
}
