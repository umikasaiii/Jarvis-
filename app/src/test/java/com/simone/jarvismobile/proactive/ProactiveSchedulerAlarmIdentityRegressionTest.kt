package com.simone.jarvismobile.proactive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * § JARVIS Implementation Master Plan — MICRO-PATCH 14.2.2 §7 / PROACTIVITY
 * RELIABILITY CLOSURE WORK PACKAGE B §3. STALE SCHEDULE RECONCILIATION — a
 * source-scan regression test (no Android/Robolectric needed) pinning that a
 * changed configured briefing time can never create a second,
 * independently-live exact alarm. Re-targeted from
 * `MorningTriggerScheduler` to [ProactiveScheduler] — Work Package B §3
 * absorbed CONFIGURED_TIME/NEXT_ALARM scheduling authority into the latter,
 * which is now the sole class this invariant applies to.
 *
 * [ProactiveScheduler.reconcileConfiguredTime] must always schedule under
 * the SAME, constant `KEY_CONFIGURED_TIME` — never a key derived from the
 * configured hour/minute — because [ExactAlarms]'s own `PendingIntent`
 * identity is built from that key alone (`Uri.parse("jarvis://alarm/$key")`
 * + `PendingIntent.getBroadcast(context, key.hashCode(), ..., FLAG_UPDATE_CURRENT)`):
 * a constant key means `AlarmManager` itself replaces any previously
 * scheduled alarm for that PendingIntent when `scheduleWithOutcome()` is
 * called again after a time change, rather than leaving the old one
 * independently live.
 */
class ProactiveSchedulerAlarmIdentityRegressionTest {

    @Test
    fun `reconcileConfiguredTime always schedules under the constant KEY_CONFIGURED_TIME, never a value derived from hour or minute`() {
        val text = proactiveSchedulerSource().readText()
        val body = extractFunctionBody(text, "reconcileConfiguredTime")
        assertTrue(
            "reconcileConfiguredTime() must call exactAlarms.scheduleWithOutcome(key = KEY_CONFIGURED_TIME, ...) - " +
                "a constant identity so a time change replaces the alarm in place instead of forking it.",
            body.contains("key = KEY_CONFIGURED_TIME"),
        )
        assertFalse(
            "the scheduled key must never interpolate hour/minute - that would give each configured " +
                "time its own independent PendingIntent identity, letting an old schedule survive " +
                "alongside a new one.",
            Regex("""key\s*=\s*"[^"]*\$\{?(hour|minute)""").containsMatchIn(body),
        )
    }

    @Test
    fun `ExactAlarms builds PendingIntent identity from the key alone - never from extras or fire time`() {
        val text = exactAlarmsSource().readText()
        assertTrue(
            "PendingIntent request code must be derived from key.hashCode() - the single stable " +
                "identity a rescheduled alarm reuses to replace, not duplicate, the previous one.",
            text.contains("key.hashCode()"),
        )
        assertTrue(
            "the PendingIntent's Intent data must be built from key alone (jarvis://alarm/\$key) - " +
                "never including the fire time/extras, which would change Intent#filterEquals identity " +
                "on every reschedule and defeat FLAG_UPDATE_CURRENT's replace-in-place semantics.",
            text.contains("Uri.parse(\"jarvis://alarm/\$key\")"),
        )
        assertTrue(
            "scheduling must use FLAG_UPDATE_CURRENT so a re-schedule under the same key replaces " +
                "the existing alarm instead of creating a second one.",
            text.contains("FLAG_UPDATE_CURRENT"),
        )
    }

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

    private fun proactiveSchedulerSource(): File = resolve("src/main/java/com/simone/jarvismobile/proactive/ProactiveScheduler.kt")
    private fun exactAlarmsSource(): File = resolve("src/main/java/com/simone/jarvismobile/alarms/ExactAlarms.kt")

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
