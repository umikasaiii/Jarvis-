package com.simone.jarvismobile.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * STEP 2 — JVM guards for the `jarvis.db` upgrade chain. These do NOT run SQLite
 * (that is `JarvisMigrationInstrumentedTest`); they make a missing or wrong-shaped
 * migration, or a re-introduced destructive fallback, fail the build.
 */
class JarvisMigrationsTest {

    @Test fun chainFromOldestToCurrentIsContiguous() {
        assertEquals(
            "every single step n -> n+1 must be registered; missing: ",
            emptyList<Int>(), JarvisMigrations.missingSteps(),
        )
    }

    @Test fun detectsAMissingStep() {
        val without = JarvisMigrations.ALL.filterNot { it.startVersion == 9 }.toTypedArray()
        assertEquals(listOf(9), JarvisMigrations.missingSteps(without))
    }

    @Test fun everyMigrationIsASingleForwardStepAndRegisteredOnce() {
        JarvisMigrations.ALL.forEach {
            assertEquals("${it.startVersion}->${it.endVersion}", it.startVersion + 1, it.endVersion)
        }
        val pairs = JarvisMigrations.ALL.map { it.startVersion to it.endVersion }
        assertEquals("duplicate migration registered", pairs.size, pairs.toSet().size)
        assertEquals(JarvisMigrations.CURRENT_VERSION - JarvisMigrations.OLDEST_SUPPORTED_VERSION, pairs.size)
    }

    @Test fun currentVersionMatchesTheDatabaseAnnotation() {
        val src = source("background/JarvisDatabase.kt")
        val declared = Regex("""version\s*=\s*(\d+)""").find(src)?.groupValues?.get(1)?.toInt()
        assertEquals("JarvisMigrations.CURRENT_VERSION must equal @Database(version)", declared, JarvisMigrations.CURRENT_VERSION)
    }

    @Test fun schemaExportIsEnabled() {
        val src = source("background/JarvisDatabase.kt")
        assertTrue("exportSchema must stay true", Regex("""exportSchema\s*=\s*true""").containsMatchIn(src))
        assertFalse(Regex("""exportSchema\s*=\s*false""").containsMatchIn(src))
    }

    @Test fun mainDatabaseHasNoDestructiveFallback() {
        val code = source("di/DatabaseModule.kt").lines()
            .filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") || it.trim().startsWith("/*") }
            .joinToString("\n")
        assertFalse("jarvis.db must never be built with a destructive fallback", code.contains("fallbackToDestructiveMigration"))
        assertTrue(code.contains("JarvisMigrations.ALL"))
        assertTrue("preflight guard must run before the builder", code.contains("DatabaseOpenGuard.preflight"))
    }

    @Test fun schemaLocationIsConfiguredForKsp() {
        val gradle = resolve("build.gradle.kts").readText()
        assertTrue(gradle.contains("room.schemaLocation"))
    }

    /** Found by the authentic-schema run: v13/v14 clean installs lack trigger_evidence's indices. */
    @Test fun migrationsFrom13And14RepairTriggerEvidenceIndices() {
        listOf("proactive/ProactiveSchedulePlanMigrations.kt", "responsibility/ResponsibilityMigrations.kt").forEach {
            assertTrue("$it must call TriggerEvidenceMigrations.ensureIndices", source(it).contains("TriggerEvidenceMigrations.ensureIndices(db)"))
        }
        val te = source("proactive/TriggerEvidenceMigrations.kt")
        assertTrue(te.contains("index_trigger_evidence_source") && te.contains("index_trigger_evidence_eventAtMs"))
        assertTrue("indices must be created idempotently", te.contains("CREATE INDEX IF NOT EXISTS `index_trigger_evidence_source`"))
    }

    // ---- DatabaseOpenGuard (pure parts) ----

    @Test fun classifyCoversEveryCase() {
        val g = DatabaseOpenGuard
        assertEquals(DatabaseOpenGuard.Plan.Fresh, g.classify(0))
        assertEquals(DatabaseOpenGuard.Plan.UpToDate, g.classify(15))
        assertEquals(DatabaseOpenGuard.Plan.Upgrade(1), g.classify(1))
        assertEquals(DatabaseOpenGuard.Plan.Upgrade(14), g.classify(14))
        assertTrue(g.classify(16) is DatabaseOpenGuard.Plan.Unsupported)
        assertTrue(g.classify(-1) is DatabaseOpenGuard.Plan.Unsupported)
    }

    @Test fun everyShippedVersionIsAnUpgradeNeverUnsupported() {
        (JarvisMigrations.OLDEST_SUPPORTED_VERSION until JarvisMigrations.CURRENT_VERSION).forEach {
            assertEquals(DatabaseOpenGuard.Plan.Upgrade(it), DatabaseOpenGuard.classify(it))
        }
    }

    @Test fun snapshotNamesRoundTripAndPruneKeepsTheNewest() {
        val names = listOf(
            DatabaseOpenGuard.snapshotName(12, 1_000), DatabaseOpenGuard.snapshotName(13, 2_000),
            DatabaseOpenGuard.snapshotName(14, 3_000), "unrelated.txt",
        )
        assertEquals(12 to 1_000L, DatabaseOpenGuard.parse(names[0]))
        assertEquals(listOf(names[0]), DatabaseOpenGuard.toPrune(names, keep = 2))
        assertEquals(emptyList<String>(), DatabaseOpenGuard.toPrune(names, keep = 3))
    }

    private fun source(rel: String) = resolve("src/main/java/com/simone/jarvismobile/$rel").readText()

    private fun resolve(relative: String): File =
        listOf(File(relative), File("app/$relative")).firstOrNull { it.isFile }
            ?: error("Could not locate $relative from ${File(".").absolutePath}")
}
