package com.simone.jarvismobile.core.backup

/**
 * Validates a [BackupEntry.relPath] before it is ever used to build a live
 * restore target path (§ JARVIS Implementation Master Plan PASSAGGIO 10 §F/§G).
 *
 * `relPath` reaches restore code from the plaintext `manifest.json` — not
 * from inside the AES-GCM-authenticated archive — so, like [BackupId], it
 * must be treated as untrusted text a tampered external/cloud copy could
 * carry, never as something the archive's own encryption already vouches
 * for. A restore that built `File(context.filesDir, relPath)` directly from
 * an unvalidated value would let `../../../../whatever` (or an absolute
 * path) escape the app's private storage entirely.
 *
 * Rejects by construction rather than by blocking a specific substring:
 * blank, an absolute path (leading `/` or `\`), a Windows drive prefix
 * (`C:`), a null byte, and — after splitting on both `/` and `\` — any
 * segment that is empty, `.` or `..`. A safe path with these removed can
 * never resolve outside the directory it is joined against.
 */
object BackupPathSafety {

    fun isSafeRelPath(relPath: String): Boolean {
        if (relPath.isBlank()) return false
        if (relPath.contains('\u0000')) return false
        if (relPath.startsWith('/') || relPath.startsWith('\\')) return false
        if (relPath.length >= 2 && relPath[1] == ':') return false // e.g. "C:\..."
        val segments = relPath.split('/', '\\')
        return segments.none { it.isEmpty() || it == "." || it == ".." }
    }
}
