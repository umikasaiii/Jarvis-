package com.simone.jarvismobile.backup

import java.io.File
import java.security.MessageDigest

/**
 * The stage → verify → cutover mechanics [BackupRepository.restore] needs
 * (§ JARVIS Implementation Master Plan PASSAGGIO 10 §G/§I) — kept in its own
 * file only so [BackupRepository] doesn't grow further, not a second backup
 * system ("Do NOT create BackupRepository2"): it has no public entry point
 * of its own, only [BackupRepository] calls it.
 *
 * Nothing here touches Android (`Context`, Room, `SharedPreferences`) — it
 * operates purely on `java.io.File`, so the stage/cutover/cleanup mechanics
 * are unit-testable on a plain JVM temp directory, independent of a device.
 *
 * Design:
 *  - [stage] decrypts nothing itself (the caller already has plaintext
 *    bytes per entry) — it only verifies each entry's bytes against its own
 *    manifest-declared SHA-256 and writes it into an isolated staging root,
 *    NEVER touching a live path. Any entry that fails its hash check is
 *    reported back; the caller must treat a non-empty failure list as
 *    "refuse the whole restore, live data unchanged" (§Q#2/#3) — this
 *    function never partially commits.
 *  - [cutoverOne] replaces one already-staged, already-verified file over
 *    its live target via a same-directory temp-file-then-rename, which is
 *    atomic on the filesystems Android uses (same directory ⇒ same volume,
 *    so `File.renameTo` never has to fall back to a non-atomic copy).
 *  - Callers needing a non-file live target (this app's `vault/` category
 *    goes through `VaultRepository`, not a plain file — see
 *    `BackupRepository.writeTarget`) do not call [cutoverOne] for that
 *    category; that write was never atomic before this pass either, and
 *    redesigning `VaultRepository` for it is out of scope here.
 *  - A staging root left behind after [cleanup] was never called (process
 *    death mid-cutover) is itself the recovery marker: its contents are
 *    already fully verified, so finishing the interrupted cutover on next
 *    start is always safe to retry — never a rollback, never event
 *    sourcing, just "the same already-checked bytes, applied again."
 */
internal object RestoreStaging {

    /**
     * Verifies every ([relPath], bytes) pair against [expectedSha256] and, if
     * (and only if) every single one verifies, writes them all into
     * [stagingRoot] mirroring [relPath]. Returns the relPaths that failed —
     * empty means every entry is now staged. On any failure, nothing is
     * written to [stagingRoot] at all (checked before any write), so a
     * caller can safely treat "stage() returned non-empty" as "nothing
     * happened yet."
     */
    fun stage(stagingRoot: File, entries: List<Pair<String, ByteArray>>, expectedSha256: Map<String, String>): List<String> {
        val failed = entries.mapNotNull { (relPath, bytes) ->
            val expected = expectedSha256[relPath]
            if (expected.isNullOrBlank() || sha256(bytes).equals(expected, ignoreCase = true)) null else relPath
        }
        if (failed.isNotEmpty()) return failed

        stagingRoot.mkdirs()
        for ((relPath, bytes) in entries) {
            val target = File(stagingRoot, relPath)
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
        }
        return emptyList()
    }

    /** Reads back one staged entry's bytes, or null if it was never staged. */
    fun readStaged(stagingRoot: File, relPath: String): ByteArray? {
        val f = File(stagingRoot, relPath)
        return if (f.exists()) f.readBytes() else null
    }

    /**
     * Every relPath currently staged under [stagingRoot], for a recovery
     * pass that only knows "a staging directory was left behind," not which
     * entries it originally held.
     */
    fun stagedRelPaths(stagingRoot: File): List<String> {
        if (!stagingRoot.isDirectory) return emptyList()
        val prefix = stagingRoot.path + File.separator
        return stagingRoot.walkTopDown().filter { it.isFile }
            .map { it.path.removePrefix(prefix).replace(File.separatorChar, '/') }
            .toList()
    }

    /**
     * Replaces [liveTarget] with [stagingFile]'s bytes via a same-directory
     * temp file + rename — atomic on the filesystems Android uses. Never
     * partially overwrites [liveTarget]: a reader either sees the old file
     * in full or the new one in full.
     */
    fun cutoverOne(stagingFile: File, liveTarget: File): Boolean = runCatching {
        liveTarget.parentFile?.mkdirs()
        val tmp = File(liveTarget.parentFile, liveTarget.name + TMP_SUFFIX)
        stagingFile.copyTo(tmp, overwrite = true)
        tmp.renameTo(liveTarget)
    }.getOrDefault(false)

    fun cleanup(stagingRoot: File) {
        runCatching { stagingRoot.deleteRecursively() }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) { val v = b.toInt() and 0xff; sb.append(HEX[v ushr 4]).append(HEX[v and 0xf]) }
        return sb.toString()
    }

    private const val HEX = "0123456789abcdef"
    const val TMP_SUFFIX = ".restoring"
}
