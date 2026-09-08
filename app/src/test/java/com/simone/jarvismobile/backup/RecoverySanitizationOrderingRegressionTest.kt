package com.simone.jarvismobile.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * § JARVIS Implementation Master Plan PASSAGGIO 10.3 §3/§5 — sanitization
 * ordering, updated from the PASSAGGIO 10.2 version of this same test.
 *
 * PASSAGGIO 10.2 required that recovery's cold-start path never open Room
 * at all, relying on whichever consumer happened to open it FIRST after the
 * barrier released to (maybe) trigger `RestoreSanitizeCallback.onOpen()` —
 * §1 of PASSAGGIO 10.3 confirmed that reliance was itself the residual gap:
 * nothing ever checked whether that first open actually cleared the
 * marker, and a stale marker with no staged work wasn't even recognized as
 * pending. PASSAGGIO 10.3 makes the Room open a DELIBERATE, PROVEN part of
 * recovery itself — [BackupRepository.ensureAssistantTasksSanitized] is now
 * the ONLY place recovery opens Room, and it does so strictly AFTER the
 * db/datastore file cutover loop (and marker persistence) has already run.
 *
 * Two invariants are pinned here, both via a plain scan of the real source
 * — no Android/Robolectric needed:
 *  1. [RestoreStaging]'s atomic file-cutover primitive, `cutoverEntryNow`,
 *     still never references the live `database` field directly — the raw
 *     file replace itself must stay database-independent, exactly as
 *     PASSAGGIO 10.1/10.2 required.
 *  2. Inside `completePendingRestoreRecovery`'s own body, the call to
 *     `cutoverEntryNow(...)` (the file-cutover loop) textually precedes the
 *     call to `ensureAssistantTasksSanitized(...)` (the deliberate Room
 *     open) — proving the required order: cutover → persist marker → open
 *     Room → sanitize, never the reverse.
 */
class RecoverySanitizationOrderingRegressionTest {

    @Test
    fun `cutoverEntryNow never opens the live Room database directly`() {
        val source = backupRepositorySource().readText()
        val cutoverBody = functionBody(source, "cutoverEntryNow")

        assertFalse(
            "cutoverEntryNow now references the live `database` field — the atomic file-cutover " +
                "primitive must stay database-independent; any Room open belongs only in " +
                "ensureAssistantTasksSanitized, strictly after cutover has already completed.",
            Regex("""\bdatabase\.""").containsMatchIn(cutoverBody),
        )
        assertFalse(
            "cutoverEntryNow now references `.openHelper` — same problem.",
            cutoverBody.contains(".openHelper"),
        )
    }

    @Test
    fun `recovery opens Room to sanitize only after the cutover loop has already run`() {
        val source = backupRepositorySource().readText()
        val recoveryBody = functionBody(source, "completePendingRestoreRecovery")

        val cutoverCallIndex = recoveryBody.indexOf("cutoverEntryNow(")
        val ensureSanitizedCallIndex = recoveryBody.indexOf("ensureAssistantTasksSanitized(")

        assertTrue("Could not find the cutoverEntryNow( call inside completePendingRestoreRecovery", cutoverCallIndex >= 0)
        assertTrue(
            "Could not find the ensureAssistantTasksSanitized( call inside completePendingRestoreRecovery",
            ensureSanitizedCallIndex >= 0,
        )
        assertTrue(
            "ensureAssistantTasksSanitized(...) — the deliberate Room open — must be called AFTER the " +
                "cutoverEntryNow(...) file-cutover loop, never before: opening Room before the db file " +
                "cutover finishes would defeat the whole point of deferring cutover to cold start.",
            cutoverCallIndex < ensureSanitizedCallIndex,
        )
    }

    @Test
    fun `ensureAssistantTasksSanitized is the one place that deliberately opens Room`() {
        val source = backupRepositorySource().readText()
        val ensureBody = functionBody(source, "ensureAssistantTasksSanitized")

        assertTrue(
            "ensureAssistantTasksSanitized should open Room via database.openHelper — the same " +
                "already-established pattern checkpointWal() uses, not a new API.",
            ensureBody.contains("database.openHelper"),
        )
    }

    /** Sanity check the extraction itself actually found real, non-empty bodies (never a silent false pass on zero characters scanned). */
    @Test
    fun `extracted function bodies are non-trivial`() {
        val source = backupRepositorySource().readText()
        assertTrue(functionBody(source, "completePendingRestoreRecovery").length > 100)
        assertTrue(functionBody(source, "cutoverEntryNow").length > 50)
        assertTrue(functionBody(source, "ensureAssistantTasksSanitized").length > 10)
    }

    /** Extracts the brace-balanced body of `fun <name>(...) { ... }` (or `= expr` bodies whose first `{` is the real content), starting from its first `{`. */
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
