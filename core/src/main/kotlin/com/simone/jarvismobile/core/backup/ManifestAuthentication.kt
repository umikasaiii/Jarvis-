package com.simone.jarvismobile.core.backup

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Cryptographically binds the restore-relevant fields of a [BackupManifest]
 * to the backup's own content key (§ JARVIS Implementation Master Plan
 * PASSAGGIO 10.1 §10) — manifest.json is plaintext, mirrored to external/
 * cloud storage the user controls, and is NOT covered by the archive's own
 * AES-GCM authentication (that only protects `backup.enc`). Without this, a
 * tamperer with write access to that plaintext copy could redirect one
 * entry's `storedInBackupId` to a different, genuinely existing backup and
 * copy that backup's real `sha256` for the same `relPath` — restore would
 * then silently substitute a wrong-generation file for one it believes is
 * current, purely by editing text nobody needs the encryption key to touch.
 *
 * HMAC-SHA256 — a standard, well-vetted authenticated primitive already
 * built into the JDK, not a new protocol — over a deterministic canonical
 * byte form of exactly the fields a restore trusts to decide what to read
 * and where to write it: the manifest id, dbSchemaVersion, archiveSha256,
 * and per FILE entry its relPath/sha256/storedInBackupId. MANIFEST_ONLY
 * entries are excluded — they never drive a live write (PASSAGGIO 10's own
 * "never copied" contract), so binding them adds nothing to the actual
 * trust boundary.
 *
 * [BackupManifest.manifestMacVersion] `== 0` is the explicit LEGACY marker
 * for every backup written before this existed: [verify] always returns
 * `false` for it (there is nothing to verify), and callers must branch on
 * `manifestMacVersion` themselves to apply the documented backward-
 * compatible policy — accept it, but never present it as authenticated.
 */
object ManifestAuthentication {

    /** The current HMAC scheme version. Bump only if the canonical byte layout below changes. */
    const val CURRENT_VERSION = 1
    private const val ALGORITHM = "HmacSHA256"
    private const val HEX = "0123456789abcdef"

    /** A field separator that can never appear inside a relPath/sha256/id — keeps the canonical form unambiguous. */
    private val FIELD_SEP = Char(1)

    /** The exact bytes [computeMac]/[verify] authenticate — deterministic regardless of entry insertion order. */
    fun canonicalBytes(manifest: BackupManifest): ByteArray = buildString {
        append(manifest.id).append('\n')
        append(manifest.dbSchemaVersion).append('\n')
        append(manifest.archiveSha256).append('\n')
        manifest.entries
            .filter { it.kind == EntryKind.FILE }
            .sortedBy { it.relPath }
            .forEach { e ->
                append(e.relPath).append(FIELD_SEP)
                append(e.sha256).append(FIELD_SEP)
                append(e.storedInBackupId).append('\n')
            }
    }.toByteArray(Charsets.UTF_8)

    /** HMAC-SHA256 of [canonicalBytes], hex-encoded, using [keyBytes] as the MAC key. */
    fun computeMac(manifest: BackupManifest, keyBytes: ByteArray): String {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(keyBytes, ALGORITHM))
        return mac.doFinal(canonicalBytes(manifest)).toHex()
    }

    /**
     * True only when [manifest] declares a real ([CURRENT_VERSION]-or-newer)
     * MAC AND that MAC matches what [keyBytes] recomputes right now. A
     * LEGACY manifest (`manifestMacVersion == 0`) always returns `false` —
     * it was never authenticated, so there is nothing to confirm; callers
     * must check `manifestMacVersion` themselves to allow it under the
     * explicit legacy policy instead of treating a `false` here as tamper
     * detection.
     */
    fun verify(manifest: BackupManifest, keyBytes: ByteArray): Boolean {
        if (manifest.manifestMacVersion < 1 || manifest.manifestMac.isBlank()) return false
        return constantTimeEquals(computeMac(manifest, keyBytes), manifest.manifestMac)
    }

    /** Avoids a timing side-channel on the comparison itself — cheap insurance, not the primary defense. */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) { val v = b.toInt() and 0xff; sb.append(HEX[v ushr 4]).append(HEX[v and 0xf]) }
        return sb.toString()
    }
}
