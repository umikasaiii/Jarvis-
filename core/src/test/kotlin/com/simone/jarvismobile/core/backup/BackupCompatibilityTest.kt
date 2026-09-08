package com.simone.jarvismobile.core.backup

import kotlin.test.Test
import kotlin.test.assertEquals

/** § JARVIS Implementation Master Plan PASSAGGIO 10 §H — compatibility classification. */
class BackupCompatibilityTest {

    private fun classify(
        manifestSchemaVersion: Int = ManifestCodec.SCHEMA_VERSION,
        status: BackupStatus = BackupStatus.COMPLETED,
        archiveSha256: String = "abc123",
        hasDbCategory: Boolean = true,
        dbSchemaVersion: Int = 11,
        minSupportedDbSchemaVersion: Int = 3,
        currentDbSchemaVersion: Int = 11,
    ) = BackupCompatibilityClassifier.classify(
        manifestSchemaVersion = manifestSchemaVersion,
        status = status,
        archiveSha256 = archiveSha256,
        hasDbCategory = hasDbCategory,
        dbSchemaVersion = dbSchemaVersion,
        minSupportedDbSchemaVersion = minSupportedDbSchemaVersion,
        currentDbSchemaVersion = currentDbSchemaVersion,
    )

    @Test fun currentEverythingIsSupportedCurrent() {
        assertEquals(BackupCompatibility.SUPPORTED_CURRENT, classify())
    }

    @Test fun noDbCategoryNeverChecksDbVersion() {
        assertEquals(
            BackupCompatibility.SUPPORTED_CURRENT,
            classify(hasDbCategory = false, dbSchemaVersion = 0),
        )
    }

    @Test fun olderSupportedDbSchemaMigratesViaExistingRoomMigrations() {
        assertEquals(BackupCompatibility.SUPPORTED_OLDER, classify(dbSchemaVersion = 9))
    }

    @Test fun futureManifestFormatIsUnsupportedFuture() {
        assertEquals(
            BackupCompatibility.UNSUPPORTED_FUTURE,
            classify(manifestSchemaVersion = ManifestCodec.SCHEMA_VERSION + 1),
        )
    }

    @Test fun futureDbSchemaIsUnsupportedFuture() {
        assertEquals(BackupCompatibility.UNSUPPORTED_FUTURE, classify(dbSchemaVersion = 999))
    }

    @Test fun tooOldDbSchemaBelowTheEarliestRegisteredMigrationIsUnsupportedTooOld() {
        assertEquals(BackupCompatibility.UNSUPPORTED_TOO_OLD, classify(dbSchemaVersion = 1))
    }

    @Test fun zeroOrLessManifestSchemaVersionIsCorrupt() {
        assertEquals(BackupCompatibility.CORRUPT, classify(manifestSchemaVersion = 0))
        assertEquals(BackupCompatibility.CORRUPT, classify(manifestSchemaVersion = -1))
    }

    @Test fun nonCompletedStatusIsIncomplete() {
        assertEquals(BackupCompatibility.INCOMPLETE, classify(status = BackupStatus.FAILED))
        assertEquals(BackupCompatibility.INCOMPLETE, classify(status = BackupStatus.RUNNING))
        assertEquals(BackupCompatibility.INCOMPLETE, classify(status = BackupStatus.PENDING))
    }

    @Test fun blankArchiveHashIsIncomplete() {
        assertEquals(BackupCompatibility.INCOMPLETE, classify(archiveSha256 = ""))
    }

    @Test fun unknownDbVersionPredatingThisFieldIsTrustedNotRefused() {
        // dbSchemaVersion=0 means "this manifest predates the field" (every
        // backup made before this check existed) — must NOT be refused for
        // metadata that simply did not exist yet; Room's own registered
        // migrations handle it exactly as they did before this check, i.e.
        // proceed exactly as if no version gate applied at all.
        assertEquals(BackupCompatibility.SUPPORTED_CURRENT, classify(dbSchemaVersion = 0))
    }

    @Test fun corruptManifestFormatWinsOverEveryOtherCheck() {
        // A manifest that fails the most basic sanity check is refused before
        // any of the more specific classifications are even considered.
        assertEquals(
            BackupCompatibility.CORRUPT,
            classify(manifestSchemaVersion = -5, status = BackupStatus.FAILED, dbSchemaVersion = 999),
        )
    }
}
