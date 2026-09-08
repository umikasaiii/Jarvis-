package com.simone.jarvismobile.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * § JARVIS Implementation Master Plan PASSAGGIO 10 §G/§I/§Q — [RestoreStaging]
 * touches no Android API, only `java.io.File`, so its stage/cutover/cleanup
 * mechanics are exercised here against real temp directories.
 */
class RestoreStagingTest {

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun tempDir(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "jarvis-restore-test-$name-${System.nanoTime()}").apply { mkdirs() }

    @Test fun stageWritesOnlyWhenEveryEntryVerifies() {
        val staging = tempDir("ok")
        val bytesA = "hello".toByteArray()
        val bytesB = "world".toByteArray()
        val entries = listOf("db/jarvis.db" to bytesA, "vault/JARVIS/Memoria.md" to bytesB)
        val expected = mapOf("db/jarvis.db" to sha256(bytesA), "vault/JARVIS/Memoria.md" to sha256(bytesB))

        val failed = RestoreStaging.stage(staging, entries, expected)

        assertTrue(failed.isEmpty())
        assertEquals("hello", RestoreStaging.readStaged(staging, "db/jarvis.db")!!.toString(Charsets.UTF_8))
        assertEquals("world", RestoreStaging.readStaged(staging, "vault/JARVIS/Memoria.md")!!.toString(Charsets.UTF_8))
        RestoreStaging.cleanup(staging)
    }

    @Test fun stageWritesNothingWhenAnyEntryFailsItsOwnChecksum() {
        val staging = tempDir("mismatch")
        val bytesA = "hello".toByteArray()
        val bytesB = "world".toByteArray()
        val entries = listOf("db/jarvis.db" to bytesA, "vault/JARVIS/Memoria.md" to bytesB)
        // "db/jarvis.db"'s declared hash does not match its real bytes.
        val expected = mapOf("db/jarvis.db" to "0000", "vault/JARVIS/Memoria.md" to sha256(bytesB))

        val failed = RestoreStaging.stage(staging, entries, expected)

        assertEquals(listOf("db/jarvis.db"), failed)
        // Nothing was written at all — not even the entry that DID verify —
        // matching "refuse the whole restore," never a partial commit.
        assertNull(RestoreStaging.readStaged(staging, "db/jarvis.db"))
        assertNull(RestoreStaging.readStaged(staging, "vault/JARVIS/Memoria.md"))
        RestoreStaging.cleanup(staging)
    }

    @Test fun cutoverOneReplacesTheLiveFileAtomically() {
        val staging = tempDir("cutover-src")
        val live = tempDir("cutover-live")
        val stagedFile = File(staging, "jarvis.db").apply { writeBytes("new-content".toByteArray()) }
        val liveTarget = File(live, "jarvis.db").apply { writeBytes("old-content".toByteArray()) }

        val ok = RestoreStaging.cutoverOne(stagedFile, liveTarget)

        assertTrue(ok)
        assertEquals("new-content", liveTarget.readText())
        // No leftover temp file in the live directory after a successful cutover.
        assertFalse(File(live, "jarvis.db${RestoreStaging.TMP_SUFFIX}").exists())
        RestoreStaging.cleanup(staging)
        live.deleteRecursively()
    }

