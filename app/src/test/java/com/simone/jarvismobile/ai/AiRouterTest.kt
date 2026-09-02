package com.simone.jarvismobile.ai

import com.simone.jarvismobile.core.ai.AiExecutionTarget
import com.simone.jarvismobile.core.ai.AiFailureReason
import com.simone.jarvismobile.core.ai.AiRequestType
import com.simone.jarvismobile.core.ai.AiRoutingPreferences
import com.simone.jarvismobile.core.ai.JarvisCoreState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the exact scenarios explicitly required for the AI Router phase:
 * Core disabled → LocalAiEngine; Core online → remote routing; Core offline
 * → local fallback; remote timeout → local fallback; no double response
 * after fallback; and request cancellation. [FakeAiEngine]/[FakeRoutingContext]
 * make [AiRouter] fully testable without touching Android `Context` at all
 * (§ `AiEngineQualifiers.kt`, `AiRoutingContextProvider.kt`).
 */
class AiRouterTest {

    private fun request(type: AiRequestType = AiRequestType.CHAT, id: String = "req-1") = AiRequest(
        requestId = id,
        text = "ciao",
        systemPrompt = "sys",
        requestType = type,
    )

    @Test
    fun `core disabled always routes to local`() = runTest {
        val local = FakeAiEngine(AiExecutionTarget.LOCAL, resultText = "risposta locale")
        val remote = FakeAiEngine(AiExecutionTarget.REMOTE_FAST, resultText = "non dovrebbe mai arrivare")
        val router = AiRouter(local, remote, FakeRoutingContext(remoteAiEnabled = false, coreState = JarvisCoreState.DISABLED), emptySnapshotCache(), FakeSnapshotGate())

        val result = router.generate(request())

        assertEquals("risposta locale", result.text)
        assertEquals(AiExecutionTarget.LOCAL, result.target)
        assertEquals(1, local.callCount)
        assertEquals(0, remote.callCount)
    }

    @Test
    fun `core online routes chat to remote fast`() = runTest {
        val local = FakeAiEngine(AiExecutionTarget.LOCAL, resultText = "locale")
        val remote = FakeAiEngine(AiExecutionTarget.REMOTE_FAST, resultText = "risposta remota")
        val router = AiRouter(local, remote, FakeRoutingContext(remoteAiEnabled = true, coreState = JarvisCoreState.ONLINE), emptySnapshotCache(), FakeSnapshotGate())

        val result = router.generate(request(AiRequestType.CHAT))

        assertEquals("risposta remota", result.text)
        assertEquals(AiExecutionTarget.REMOTE_FAST, result.target)
        assertFalse(result.wasFallback)
        assertEquals(1, remote.callCount)
        assertEquals(0, local.callCount)
    }

    @Test
    fun `core offline never even attempts remote, goes straight to local`() = runTest {
        val local = FakeAiEngine(AiExecutionTarget.LOCAL, resultText = "locale")
        val remote = FakeAiEngine(AiExecutionTarget.REMOTE_FAST, resultText = "remota")
        val router = AiRouter(local, remote, FakeRoutingContext(remoteAiEnabled = true, coreState = JarvisCoreState.OFFLINE), emptySnapshotCache(), FakeSnapshotGate())

        val result = router.generate(request())

        assertEquals(AiExecutionTarget.LOCAL, result.target)
        assertEquals(0, remote.callCount)
        assertEquals(1, local.callCount)
    }

    @Test
    fun `remote timeout falls back to local within the same call`() = runTest {
        val local = FakeAiEngine(AiExecutionTarget.LOCAL, resultText = "risposta locale di riserva")
        val remote = FakeAiEngine(
            AiExecutionTarget.REMOTE_FAST,
            result = { AiEngineResult(it.requestId, success = false, target = AiExecutionTarget.REMOTE_FAST, failureReason = AiFailureReason.TIMEOUT) },
        )
        val router = AiRouter(local, remote, FakeRoutingContext(remoteAiEnabled = true, coreState = JarvisCoreState.ONLINE), emptySnapshotCache(), FakeSnapshotGate())

        val result = router.generate(request())

        assertTrue(result.success)
        assertTrue(result.wasFallback)
        assertEquals("risposta locale di riserva", result.text)
        assertEquals(1, remote.callCount)
        assertEquals(1, local.callCount)
    }

