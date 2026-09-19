package com.simone.jarvismobile.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * § JARVIS Implementation Master Plan — PROACTIVITY RELIABILITY CLOSURE WORK
 * PACKAGE D.1 §3 (W12). [com.simone.jarvismobile.weather.receipt
 * .WeatherDecisionDatabase] deliberately lives in a SEPARATE Room database
 * file (`weather_decisions.db`, its own [android.content.Context
 * .getDatabasePath] entry) precisely so it is excluded from
 * [BackupRepository]'s incremental encrypted backup by construction — never
 * by a redaction/filter step that a future edit could quietly weaken.
 *
 * `BackupRepository.collectSources()` proves this by enumerating an EXPLICIT
 * fixed filename list (`jarvis.db`/`jarvis.db-wal`/`jarvis.db-shm`) rather
 * than walking the app's `databases/` directory — a directory walk would
 * silently pick up `weather_decisions.db` (and its own `-wal`/`-shm`
 * sidecars) the moment that file exists on disk, which is exactly the
 * regression this test exists to catch.
 *
 * Rather than trust that finding to stay true forever, this scans the real
 * `app/src/main` source of [BackupRepository] at test time — a plain
 * file-content check, no Android/Robolectric needed, same pattern already
 * established by `WidgetUpdaterPreBarrierRegressionTest`/
 * `OnlineParameterRegressionTest` elsewhere in this project.
 */
class WeatherReceiptBackupExclusionRegressionTest {

    @Test
    fun `collectSources never references weather_decisions_db`() {
        val text = backupRepositorySource().readText()
        assertFalse(
            "BackupRepository now references weather_decisions.db directly — " +
                "the receipt database must stay excluded from backup by construction " +
                "(§ WORK PACKAGE D.1 §3); if this file now needs to include it " +
                "deliberately, update this regression test explicitly instead of " +
                "letting it slip in silently.",
            text.contains("weather_decisions"),
        )
    }

    @Test
    fun `the db source list is an explicit fixed filename enumeration, not a directory walk`() {
        val text = backupRepositorySource().readText()
        val collectSources = extractFunctionBody(text, "collectSources")
        assertTrue(
            "collectSources() must enumerate jarvis.db/-wal/-shm by an explicit " +
                "fixed name list (see the listOf(...) around the WAL checkpoint) — " +
                "this is what keeps a co-located but separately-named database file " +
                "(weather_decisions.db) out of the backup without any redaction step.",
            collectSources.contains("""listOf("jarvis.db", "jarvis.db-wal", "jarvis.db-shm")"""),
        )
        // A directory walk (walkTopDown/listFiles) over the databases/ folder
        // would defeat the fixed-name-list exclusion above the moment
        // weather_decisions.db exists on disk. The function DOES walk
        // documents/ (legitimate real content, unrelated to any database) —
        // so this asserts specifically that no walk/listFiles call is ever
        // reached from `context.getDatabasePath(...)`'s directory, not that
        // no walk exists in the function at all.
        val dbSection = collectSources.substringBefore("// Preferences (DataStore)")
        assertFalse(
            "collectSources() must never directory-walk/list the databases folder — " +
                "that would silently include weather_decisions.db the moment it exists.",
            dbSection.contains(".walkTopDown()") || dbSection.contains(".listFiles("),
        )
    }

    private fun extractFunctionBody(source: String, functionName: String): String {
        val marker = "fun $functionName("
        val start = source.indexOf(marker)
        checkNotNull(start >= 0) { "Could not locate fun $functionName( in BackupRepository.kt" }
        val bodyStart = source.indexOf('{', start)
        var depth = 0
        var i = bodyStart
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(bodyStart, i + 1)
                }
            }
            i++
        }
        error("Unbalanced braces while extracting fun $functionName( body")
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
