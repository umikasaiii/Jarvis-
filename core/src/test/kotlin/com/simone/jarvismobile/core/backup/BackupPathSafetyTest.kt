package com.simone.jarvismobile.core.backup

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** § JARVIS Implementation Master Plan PASSAGGIO 10 §F/§G — relPath allow-list. */
class BackupPathSafetyTest {

    @Test fun realCategoryPathsAreSafe() {
        assertTrue(BackupPathSafety.isSafeRelPath("db/jarvis.db"))
        assertTrue(BackupPathSafety.isSafeRelPath("db/jarvis.db-wal"))
        assertTrue(BackupPathSafety.isSafeRelPath("datastore/jarvis_settings.preferences_pb"))
        assertTrue(BackupPathSafety.isSafeRelPath("vault/JARVIS/Memoria.md"))
        assertTrue(BackupPathSafety.isSafeRelPath("documents/photo.jpg"))
    }

    @Test fun parentDirectoryTraversalIsRejected() {
        assertFalse(BackupPathSafety.isSafeRelPath("../../../../data/data/other/app_data.db"))
        assertFalse(BackupPathSafety.isSafeRelPath("vault/../../etc/passwd"))
        assertFalse(BackupPathSafety.isSafeRelPath("documents/../../../secret"))
    }

    @Test fun absolutePathIsRejected() {
        assertFalse(BackupPathSafety.isSafeRelPath("/etc/passwd"))
        assertFalse(BackupPathSafety.isSafeRelPath("\\Windows\\System32"))
    }

    @Test fun windowsDriveLetterIsRejected() {
        assertFalse(BackupPathSafety.isSafeRelPath("C:\\Windows\\System32\\config"))
    }

    @Test fun blankOrCurrentDirSegmentIsRejected() {
        assertFalse(BackupPathSafety.isSafeRelPath(""))
        assertFalse(BackupPathSafety.isSafeRelPath("   "))
        assertFalse(BackupPathSafety.isSafeRelPath("vault/./Memoria.md"))
        assertFalse(BackupPathSafety.isSafeRelPath("vault//Memoria.md")) // empty segment
    }

    @Test fun nullByteIsRejected() {
        assertFalse(BackupPathSafety.isSafeRelPath("vault/Memoria.md\u0000.png"))
    }

    @Test fun aDeepButLegitimateSubpathIsStillSafe() {
        assertTrue(BackupPathSafety.isSafeRelPath("vault/JARVIS/Sub/Folder/Note.md"))
    }
}
