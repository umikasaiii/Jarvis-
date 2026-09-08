package com.simone.jarvismobile.core.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** § JARVIS Implementation Master Plan PASSAGGIO 10.1 §10 — manifest metadata authentication. */
class ManifestAuthenticationTest {

    private val key = ByteArray(32) { it.toByte() }
    private val otherKey = ByteArray(32) { (it + 1).toByte() }

    private fun realEntry(relPath: String, sha: String, storedIn: String = "") =
        BackupEntry(name = relPath, relPath = relPath, sizeBytes = 10, sha256 = sha, storedInBackupId = storedIn)

    private fun manifest(
        id: String = "backup-20260910-120000",
        dbSchemaVersion: Int = 11,
        archiveSha256: String = "aa11",
        entries: List<BackupEntry> = listOf(
            realEntry("db/jarvis.db", "h1"),
            realEntry("vault/JARVIS/Memoria.md", "h2"),
        ),
        macVersion: Int = 0,
        mac: String = "",
    ) = BackupManifest(
        id = id, createdAt = 1L, schemaVersion = ManifestCodec.SCHEMA_VERSION, appVersion = "1.0",
        status = BackupStatus.COMPLETED, totalSizeBytes = 20, archiveSha256 = archiveSha256,
        dbSchemaVersion = dbSchemaVersion, manifestMacVersion = macVersion, manifestMac = mac, entries = entries,
    )

    private fun signed(base: BackupManifest, k: ByteArray = key): BackupManifest {
        val mac = ManifestAuthentication.computeMac(base, k)
        return base.copy(manifestMacVersion = ManifestAuthentication.CURRENT_VERSION, manifestMac = mac)
    }

    // --- basic round trip -----------------------------------------------

    @Test fun untamperedSignedManifestVerifies() {
        val m = signed(manifest())
        assertTrue(ManifestAuthentication.verify(m, key))
    }

    @Test fun canonicalBytesAreDeterministicRegardlessOfEntryOrder() {
        val a = manifest(entries = listOf(realEntry("db/jarvis.db", "h1"), realEntry("vault/JARVIS/Memoria.md", "h2")))
        val b = manifest(entries = listOf(realEntry("vault/JARVIS/Memoria.md", "h2"), realEntry("db/jarvis.db", "h1")))
        assertTrue(ManifestAuthentication.canonicalBytes(a).contentEquals(ManifestAuthentication.canonicalBytes(b)))
    }

    @Test fun manifestOnlyEntriesDoNotAffectTheMac() {
        val withoutManifestOnly = manifest()
        val withManifestOnly = withoutManifestOnly.copy(
            entries = withoutManifestOnly.entries + BackupEntry(
                name = "models", relPath = "models", sizeBytes = 0, sha256 = "irrelevant",
                kind = EntryKind.MANIFEST_ONLY, sourceRef = "/some/path",
            ),
        )
        assertEquals(
            ManifestAuthentication.computeMac(withoutManifestOnly, key),
            ManifestAuthentication.computeMac(withManifestOnly, key),
        )
    }

    // --- the adversarial case named explicitly by PASSAGGIO 10.1 §10 -----

    @Test fun crossBackupSubstitutionTamperIsDetected() {
        // Attacker redirects one entry to a different genuine backup's real
        // sha256/storedInBackupId, WITHOUT the content key to recompute a
        // valid MAC for the edited manifest.
        val original = signed(manifest())
        val tampered = original.copy(
            entries = original.entries.map {
                if (it.relPath == "db/jarvis.db") it.copy(sha256 = "some-other-backups-real-hash", storedInBackupId = "backup-20260101-000000")
                else it
            },
        )
        assertFalse(ManifestAuthentication.verify(tampered, key))
    }

    @Test fun tamperingRelPathIsDetected() {
        val original = signed(manifest())
        val tampered = original.copy(entries = original.entries.map { it.copy(relPath = it.relPath + "x") })
        assertFalse(ManifestAuthentication.verify(tampered, key))
    }

    @Test fun tamperingIdIsDetected() {
        val original = signed(manifest())
        val tampered = original.copy(id = "backup-20990101-000000")
        assertFalse(ManifestAuthentication.verify(tampered, key))
    }

    @Test fun tamperingDbSchemaVersionIsDetected() {
        val original = signed(manifest())
        val tampered = original.copy(dbSchemaVersion = 999)
        assertFalse(ManifestAuthentication.verify(tampered, key))
    }

    @Test fun tamperingArchiveSha256IsDetected() {
        val original = signed(manifest())
        val tampered = original.copy(archiveSha256 = "bb22")
        assertFalse(ManifestAuthentication.verify(tampered, key))
    }

    @Test fun wrongKeyFailsVerification() {
        val original = signed(manifest())
        assertFalse(ManifestAuthentication.verify(original, otherKey))
    }

    // --- legacy policy -----------------------------------------------------

    @Test fun legacyManifestVersionZeroNeverVerifies() {
        val legacy = manifest(macVersion = 0, mac = "")
        assertFalse(ManifestAuthentication.verify(legacy, key))
    }

    @Test fun blankMacWithNonZeroVersionNeverVerifies() {
        val malformed = manifest(macVersion = ManifestAuthentication.CURRENT_VERSION, mac = "")
        assertFalse(ManifestAuthentication.verify(malformed, key))
    }

    @Test fun computeMacIsStableForTheSameInputs() {
        val m = manifest()
        assertEquals(ManifestAuthentication.computeMac(m, key), ManifestAuthentication.computeMac(m, key))
    }
}
