package com.simone.jarvismobile.core.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class M3NetworkPolicyTest {
    @Test fun strictOfflineBlocksEverythingWhateverTheUserEnabled() {
        for (op in NetOperation.values()) {
            val d = NetworkPolicy.decide(OfflineMode.STRICT_OFFLINE, op, userEnabled = true)
            assertEquals(Decision.BLOCK, d.decision, op.name); assertEquals(Cause.BLOCKED_STRICT_OFFLINE, d.cause, op.name)
        }
    }

    @Test fun explicitOnlineNeedsTheUsersIntegrationAndNeverAllowsAnUncensusedCall() {
        assertEquals(Decision.ALLOW, NetworkPolicy.decide(OfflineMode.EXPLICIT_ONLINE, NetOperation.WEATHER, true).decision)
        assertEquals(Cause.BLOCKED_NOT_ENABLED_BY_USER, NetworkPolicy.decide(OfflineMode.EXPLICIT_ONLINE, NetOperation.CORE_REMOTE_CHAT, false).cause)
        assertEquals(Cause.BLOCKED_UNKNOWN_OPERATION, NetworkPolicy.decide(OfflineMode.EXPLICIT_ONLINE, NetOperation.UNKNOWN, true).cause)
    }

    @Test fun aCancelledRequestCannotStartALateAttempt() {
        assertEquals(Cause.BLOCKED_REQUEST_CANCELLED, NetworkPolicy.decide(OfflineMode.EXPLICIT_ONLINE, NetOperation.CORE_REMOTE_CHAT, true, requestActive = false).cause)
    }

    @Test fun theBlockExceptionIsAnIoErrorSoEveryExistingFailurePathHandlesIt() {
        val e = OfflineBlockedException(NetOperation.WEATHER, Cause.BLOCKED_STRICT_OFFLINE)
        assertTrue(e is java.io.IOException); assertTrue("weather" in e.message!!)
    }

    @Test fun theTraceIsBoundedAndContentFree() {
        val t = OfflineTrace(capacity = 3)
        repeat(5) { t.net("req-$it", NetOperation.CORE_REMOTE_CHAT, OfflineMode.STRICT_OFFLINE, NetDecision(Decision.BLOCK, Cause.BLOCKED_STRICT_OFFLINE), "blocked") }
        assertEquals(3, t.events().size); assertEquals("req-4", t.events().last().requestId)
        // free text, URLs, IPs and paths cannot get in through any string field
        t.add(TraceEvent(1, "https://x.example/api?key=SECRET", "fast", "llm:/data/user/0/model.litertlm", "-", "-", "10.0.0.5", "Qual è la mia password? 1234", 5, mapOf("estimatedTokens" to 10, "http://evil" to 3, "mypassword" to 4)))
        val json = t.preview().json
        for (bad in listOf("SECRET", "example", "10.0.0.5", "password", "/data/", "evil", "http", "mypassword", "key=")) assertFalse(bad in json, bad)
        assertTrue(t.preview().events == 3 && !t.preview().containsFreeText)
    }

    @Test fun exportHappensOnlyOnTheUsersConfirmationAndEqualsThePreview() {
        val t = OfflineTrace()
        t.llm("r", "fast", "load", "ok", 1200, mapOf("pssAfterMb" to 700))
        assertNull(t.exportForUser(confirmed = false))
        assertEquals(t.preview().json, t.exportForUser(confirmed = true))
        assertTrue("\"n\":{\"pssAfterMb\":700}" in t.preview().json)
    }
}
