package com.simone.jarvismobile.core.backup

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackupCoreTest {

    private val base = Instant.parse("2026-08-10T12:00:00Z").toEpochMilli()
    private val day = 86_400_000L
    private fun daysAgo(n: Int) = base - n * day

    // --- manifest codec -----------------------------------------------------

    @Test fun manifestRoundTrips() {
        val manifest = BackupManifest(
            id = "backup-20260810-2330",
            createdAt = base,
            schemaVersion = ManifestCodec.SCHEMA_VERSION,
            appVersion = "1.2.3",
            status = BackupStatus.COMPLETED,
            totalSizeBytes = 4096,
            archiveSha256 = "abc123",
            entries = listOf(
                BackupEntry("Memoria.md", "vault/JARVIS/Memoria.md", 120, "h1"),
                BackupEntry("jarvis.db", "db/jarvis.db", 2048, "h2"),
                BackupEntry("Gemma", "models/gemma.litertlm", 0, "h3", EntryKind.MANIFEST_ONLY, sourceRef = "v1"),
            ),
        )
        val decoded = ManifestCodec.decode(ManifestCodec.encode(manifest))
        assertEquals(manifest, decoded)
        assertEquals(2, decoded.fileHashes().size) // manifest-only excluded
        assertEquals("h1", decoded.fileHashes()["vault/JARVIS/Memoria.md"])
    }

    @Test fun manifestEscapesStrings() {
        val m = BackupManifest(
            id = "id\"with\\quote", createdAt = 1, schemaVersion = 1, appVersion = "a\nb",
            status = BackupStatus.FAILED, totalSizeBytes = 0,
        )
        assertEquals(m, ManifestCodec.decode(ManifestCodec.encode(m)))
    }

    // --- incremental --------------------------------------------------------

    @Test fun incrementalCopiesOnlyChanged() {
        val previous = mapOf("a" to "h1", "b" to "h2", "c" to "h3")
        val current = mapOf("a" to "h1", "b" to "h2x", "d" to "h4")
        val plan = Incremental.plan(previous, current)
        assertEquals(listOf("b", "d"), plan.toCopy)
        assertEquals(listOf("a"), plan.unchanged)
        assertEquals(listOf("c"), plan.removed)
    }

    @Test fun incrementalFirstRunCopiesEverything() {
        val plan = Incremental.plan(emptyMap(), mapOf("a" to "h1", "b" to "h2"))
        assertEquals(listOf("a", "b"), plan.toCopy)
        assertTrue(plan.unchanged.isEmpty())
    }

    // --- retention (GFS) ----------------------------------------------------

    @Test fun retentionDailyKeepsMostRecentDistinctDays() {
        val backups = (0..4).map { BackupRef("d$it", daysAgo(it)) }
        val keep = Retention.keep(backups, RetentionPolicy(daily = 3, weekly = 0, monthly = 0))
        assertEquals(setOf("d0", "d1", "d2"), keep)
    }

    @Test fun retentionKeepsLatestBackupOfEachDay() {
        val early = BackupRef("early", Instant.parse("2026-08-10T06:00:00Z").toEpochMilli())
        val late = BackupRef("late", Instant.parse("2026-08-10T20:00:00Z").toEpochMilli())
        val keep = Retention.keep(listOf(early, late), RetentionPolicy(daily = 1, weekly = 0, monthly = 0))
        assertEquals(setOf("late"), keep) // one per day → the later one
    }

    @Test fun retentionUnionKeepsNewestAndRecentDaysAndIsDisjoint() {
        // Daily run for ~12 days plus a couple of older ones.
        val backups = (0..11).map { BackupRef("d$it", daysAgo(it)) } +
            listOf(BackupRef("old30", daysAgo(30)), BackupRef("old60", daysAgo(60)))
        val policy = RetentionPolicy() // 7/4/6
        val keep = Retention.keep(backups, policy)
        val prune = Retention.prune(backups, policy)

        assertTrue("d0" in keep, "newest always kept")
        (0..6).forEach { assertTrue("d$it" in keep, "recent daily kept: d$it") }
        // Kept and pruned partition the whole set with no overlap.
        assertTrue(keep.intersect(prune.toSet()).isEmpty())
        assertEquals(backups.map { it.id }.toSet(), keep + prune.toSet())
        // The monthly tier retains an older backup rather than dropping everything.
        assertTrue("old60" in keep || "old30" in keep)
    }

    @Test fun retentionEmptyIsEmpty() {
        assertTrue(Retention.keep(emptyList(), RetentionPolicy()).isEmpty())
    }
}
