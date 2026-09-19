package com.simone.jarvismobile.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * § JARVIS Implementation Master Plan — PROACTIVITY RELIABILITY CLOSURE
 * WORK PACKAGE A. ARCHITECTURAL regression test (plain file-content scan,
 * no Android/Robolectric needed — same proven pattern as
 * `OnlineParameterRegressionTest`/`EventBridgeStartupRegressionTest`/
 * `RecoverySanitizationOrderingRegressionTest`): pins the "ONE CANONICAL
 * SIDE-EFFECT DISPATCH OWNER" invariant §11/§19/§22 require. REPLACES the
 * old MICRO-PATCH 14.2.2 version of this test, which encoded now-obsolete
 * behavior (`notifier.show(...)`, `refreshMorningDigestNotification()`,
 * `MorningRefreshGate` — all removed by this work package, §5/§10) — never
 * kept green by preserving a known-superseded design (§22).
 */
class MorningBriefingCanonicalGateRegressionTest {

    @Test
    fun `refreshMorningDigestNotification no longer exists - the P0-1 bypass is removed, not patched again`() {
        val text = proactiveManagerSource().readText()
        assertFalse(
            "refreshMorningDigestNotification() must be REMOVED entirely (§10, P0) - it was the one " +
                "code path with occurrence claim = NO that could produce Morning-Briefing-shaped " +
                "notification content.",
            text.contains("fun refreshMorningDigestNotification("),
        )
    }

    @Test
    fun `MorningRefreshGate no longer exists anywhere - nothing calls shouldRefresh once the refresh path is data-only`() {
        val proactiveDir = proactiveManagerSource().parentFile
        val referencingFiles = proactiveDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("MorningRefreshGate") }
            .toList()
        assertTrue(
            "No file under proactive/ should reference MorningRefreshGate any more (found: " +
                "${referencingFiles.map { it.name }}) - it was deleted along with the notification-" +
                "authorization role it played, since the refresh path is now data-only (§10).",
            referencingFiles.isEmpty(),
        )
    }

    @Test
    fun `every occurrence-backed delivered kind in run's Deliver branch goes through dispatcher_dispatch, never a direct notifier call`() {
        val text = proactiveManagerSource().readText()
        val body = extractFunctionBody(text, "run")
        val deliverBranchStart = body.indexOf("is ProactiveDecision.Deliver ->")
        assertTrue("Could not find the Deliver branch in run()", deliverBranchStart >= 0)
        val skipBranchStart = body.indexOf("is ProactiveDecision.Skip ->", deliverBranchStart)
        val deliverBody = if (skipBranchStart > deliverBranchStart) {
            body.substring(deliverBranchStart, skipBranchStart)
        } else {
            body.substring(deliverBranchStart)
        }
        assertTrue(
            "run()'s Deliver branch must call dispatcher.dispatch( for occurrence-backed kinds - the " +
                "single production dispatch owner (§11).",
            deliverBody.contains("dispatcher.dispatch("),
        )
        // The ONE deliberate exception is BATTERY_BEFORE_ALARM, which has no
        // durable occurrence backing (existing SharedPreferences dedup, out
        // of scope) - its direct notifier.dispatch( call must be reachable
        // ONLY from that specific no-occurrence branch, never generically.
        val directNotifierCalls = Regex("""notifier\.dispatch\(""").findAll(deliverBody).count()
        assertEquals(
            "run()'s Deliver branch must have EXACTLY ONE direct notifier.dispatch( call - the " +
                "BATTERY_BEFORE_ALARM no-occurrence fallback path. Every occurrence-backed kind " +
                "(MORNING_DIGEST/EVENING_DIGEST/WEATHER_ALERT) must go through dispatcher.dispatch( instead.",
            1,
            directNotifierCalls,
        )
    }

    @Test
    fun `simulateWeatherAlert uses the debug tag, never a production feature tag`() {
        val text = proactiveManagerSource().readText()
        val body = extractFunctionBody(text, "simulateWeatherAlert")
        assertTrue(
            "simulateWeatherAlert()'s notifier.dispatch(...) call must pass tag = ProactiveNotifier.TAG_DEBUG " +
                "(§18) - never a production feature tag, and never production occurrence ownership.",
            body.contains("ProactiveNotifier.TAG_DEBUG"),
        )
        assertTrue(
            "simulateWeatherAlert()'s message must be visibly prefixed [SIMULAZIONE] (§18) so it can " +
                "never be confused with a real hazard warning.",
            body.contains("[SIMULAZIONE]"),
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
    fun `MorningRefreshWorker is data-only - never composes, never calls the notifier or ProactiveManager, never mutates dispatch ownership`() {
        val text = morningRefreshWorkerSource().readText()
        assertFalse(
            "MorningRefreshWorker must never call into ProactiveManager any more (§10) - it is data-only.",
            text.contains("proactiveManager()"),
        )
        assertFalse(
            "MorningRefreshWorker must never reference ProactiveComposer - composing a digest is a " +
                "side-effecting production decision, forbidden here (§10).",
            text.contains("ProactiveComposer"),
        )
        assertFalse(
            "MorningRefreshWorker must never reference ProactiveNotifier - it must not post/update/" +
                "recreate any notification (§10).",
            text.contains("ProactiveNotifier"),
        )
        assertFalse(
            "MorningRefreshWorker must never call NotificationManagerCompat directly either.",
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