    @Test
    fun `remote network failure also falls back`() = runTest {
        val local = FakeAiEngine(AiExecutionTarget.LOCAL, resultText = "locale")
        val remote = FakeAiEngine(
            AiExecutionTarget.REMOTE_FAST,
            result = { AiEngineResult(it.requestId, success = false, target = AiExecutionTarget.REMOTE_FAST, failureReason = AiFailureReason.NETWORK) },
        )
        val router = AiRouter(local, remote, FakeRoutingContext(remoteAiEnabled = true, coreState = JarvisCoreState.ONLINE), emptySnapshotCache(), FakeSnapshotGate())

        val result = router.generate(request())

        assertTrue(result.wasFallback)
        assertEquals("locale", result.text)
    }

    @Test
    fun `no double response after fallback - exactly one attempt on each engine`() = runTest {
        val local = FakeAiEngine(AiExecutionTarget.LOCAL, resultText = "unica risposta")
        val remote = FakeAiEngine(
            AiExecutionTarget.REMOTE_FAST,
            result = { AiEngineResult(it.requestId, success = false, target = AiExecutionTarget.REMOTE_FAST, failureReason = AiFailureReason.ENGINE_ERROR) },
        )
        val router = AiRouter(local, remote, FakeRoutingContext(remoteAiEnabled = true, coreState = JarvisCoreState.ONLINE), emptySnapshotCache(), FakeSnapshotGate())

        val result = router.generate(request())

        // Exactly one engine attempt each — never two remote retries, never two local answers.
        assertEquals(1, remote.callCount)
        assertEquals(1, local.callCount)
        assertEquals("unica risposta", result.text)
    }

    @Test
    fun `cancellation during remote generation is never turned into a fallback`() = runTest {
        val local = FakeAiEngine(AiExecutionTarget.LOCAL, resultText = "non deve essere chiamato")
        val remote = FakeAiEngine(AiExecutionTarget.REMOTE_FAST, throwOnGenerate = CancellationException("stop"))
        val router = AiRouter(local, remote, FakeRoutingContext(remoteAiEnabled = true, coreState = JarvisCoreState.ONLINE), emptySnapshotCache(), FakeSnapshotGate())

        var propagated = false
        try {
            router.generate(request())
        } catch (_: CancellationException) {
            propagated = true
        }

        assertTrue(propagated)
        assertEquals(0, local.callCount) // cancellation means "stop", never "try elsewhere"
    }

    @Test
    fun `cancel with unknown requestId is a safe no-op that reaches both engines`() = runTest {
        val local = FakeAiEngine(AiExecutionTarget.LOCAL, resultText = "x")
        val remote = FakeAiEngine(AiExecutionTarget.REMOTE_FAST, resultText = "y")
        val router = AiRouter(local, remote, FakeRoutingContext(remoteAiEnabled = false, coreState = JarvisCoreState.DISABLED), emptySnapshotCache(), FakeSnapshotGate())

        router.cancel("never-started")

        assertTrue("never-started" in local.cancelledIds)
        assertTrue("never-started" in remote.cancelledIds)
    }

    @Test
    fun `stream falls back to local when the remote stream produces only a bare error chunk`() = runTest {
        val local = FakeAiEngine(AiExecutionTarget.LOCAL, resultText = "streaming locale")
        val remote = FakeAiEngine(
            AiExecutionTarget.REMOTE_FAST,
            result = { AiEngineResult(it.requestId, success = false, target = AiExecutionTarget.REMOTE_FAST, failureReason = AiFailureReason.UNAVAILABLE) },
        )
        val router = AiRouter(local, remote, FakeRoutingContext(remoteAiEnabled = true, coreState = JarvisCoreState.ONLINE), emptySnapshotCache(), FakeSnapshotGate())

        val chunks = mutableListOf<AiStreamChunk>()
        router.stream(request()).collect { chunks.add(it) }

        assertEquals(1, chunks.size)
        assertEquals("streaming locale", chunks.first().delta)
        assertEquals(1, local.callCount)
    }

    @Test
    fun `stream replays real remote content in full, never discards it for the local fallback`() = runTest {
        val local = FakeAiEngine(AiExecutionTarget.LOCAL, resultText = "non deve mai comparire")
        val remote = object : AiEngine {
            override val target = AiExecutionTarget.REMOTE_FAST
            override suspend fun isAvailable() = true
            override suspend fun generate(request: AiRequest) = AiEngineResult(request.requestId, success = true, text = "x", target = target)
            override fun stream(request: AiRequest): Flow<AiStreamChunk> = flow {
                emit(AiStreamChunk(request.requestId, delta = "Ciao, "))
                emit(AiStreamChunk(request.requestId, delta = "come stai?", done = true))
            }
            override fun cancel(requestId: String) = Unit
        }
        val router = AiRouter(local, remote, FakeRoutingContext(remoteAiEnabled = true, coreState = JarvisCoreState.ONLINE), emptySnapshotCache(), FakeSnapshotGate())

        val chunks = mutableListOf<AiStreamChunk>()
        router.stream(request()).collect { chunks.add(it) }

        assertEquals(2, chunks.size)
        assertEquals("Ciao, ", chunks[0].delta)
        assertEquals("come stai?", chunks[1].delta)
        assertEquals(0, local.callCount)
    }

