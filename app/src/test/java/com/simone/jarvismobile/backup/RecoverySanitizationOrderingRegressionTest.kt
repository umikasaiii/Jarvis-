package com.simone.jarvismobile.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * § JARVIS Implementation Master Plan PASSAGGIO 10.2 §5 — sanitization
 * ordering. `RestoreSanitizeCallback` runs inside Room's own `onOpen()`
 * (guaranteed by Room to complete before any query on that connection is
 * served), which fires only on the FIRST time this process opens
 * `JarvisDatabase`. That guarantee only protects a just-restored
 * `assistant_tasks` table if [BackupRepository.completePendingRestoreRecovery]
 * — which writes the sanitize marker after a real db cutover — never itself
 * performs that first open (via the injected `database`/`openHelper`, e.g.
 * through `currentDbSchemaVersion()`, which [BackupRepository.restore] does
 * call). If it did, the marker could exist strictly AFTER Room had already
 * been opened once in this process, and `onOpen()` would never fire again
 * to sanitize it.
 *
 * This scans the real source of [com.simone.jarvismobile.backup.BackupRepository]'s
 * `completePendingRestoreRecovery`/`cutoverEntryNow`/`clearDerivedCachesIfMarked`
 * functions — the entire code path that runs at cold start before the
 * startup barrier releases — for any reference to the live `database`
 * field or `.openHelper`, which would open Room. A plain file-content
 * check, no Android/Robolectric needed; it is what makes the ordering
 * claim in `completePendingRestoreRecovery`'s own doc comment provable
 * instead of just asserted.
 */
class RecoverySanitizationOrderingRegressionTest {

    @Test
    fun `cold-start recovery path never opens the live Room database`() {
        val source = backupRepositorySource().readText()
        val recoveryBody = functionBody(source, "completePendingRestoreRecovery")
        val cutoverBody = functionBody(source, "cutoverEntryNow")
        val cacheClearBody = functionBody(source, "clearDerivedCachesIfMarked")

        for ((name, body) in listOf(
            "completePendingRestoreRecovery" to recoveryBody,
            "cutoverEntryNow" to cutoverBody,
            "clearDerivedCachesIfMarked" to cacheClearBody,
        )) {
            assertFalse(
                "$name now references the live `database` field — this would open Room's " +
                    "connection before RestoreSanitizeCallback's marker is written, breaking the " +
                    "guarantee that onOpen() sanitizes assistant_tasks before any consumer query.",
                Regex("""\bdatabase\.""").containsMatchIn(body),
            )
            assertFalse(
                "$name now references `.openHelper` — same problem: it would open Room before recovery finishes.",
                body.contains(".openHelper"),
            )
        }
    }

    /** Sanity check the extraction itself actually found real, non-empty bodies (never a silent false pass on zero characters scanned). */
    @Test
    fun `extracted function bodies are non-trivial`() {
        val source = backupRepositorySource().readText()
        assertTrue(functionBody(source, "completePendingRestoreRecovery").length > 100)
        assertTrue(functionBody(source, "cutoverEntryNow").length > 50)
        assertTrue(functionBody(source, "clearDerivedCachesIfMarked").length > 50)
    }

    /** Extracts the brace-balanced body of `fun <name>(...) { ... }`, starting from its first `{`. */
    private fun functionBody(source: String, functionName: String): String {
        val signatureIndex = source.indexOf("fun $functionName(")
        check(signatureIndex >= 0) { "Could not find `fun $functionName(` in BackupRepository.kt — has it been renamed?" }
        val openBrace = source.indexOf('{', signatureIndex)
        check(openBrace >= 0) { "Could not find the opening brace of $functionName" }
        var depth = 0
        var i = openBrace
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(openBrace, i + 1)
                }
            }
            i++
        }
        error("Unbalanced braces while extracting $functionName's body")
    }

    private fun backupRepositorySource(): File {
        val candidates = listOf(
            File("src/main/java/com/simone/jarvismobile/backup/BackupRepository.kt"),
            File("app/src/main/java/com/simone/jarvismobile/backup/BackupRepository.kt"),
        )
        val found = candidates.firstOrNull { it.isFile }
        checkNotNull(found) {
            "Could not locate BackupRepository.kt from working directory " +
                "${File(".").absolutePath} — this test cannot silently pass without reading the real file."
        }
        return found
    }
}
