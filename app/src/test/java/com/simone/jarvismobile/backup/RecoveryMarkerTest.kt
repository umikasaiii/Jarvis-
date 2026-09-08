package com.simone.jarvismobile.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * § JARVIS Implementation Master Plan PASSAGGIO 10.3 §2/§3/§4 —
 * [RecoveryMarker] touches no Android API, only `java.io.File` and a plain
 * lambda, so its persist/ensure-cleared mechanics are exercised here
 * against real temp directories (same pattern as [RestoreStagingTest]).
 */
class RecoveryMarkerTest {

    private fun tempDir(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "jarvis-recovery-marker-test-$name-${System.nanoTime()}").apply { mkdirs() }

    // --- persist() --------------------------------------------------------

    @Test fun `persist creates a fresh marker and reports it durably present`() {
        val dir = tempDir("fresh")
        val marker = File(dir, ".sanitize_assistant_tasks")

        val ok = RecoveryMarker.persist(marker)

        assertTrue(ok)
        assertTrue(marker.exists())
        dir.deleteRecursively()
    }

    @Test fun `persist on an already-existing marker is idempotent and still reports true`() {
        val dir = tempDir("existing")
        val marker = File(dir, ".sanitize_assistant_tasks").apply { writeText("") }

        val ok = RecoveryMarker.persist(marker)

        assertTrue(ok)
        assertTrue(marker.exists())
        dir.deleteRecursively()
    }

    @Test fun `persist reports failure when the marker is genuinely still absent afterward`() {
        // Parent directory was never created — createNewFile() must throw
        // (no such file or directory), and persist() must not paper over
        // that: marker persistence is part of restore correctness, never a
        // best-effort diagnostic silently ignored.
        val neverCreatedParent = File(System.getProperty("java.io.tmpdir"), "jarvis-never-created-${System.nanoTime()}")
        val marker = File(neverCreatedParent, ".sanitize_assistant_tasks")

        val ok = RecoveryMarker.persist(marker)

        assertFalse(ok)
        assertFalse(marker.exists())
    }

    // --- ensureClearedByOpening() ------------------------------------------

    @Test fun `ensureClearedByOpening with no marker returns true and never invokes open`() {
        val dir = tempDir("absent")
        val marker = File(dir, ".sanitize_assistant_tasks") // never created
        var invoked = false

        val ok = RecoveryMarker.ensureClearedByOpening(marker) { invoked = true }

        assertTrue(ok)
        assertFalse("open() must never run when there was nothing to sanitize", invoked)
        dir.deleteRecursively()
    }

    @Test fun `ensureClearedByOpening reports success when open clears the marker`() {
        val dir = tempDir("cleared")
        val marker = File(dir, ".sanitize_assistant_tasks").apply { writeText("") }

        // Simulates RestoreSanitizeCallback.onOpen() succeeding and deleting the marker.
        val ok = RecoveryMarker.ensureClearedByOpening(marker) { marker.delete() }

        assertTrue(ok)
        assertFalse(marker.exists())
        dir.deleteRecursively()
    }

    @Test fun `ensureClearedByOpening reports failure when open runs but leaves the marker`() {
        val dir = tempDir("not-cleared")
        val marker = File(dir, ".sanitize_assistant_tasks").apply { writeText("") }

        // Simulates a swallowed sanitize failure inside the callback: the
        // open itself "succeeds" (never throws), but the marker survives.
        val ok = RecoveryMarker.ensureClearedByOpening(marker) { /* onOpen() ran, sanitize failed, marker untouched */ }

        assertFalse(ok)
        assertTrue("a failed sanitize must never silently look like a cleared marker", marker.exists())
        dir.deleteRecursively()
    }

    @Test fun `ensureClearedByOpening contains an exception thrown by open and still reports failure`() {
        val dir = tempDir("throws")
        val marker = File(dir, ".sanitize_assistant_tasks").apply { writeText("") }

        // A failed Room open must not crash cold start.
        val ok = RecoveryMarker.ensureClearedByOpening(marker) { throw IllegalStateException("boom") }

        assertFalse(ok)
        assertTrue(marker.exists())
        dir.deleteRecursively()
    }

    @Test fun `ensureClearedByOpening invokes open exactly once`() {
        val dir = tempDir("once")
        val marker = File(dir, ".sanitize_assistant_tasks").apply { writeText("") }
        var calls = 0

        RecoveryMarker.ensureClearedByOpening(marker) { calls++; marker.delete() }

        assertTrue("open() must run exactly once per call, never a retry loop inside a single attempt", calls == 1)
        dir.deleteRecursively()
    }
}
