package com.simone.jarvismobile.corebridge

import com.simone.jarvismobile.core.ai.JarvisCoreState
import com.simone.jarvismobile.core.bridge.EventPriority
import com.simone.jarvismobile.core.bridge.JarvisEvent
import com.simone.jarvismobile.core.bridge.JarvisEventType
import com.simone.jarvismobile.core.bridge.QueuedEvent
import com.simone.jarvismobile.core.tools.SensitivityLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the explicitly required Event Bridge scenarios: publishing/flushing
 * with Core offline vs online, and that a delivered event actually leaves
 * the queue while an undelivered one stays. [FakeEventQueue]/[FakeCoreClient]/
 * [FakeGate] keep this fully off the real file system / network — see
 * `EventQueueStore.kt`'s `EventQueue` interface and `EventBridgeGate` for why.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventBridgeTest {

    private fun event(id: String = "evt-1") = JarvisEvent(
        id = id,
        type = JarvisEventType.APP_STARTED,
        timestampMs = 1_000L,
        source = "test",
        priority = EventPriority.NORMAL,
        privacyLevel = SensitivityLevel.PUBLIC,
    )

    @Test
    fun `publish with core offline enqueues but never attempts delivery`() = runTest {
        val queue = FakeEventQueue()
        val client = FakeCoreClient()
        val bridge = EventBridge(FakeGate(enabled = true, state = JarvisCoreState.OFFLINE), queue, client, CoroutineScope(UnconfinedTestDispatcher()))

        bridge.publish(event())

        assertEquals(1, queue.enqueued.size)
        assertEquals(0, client.publishedIds.size)
        assertEquals(1, queue.contents.size) // still queued, nothing delivered
    }

    @Test
    fun `publish with core online still only enqueues - remote transport is disabled`() = runTest {
        // jarvis-protocol/main v1.0.0 defines no event-ingestion endpoint
        // (no POST /v1/events on real jarvis-core) — EventBridge.flushIfOnline()
        // is gated off at the transport level until the protocol defines one,
        // regardless of Core being reachable. Local queuing (this test) stays
        // fully intact; see EventBridge.REMOTE_TRANSPORT_ENABLED.
        val queue = FakeEventQueue()
        val client = FakeCoreClient(alwaysSucceeds = true)
        val bridge = EventBridge(FakeGate(enabled = true, state = JarvisCoreState.ONLINE), queue, client, CoroutineScope(UnconfinedTestDispatcher()))

        bridge.publish(event("evt-online"))

        assertTrue(client.publishedIds.isEmpty()) // never delivered - remote transport disabled
        assertEquals(1, queue.contents.size) // still queued, waiting for a real endpoint
    }

    @Test
    fun `event bridge disabled never touches the queue at all`() = runTest {
        val queue = FakeEventQueue()
        val client = FakeCoreClient()
        val bridge = EventBridge(FakeGate(enabled = false, state = JarvisCoreState.ONLINE), queue, client, CoroutineScope(UnconfinedTestDispatcher()))

        bridge.publish(event())

        assertEquals(0, queue.enqueued.size)
        assertEquals(0, client.publishedIds.size)
    }

    @Test
    fun `flush never attempts delivery while remote transport is disabled - everything stays queued`() = runTest {
        val queue = FakeEventQueue()
        queue.contents += QueuedEvent(event("ok"), enqueuedAtMs = 0L)
        queue.contents += QueuedEvent(event("fails"), enqueuedAtMs = 0L)
        val client = FakeCoreClient(succeedsFor = setOf("ok"))
        val bridge = EventBridge(FakeGate(enabled = true, state = JarvisCoreState.ONLINE), queue, client, CoroutineScope(UnconfinedTestDispatcher()))

        bridge.flushIfOnline()

        assertFalse(client.flushAttempted)
        assertTrue("ok" in queue.contents.map { it.event.id })
        assertTrue("fails" in queue.contents.map { it.event.id })
    }

    @Test
    fun `flush is a no-op when the queue is empty`() = runTest {
        val queue = FakeEventQueue()
        val client = FakeCoreClient(alwaysSucceeds = true)
        val bridge = EventBridge(FakeGate(enabled = true, state = JarvisCoreState.ONLINE), queue, client, CoroutineScope(UnconfinedTestDispatcher()))

        bridge.flushIfOnline()

        assertFalse(client.flushAttempted)
    }

    // --- fakes ---------------------------------------------------------------

    private class FakeGate(private val enabled: Boolean, private val state: JarvisCoreState) : EventBridgeGate {
        override suspend fun enabled(): Boolean = enabled
        override suspend fun coreState(): JarvisCoreState = state
    }

    private class FakeEventQueue : EventQueue {
        val enqueued = mutableListOf<JarvisEvent>()
        val contents = mutableListOf<QueuedEvent>()

        override suspend fun enqueue(event: JarvisEvent) {
            enqueued += event
            contents += QueuedEvent(event, enqueuedAtMs = 0L)
        }

        override suspend fun peekAll(): List<QueuedEvent> = contents.toList()

        override suspend fun removeDelivered(ids: Set<String>) {
            contents.removeAll { it.event.id in ids }
        }
    }

    private class FakeCoreClient(
        private val alwaysSucceeds: Boolean = false,
        private val succeedsFor: Set<String> = emptySet(),
    ) : CoreClient {
        val publishedIds = mutableListOf<String>()
        var flushAttempted = false

        override suspend fun healthCheck(): CoreHealthResult = CoreHealthResult(reachable = true)
        override suspend fun testConnection(): CoreConnectionTestResult = CoreConnectionTestResult(reachable = true)
        override suspend fun send(request: JarvisCoreRequest): JarvisCoreResponse =
            JarvisCoreResponse(requestId = request.requestId, status = CoreResponseStatus.OK)
        override fun stream(request: JarvisCoreRequest) = kotlinx.coroutines.flow.emptyFlow<JarvisCoreStreamChunk>()
        override fun cancel(requestId: String) = Unit
        override suspend fun describeEndpoint(): String = "http://fake:0"

        override suspend fun publishEvent(event: JarvisEvent): Boolean {
            flushAttempted = true
            val ok = alwaysSucceeds || event.id in succeedsFor
            if (ok) publishedIds += event.id
            return ok
        }
    }
}