    @Test
    fun `personal snapshot is attached automatically when enabled, without the caller doing anything`() = runTest {
        var seenContext: com.simone.jarvismobile.core.snapshot.RelevantPersonalContext? = null
        val local = FakeAiEngine(AiExecutionTarget.LOCAL, result = { seenContext = it.relevantContext; AiEngineResult(it.requestId, success = true, text = "ok", target = AiExecutionTarget.LOCAL) })
        val remote = FakeAiEngine(AiExecutionTarget.REMOTE_FAST, resultText = "remota")
        // Real "now": the selector's freshness check (RelevantContextSelector.select's
        // default `now` parameter) always compares against the real wall clock, since
        // AiRouter.withAutoContext has no way to inject a fixed clock — a stale fixed
        // literal here would make this section look expired and silently drop out.
        val now = java.time.Instant.now()
        val builder = com.simone.jarvismobile.snapshot.PersonalIntelligenceSnapshotBuilder(
            temporal = com.simone.jarvismobile.snapshot.providers.TemporalContextProvider {
                com.simone.jarvismobile.core.snapshot.TemporalContext(java.time.LocalDate.of(2026, 1, 1), java.time.LocalTime.of(10, 0), java.time.DayOfWeek.THURSDAY, null, null, now)
            },
            location = com.simone.jarvismobile.snapshot.providers.LocationContextProvider { null },
            agenda = com.simone.jarvismobile.snapshot.providers.AgendaContextProvider { null },
            driving = com.simone.jarvismobile.snapshot.providers.DrivingContextProvider { null },
            device = com.simone.jarvismobile.snapshot.providers.DeviceContextProvider { null },
            memory = com.simone.jarvismobile.snapshot.providers.MemoryContextProvider { null },
            recentEvents = com.simone.jarvismobile.snapshot.providers.RecentEventsProvider { null },
            task = com.simone.jarvismobile.snapshot.providers.TaskContextProvider { null },
            capability = com.simone.jarvismobile.snapshot.providers.CapabilityContextProvider { null },
        )
        val router = AiRouter(
            local, remote,
            FakeRoutingContext(remoteAiEnabled = false, coreState = JarvisCoreState.DISABLED),
            com.simone.jarvismobile.snapshot.PersonalIntelligenceSnapshotCache(builder),
            FakeSnapshotGate(isEnabled = true),
        )

        router.generate(request())

        assertTrue(seenContext != null)
        assertTrue(com.simone.jarvismobile.core.snapshot.SelectionCategory.TEMPORAL in seenContext!!.selected)
    }

    @Test
    fun `a caller-supplied relevantContext is never overwritten by the automatic one`() = runTest {
        var seenContext: com.simone.jarvismobile.core.snapshot.RelevantPersonalContext? = null
        val marker = com.simone.jarvismobile.core.snapshot.RelevantPersonalContext(approxSizeChars = 999)
        val local = FakeAiEngine(AiExecutionTarget.LOCAL, result = { seenContext = it.relevantContext; AiEngineResult(it.requestId, success = true, text = "ok", target = AiExecutionTarget.LOCAL) })
        val remote = FakeAiEngine(AiExecutionTarget.REMOTE_FAST, resultText = "remota")
        val router = AiRouter(local, remote, FakeRoutingContext(remoteAiEnabled = false, coreState = JarvisCoreState.DISABLED), emptySnapshotCache(), FakeSnapshotGate(isEnabled = true))

        router.generate(request().copy(relevantContext = marker))

        assertEquals(999, seenContext?.approxSizeChars)
    }