    @Test fun cutoverOneCreatesANewLiveFileWhenNoneExistedBefore() {
        val staging = tempDir("cutover-new-src")
        val live = tempDir("cutover-new-live")
        val stagedFile = File(staging, "photo.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val liveTarget = File(File(live, "documents"), "photo.jpg") // parent doesn't exist yet

        val ok = RestoreStaging.cutoverOne(stagedFile, liveTarget)

        assertTrue(ok)
        assertTrue(liveTarget.exists())
        assertEquals(listOf<Byte>(1, 2, 3), liveTarget.readBytes().toList())
        RestoreStaging.cleanup(staging)
        live.deleteRecursively()
    }

    @Test fun stagedRelPathsListsEveryFileMirroringItsRelPath() {
        val staging = tempDir("list")
        val bytesA = byteArrayOf(1)
        val bytesB = byteArrayOf(2)
        RestoreStaging.stage(
            staging,
            listOf("db/jarvis.db" to bytesA, "vault/JARVIS/Sub/Note.md" to bytesB),
            mapOf("db/jarvis.db" to sha256(bytesA), "vault/JARVIS/Sub/Note.md" to sha256(bytesB)),
        )

        val relPaths = RestoreStaging.stagedRelPaths(staging).toSet()

        assertEquals(setOf("db/jarvis.db", "vault/JARVIS/Sub/Note.md"), relPaths)
        RestoreStaging.cleanup(staging)
    }

    // --- § JARVIS Implementation Master Plan PASSAGGIO 10.1 §1 ---------------
    // "NO EXPECTED HASH != VERIFIED ENTRY": a missing, blank or malformed
    // declared hash must be a FAILURE, never a free pass.

    @Test fun stageFailsEveryEntryWhenNoHashesAreDeclaredAtAll() {
        val staging = tempDir("no-hashes")
        val entries = listOf("db/jarvis.db" to byteArrayOf(1), "vault/JARVIS/Note.md" to byteArrayOf(2))

        val failed = RestoreStaging.stage(staging, entries, emptyMap())

        assertEquals(setOf("db/jarvis.db", "vault/JARVIS/Note.md"), failed.toSet())
        assertNull(RestoreStaging.readStaged(staging, "db/jarvis.db"))
        RestoreStaging.cleanup(staging)
    }

    @Test fun stageFailsAnEntryWithABlankDeclaredHash() {
        val staging = tempDir("blank-hash")
        val bytes = "hello".toByteArray()
        val entries = listOf("db/jarvis.db" to bytes)

        val failed = RestoreStaging.stage(staging, entries, mapOf("db/jarvis.db" to "   "))

        assertEquals(listOf("db/jarvis.db"), failed)
        assertNull(RestoreStaging.readStaged(staging, "db/jarvis.db"))
        RestoreStaging.cleanup(staging)
    }

    @Test fun stageFailsAnEntryWithAMalformedDeclaredHash() {
        val staging = tempDir("malformed-hash")
        val bytes = "hello".toByteArray()
        // Right length family of mistake avoided on purpose: not 64 hex chars.
        val entries = listOf("db/jarvis.db" to bytes)

        val failed = RestoreStaging.stage(staging, entries, mapOf("db/jarvis.db" to "not-a-real-hash"))

        assertEquals(listOf("db/jarvis.db"), failed)
        RestoreStaging.cleanup(staging)
    }

    @Test fun stageFailsAnEntryWhoseDeclaredHashHasWrongLength() {
        val staging = tempDir("short-hash")
        val bytes = "hello".toByteArray()
        val entries = listOf("db/jarvis.db" to bytes)
        // A truncated real hash — 63 hex chars — must still fail, not be
        // accepted as "close enough."
        val truncated = sha256(bytes).dropLast(1)

        val failed = RestoreStaging.stage(staging, entries, mapOf("db/jarvis.db" to truncated))

        assertEquals(listOf("db/jarvis.db"), failed)
        RestoreStaging.cleanup(staging)
    }

    @Test fun stageAcceptsAWellFormedValidHashRegardlessOfCase() {
        val staging = tempDir("upper-hash")
        val bytes = "hello".toByteArray()
        val entries = listOf("db/jarvis.db" to bytes)

        val failed = RestoreStaging.stage(staging, entries, mapOf("db/jarvis.db" to sha256(bytes).uppercase()))

        assertTrue(failed.isEmpty())
        assertEquals("hello", RestoreStaging.readStaged(staging, "db/jarvis.db")!!.toString(Charsets.UTF_8))
        RestoreStaging.cleanup(staging)
    }

    @Test fun stagedFileReturnsTheFileOnlyWhenStaged() {
        val staging = tempDir("staged-file")
        val bytes = "hello".toByteArray()
        RestoreStaging.stage(staging, listOf("db/jarvis.db" to bytes), mapOf("db/jarvis.db" to sha256(bytes)))

        assertTrue(RestoreStaging.stagedFile(staging, "db/jarvis.db") != null)
        assertNull(RestoreStaging.stagedFile(staging, "db/never_staged.db"))
        RestoreStaging.cleanup(staging)
    }

    @Test fun stagedRelPathsOfAMissingDirectoryIsEmpty() {
        val neverCreated = File(System.getProperty("java.io.tmpdir"), "jarvis-never-${System.nanoTime()}")
        assertTrue(RestoreStaging.stagedRelPaths(neverCreated).isEmpty())
    }

    @Test fun cleanupRemovesTheEntireStagingTreeAndIsSafeToCallTwice() {
        val staging = tempDir("cleanup")
        File(staging, "a.txt").writeText("x")
        RestoreStaging.cleanup(staging)
        assertFalse(staging.exists())
        RestoreStaging.cleanup(staging) // second call on an already-gone dir must not throw
    }

    @Test fun readStagedOfAnUnstagedEntryIsNull() {
        val staging = tempDir("read-miss")
        assertNull(RestoreStaging.readStaged(staging, "db/never_staged.db"))
        RestoreStaging.cleanup(staging)
    }
}
