package com.simone.jarvismobile.net

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.simone.jarvismobile.core.net.Decision
import com.simone.jarvismobile.core.net.NetOperation
import com.simone.jarvismobile.core.net.OfflineBlockedException
import com.simone.jarvismobile.core.net.OfflineMode
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * M3-D — the offline gate at the REAL boundary: real OkHttp clients built through the production `guarded()` helper, and the production
 * [com.simone.jarvismobile.corebridge.CoreClient] (real settings, real client) pointed at a LOOPBACK server that counts connections. Loopback is
 * used because the emulator's real network state is not controllable from a test: what is proved is "no socket is opened by JARVIS's adapters in
 * STRICT_OFFLINE, one is opened in EXPLICIT_ONLINE", observed on the server side — not "no packet leaves the device" (airplane mode and OS-level
 * observation are NOT covered by this test). No traffic goes to any external host.
 */
@RunWith(AndroidJUnit4::class)
class M3OfflineInstrumentedTest {
    private lateinit var server: ServerSocket
    private val accepted = AtomicInteger()
    @Volatile private var running = true
    private lateinit var entry: OfflineEntryPoint
    private var savedHost = ""; private var savedPort = 0; private var savedEnabled = false

    @Before fun setUp() {
        entry = EntryPointAccessors.fromApplication(ApplicationProvider.getApplicationContext(), OfflineEntryPoint::class.java)
        server = ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1")).also { it.soTimeout = 300 }
        thread(isDaemon = true) {
            while (running) {
                try {
                    server.accept().use { s ->
                        accepted.incrementAndGet()
                        s.getInputStream().read(ByteArray(2048))
                        s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nContent-Type: application/json\r\n\r\n{}".toByteArray())
                    }
                } catch (_: SocketTimeoutException) { } catch (_: Exception) { }
            }
        }
        runBlocking {
            savedHost = entry.settings().coreHost.first()
            savedPort = entry.settings().corePort.first()
            savedEnabled = entry.settings().coreEnabled.first()
        }
        OfflineGate.trace.clear()
    }

    @After fun tearDown() = runBlocking {
        running = false; runCatching { server.close() }
        entry.settings().setCoreHost(savedHost); entry.settings().setCorePort(savedPort); entry.settings().setCoreEnabled(savedEnabled)
        entry.settings().setOfflineMode(OfflineMode.EXPLICIT_ONLINE)
    }

    private fun url() = "http://127.0.0.1:${server.localPort}/v1/health"

    @Test fun strictOfflineStopsAGuardedClientBeforeAnySocketIsOpened() {
        OfflineGate.initialise(OfflineMode.STRICT_OFFLINE)
        val client = OkHttpClient.Builder().guarded(NetOperation.WEATHER).build()
        try { client.newCall(Request.Builder().url(url()).build()).execute().close(); fail("a call went out in STRICT_OFFLINE") } catch (e: OfflineBlockedException) { /* expected */ }
        Thread.sleep(400)
        assertEquals("no connection reached the server", 0, accepted.get())
        assertTrue(OfflineGate.trace.events().any { it.component == "net:weather" && it.decision == Decision.BLOCK.name })
    }

    @Test fun explicitOnlineLetsTheSameClientThrough() {
        OfflineGate.initialise(OfflineMode.EXPLICIT_ONLINE)
        val client = OkHttpClient.Builder().guarded(NetOperation.WEATHER).build()
        client.newCall(Request.Builder().url(url()).build()).execute().use { assertTrue(it.isSuccessful) }
        assertEquals(1, accepted.get())
        assertTrue(OfflineGate.trace.events().any { it.decision == Decision.ALLOW.name && it.result == "ok" })
    }

    @Test fun everyCensusedOperationIsBlockedInStrictAndAnUncensusedOneEvenWhenOnline() {
        OfflineGate.initialise(OfflineMode.STRICT_OFFLINE)
        for (op in NetOperation.values()) {
            val c = OkHttpClient.Builder().guarded(op).build()
            try { c.newCall(Request.Builder().url(url()).build()).execute().close(); fail("$op went out") } catch (_: OfflineBlockedException) { }
        }
        OfflineGate.initialise(OfflineMode.EXPLICIT_ONLINE)
        try { OkHttpClient.Builder().guarded(NetOperation.UNKNOWN).build().newCall(Request.Builder().url(url()).build()).execute().close(); fail("unknown op allowed") } catch (_: OfflineBlockedException) { }
        Thread.sleep(300)
        assertEquals(0, accepted.get())
    }

    @Test fun aCancelledCallStartsNoLateAttempt() {
        OfflineGate.initialise(OfflineMode.EXPLICIT_ONLINE)
        val call = OkHttpClient.Builder().guarded(NetOperation.WEATHER).build().newCall(Request.Builder().url(url()).build())
        call.cancel()
        try { call.execute().close(); fail("a cancelled call ran") } catch (_: java.io.IOException) { }
        Thread.sleep(300)
        assertEquals(0, accepted.get())
    }

    @Test fun theProductionCoreClientReachesTheServerOnlyInExplicitOnline() = runBlocking {
        entry.settings().setCoreHost("127.0.0.1"); entry.settings().setCorePort(server.localPort); entry.settings().setCoreEnabled(true)
        entry.settings().setOfflineMode(OfflineMode.STRICT_OFFLINE)
        val strict = entry.coreClient().healthCheck()
        Thread.sleep(400)
        assertFalse("strict: Core must look unreachable", strict.reachable)
        assertEquals("strict: the server saw no connection", 0, accepted.get())
        entry.settings().setOfflineMode(OfflineMode.EXPLICIT_ONLINE)
        val online = entry.coreClient().healthCheck()
        assertTrue("explicit: the same client reaches it", online.reachable)
        assertTrue(accepted.get() >= 1)
    }

    @Test fun theTraceNamesNoHostPortOrUrl() {
        OfflineGate.initialise(OfflineMode.STRICT_OFFLINE)
        runCatching { OkHttpClient.Builder().guarded(NetOperation.CORE_REMOTE_CHAT).build().newCall(Request.Builder().url(url()).build()).execute().close() }
        val json = OfflineGate.trace.preview().json
        assertFalse("127.0.0.1" in json || server.localPort.toString() in json || "http" in json)
    }
}