    @Test
    fun `snapshot failure never blocks generation - AI keeps working without it`() = runTest {
        val local = FakeAiEngine(AiExecutionTarget.LOCAL, resultText = "risposta comunque arrivata")
        val remote = FakeAiEngine(AiExecutionTarget.REMOTE_FAST, resultText = "remota")
        val throwingBuilder = com.simone.jarvismobile.snapshot.PersonalIntelligenceSnapshotBuilder(
            temporal = com.simone.jarvismobile.snapshot.providers.TemporalContextProvider { throw RuntimeException("boom") },
            location = com.simone.jarvismobile.snapshot.providers.LocationContextProvider { null },
            agenda = com.simone.jarvismobile.snapshot.providers.AgendaContextProvider { null },
            driving = com.simone.jarvismobile.snapshot.providers.DrivingContextProvider { null },
            device = com.simone.jarvismobile.snapshot.providers.DeviceContextProvider { null },
            memory = com.simone.jarvismobile.snapshot.providers.MemoryContextProvider { null },
            recentEvents = com.simone.jarvismobile.snapshot.providers.RecentEventsProvider { null },
            task = com.simone.jarvismobile.snapshot.providers.TaskContextProvider { null },
            capability = com.simone.jarvismobile.snapshot.providers.CapabilityContextProvider { null },
        )
        val router = AiRouter(
            local, remote,
            FakeRoutingContext(remoteAiEnabled = false, coreState = JarvisCoreState.DISABLED),
            com.simone.jarvismobile.snapshot.PersonalIntelligenceSnapshotCache(throwingBuilder),
            FakeSnapshotGate(isEnabled = true),
        )

        val result = router.generate(request())

        assertEquals("risposta comunque arrivata", result.text)
    }

    // --- fakes ---------------------------------------------------------------

    private class FakeAiEngine(
        override val target: AiExecutionTarget,
        private val resultText: String? = null,
        private val throwOnGenerate: Throwable? = null,
        private val result: ((AiRequest) -> AiEngineResult)? = null,
    ) : AiEngine {
        var callCount = 0
            private set
        val cancelledIds = mutableListOf<String>()

        override suspend fun isAvailable(): Boolean = true

        override suspend fun generate(request: AiRequest): AiEngineResult {
            callCount++
            throwOnGenerate?.let { throw it }
            return result?.invoke(request)
                ?: AiEngineResult(request.requestId, success = true, text = resultText, target = target)
        }

        override fun stream(request: AiRequest): Flow<AiStreamChunk> = flow {
            val r = generate(request)
            emit(AiStreamChunk(request.requestId, delta = r.text.orEmpty(), done = true, error = if (r.success) null else r.failureReason))
        }

        override fun cancel(requestId: String) {
            cancelledIds += requestId
        }
    }

    private class FakeRoutingContext(
        private val remoteAiEnabled: Boolean,
        private val coreState: JarvisCoreState,
    ) : AiRoutingContextProvider {
        override suspend fun preferencesFor(requestType: AiRequestType): AiRoutingPreferences =
            AiRoutingPreferences(remoteAiEnabled = remoteAiEnabled, coreState = coreState, coreHasBrainModel = false)
    }

    /** Disabled by default — existing tests above never care about auto-attached context, so it stays a no-op for them. */
    private class FakeSnapshotGate(private val isEnabled: Boolean = false) : SnapshotContextGate {
        override suspend fun enabled(): Boolean = isEnabled
        override suspend fun budget(): com.simone.jarvismobile.core.snapshot.ContextBudget = com.simone.jarvismobile.core.snapshot.ContextBudget()
    }

    /** A snapshot cache backed by a real builder whose nine providers all return null — an empty, harmless snapshot. */
    private fun emptySnapshotCache() = com.simone.jarvismobile.snapshot.PersonalIntelligenceSnapshotCache(
        com.simone.jarvismobile.snapshot.PersonalIntelligenceSnapshotBuilder(
            temporal = com.simone.jarvismobile.snapshot.providers.TemporalContextProvider { null },
            location = com.simone.jarvismobile.snapshot.providers.LocationContextProvider { null },
            agenda = com.simone.jarvismobile.snapshot.providers.AgendaContextProvider { null },
            driving = com.simone.jarvismobile.snapshot.providers.DrivingContextProvider { null },
            device = com.simone.jarvismobile.snapshot.providers.DeviceContextProvider { null },
            memory = com.simone.jarvismobile.snapshot.providers.MemoryContextProvider { null },
            recentEvents = com.simone.jarvismobile.snapshot.providers.RecentEventsProvider { null },
            task = com.simone.jarvismobile.snapshot.providers.TaskContextProvider { null },
            capability = com.simone.jarvismobile.snapshot.providers.CapabilityContextProvider { null },
        ),
    )
}
