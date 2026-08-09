package com.simone.jarvismobile.core.document

import java.security.MessageDigest

/**
 * SHA-256 hashing for duplicate detection. Two imports of the same bytes get the
 * same checksum, so a re-import can offer "use existing / import anyway / cancel"
 * instead of silently indexing the file twice. `MessageDigest` is part of the
 * JVM/Android platform, so this stays in `:core` and is unit-tested.
 */
object Checksums {

    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    fun sha256(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))

    private const val HEX = "0123456789abcdef"
}

/**
 * A filesystem-safe file name for a vault copy: no path separators, no traversal,
 * no control characters, length-bounded. The original file is never touched; this
 * only names the *copy* placed in the vault, so a hostile name cannot escape the
 * import directory.
 */
object SafeFileName {
    private val ILLEGAL = Regex("[\\\\/:*?\"<>|]")
    private val CONTROL = Regex("[\\u0000-\\u001F]")
    private val WHITESPACE = Regex("\\s+")
    private const val MAX = 120

    fun of(raw: String, fallback: String = "documento"): String {
        var name = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        name = name
            .replace(CONTROL, "")
            .replace(ILLEGAL, "_")
            .replace(WHITESPACE, "_")
            .replace(Regex("\\.{2,}"), ".")
            .trim('.', '_')
        if (name.isBlank()) name = fallback
        if (name.length > MAX) {
            val ext = name.substringAfterLast('.', "")
            val stem = name.substringBeforeLast('.').take(MAX - ext.length - 1)
            name = if (ext.isBlank()) stem else "$stem.$ext"
        }
        return name
    }

    /** Adds a short checksum suffix so two different files sharing a name coexist. */
    fun uniqueOf(raw: String, checksum: String, fallback: String = "documento"): String {
        val base = of(raw, fallback)
        val suffix = checksum.take(8)
        val ext = base.substringAfterLast('.', "")
        val stem = if (ext.isBlank()) base else base.substringBeforeLast('.')
        return if (ext.isBlank()) "${stem}_$suffix" else "${stem}_$suffix.$ext"
    }
}
