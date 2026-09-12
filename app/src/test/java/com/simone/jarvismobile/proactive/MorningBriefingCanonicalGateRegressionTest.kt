package com.simone.jarvismobile.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * § JARVIS Implementation Master Plan — MICRO-PATCH 14.2.2. ARCHITECTURAL
 * regression test (plain file-content scan, no Android/Robolectric needed
 * — same proven pattern as `OnlineParameterRegressionTest`/
 * `EventBridgeStartupRegressionTest`/`RecoverySanitizationOrderingRegressionTest`):
 * pins the "ONE CANONICAL SIDE-EFFECT GATE" invariant the mission requires
 * — every known Morning Briefing entry point either goes through the
 * atomic occurrence claim/gate, or (for the one legitimate post-delivery
 * refresh path) through [com.simone.jarvismobile.core.proactive.MorningRefreshGate]
 * plus a silent-only notification — so a future edit cannot silently
 * re-introduce the exact bypass this micro-patch fixed
 * ([ProactiveManager.refreshMorningDigestNotification] used to call
 * `notifier.show(suggestion)` directly with zero occurrence check at all).
 */
class MorningBriefingCanonicalGateRegressionTest {

    @Test
    fun `notifier show for a real MORNING_DIGEST-shaped notification is called from exactly the three known, audited call sites`() {
        val text = proactiveManagerSource().readText()
        val callSites = Regex("""notifier\.show\(""").findAll(text).count()
        assertEquals(
            "ProactiveManager.kt must have EXACTLY 3 notifier.show( call sites: (1) run()'s gated " +
                "Deliver branch, (2) simulateWeatherAlert()'s debug-only distinct-key path, " +
                "(3) refreshMorningDigestNotification()'s gate-checked silent refresh. A new count " +
                "means either a new bypass was added, or this test's own accounting needs updating " +
                "after a deliberate, reviewed change — never silently.",
            3,
            callSites,
        )
    }

    @Test
    fun `refreshMorningDigestNotification consults MorningRefreshGate before ever touching the notifier`() {
        val text = proactiveManagerSource().readText()
        val body = extractFunctionBody(text, "refreshMorningDigestNotification")
        assertTrue(
            "refreshMorningDigestNotification() must call MorningRefreshGate.shouldRefresh(...) - " +
                "the real fix for the bypass that produced extra real-device deliveries.",
            body.contains("MorningRefreshGate.shouldRefresh("),
        )
        val gateIndex = body.indexOf("MorningRefreshGate.shouldRefresh(")
        val notifyIndex = body.indexOf("notifier.show(")
        assertTrue(
            "the gate check must come BEFORE the notifier.show( call in refreshMorningDigestNotification(), " +
                "not merely exist somewhere in the function.",
            gateIndex in 0 until notifyIndex,
        )
    }

    @Test
    fun `refreshMorningDigestNotification always posts silently - it can update content but never itself alert`() {
        val text = proactiveManagerSource().readText()
        val body = extractFunctionBody(text, "refreshMorningDigestNotification")
        assertTrue(
            "the refresh path's notifier.show(...) call must pass silent = true - setOnlyAlertOnce " +
                "alone does not prevent alerting once the original notification has been dismissed, " +
                "which is the exact real-device root cause this micro-patch fixes.",
            body.contains("notifier.show(suggestion, silent = true)"),
        )
    }

    @Test
    fun `refreshMorningDigestNotification never calls ProactiveGovernor decide or speakBackgroundResponse`() {
        val text = proactiveManagerSource().readText()
        val body = extractFunctionBody(text, "refreshMorningDigestNotification")
        assertFalse(
            "a content refresh must never make a second independent delivery decision via the governor.",
            body.contains("ProactiveGovernor.decide"),
        )
        assertFalse(
            "a content refresh must never re-speak the briefing (a second spoken briefing minutes " +
                "later would be intrusive, not helpful - already documented intent, now also pinned).",
            body.contains("speakBackgroundResponse"),
        )
    }

    @Test
    fun `AlarmReceiver KIND_REMINDER path never references ProactiveNotifier or the morning-digest occurrence key format`() {
        val text = alarmReceiverSource().readText()
        assertFalse(
            "agenda-item reminders (KIND_REMINDER / notifyReminder) must stay structurally distinct " +
                "from the Morning Briefing - never masquerading as one by sharing its notifier/kind.",
            text.contains("ProactiveNotifier"),
        )
        assertFalse(
            text.contains("MORNING_DIGEST"),
        )
    }

    @Test
    fun `MorningRefreshWorker delegates to the gated refreshMorningDigestNotification, not a direct NotificationManager call`() {
        val text = morningRefreshWorkerSource().readText()
        assertTrue(
            "MorningRefreshWorker must call the (now gate-checked) ProactiveManager method, never post " +
                "a notification itself.",
            text.contains("proactiveManager().refreshMorningDigestNotification("),
        )
        assertFalse(
            "MorningRefreshWorker must never call NotificationManagerCompat directly - all delivery/" +
                "refresh side effects go through ProactiveNotifier via ProactiveManager.",
            text.contains("NotificationManagerCompat"),
        )
    }

    /** Extracts a single function's body by matching braces, starting at its `fun <name>(` declaration. */
    private fun extractFunctionBody(source: String, functionName: String): String {
        val declIndex = source.indexOf("fun $functionName(")
        check(declIndex >= 0) { "Could not find `fun $functionName(` in the source - has it been renamed?" }
        val openBraceIndex = source.indexOf('{', declIndex)
        check(openBraceIndex >= 0) { "Could not find the opening brace of $functionName()" }
        var depth = 0
        var i = openBraceIndex
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(openBraceIndex, i + 1)
                }
            }
            i++
        }
        error("Unbalanced braces while extracting $functionName() - could not find its closing brace")
    }

    private fun proactiveManagerSource(): File = resolve("src/main/java/com/simone/jarvismobile/proactive/ProactiveManager.kt")
    private fun alarmReceiverSource(): File = resolve("src/main/java/com/simone/jarvismobile/alarms/AlarmReceiver.kt")
    private fun morningRefreshWorkerSource(): File = resolve("src/main/java/com/simone/jarvismobile/proactive/MorningRefreshWorker.kt")

    private fun resolve(relative: String): File {
        val candidates = listOf(File(relative), File("app/$relative"))
        val found = candidates.firstOrNull { it.isFile }
        checkNotNull(found) {
            "Could not locate $relative from working directory ${File(".").absolutePath} - " +
                "this test cannot silently pass without reading the real file."
        }
        return found
    }
}
