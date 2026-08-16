package com.simone.jarvismobile.core.backup

import java.io.ByteArrayOutputStream

/**
 * Turns the 32-byte backup content key into a string a person can write down,
 * read aloud, or paste back in on a new device — and back again.
 *
 * The Keystore key that protects a backup archive at rest on *this* device
 * cannot be exported by design (that is the point of hardware-backed
 * Keystore); the content key this codec encodes is a separate, software-held
 * key the archive is actually encrypted with; the Keystore key only wraps it
 * for local storage. Losing the recovery key does not weaken today's device —
 * it only means a future device cannot read old cloud backups without it.
 *
 * Base32 (RFC 4648, no padding) keeps every character unambiguous when
 * hand-copied (no 0/O or 1/I/l confusion); the appended CRC-8 catches a typo
 * before it silently produces the wrong key instead of failing loudly.
 */
object RecoveryKeyCodec {

    // Crockford's Base32 alphabet: digits 0-9 plus A-Z with I, L, O and U left
    // out (each easily confused with 1, 1, 0 and V respectively when hand-copied
    // or read aloud) — unlike plain RFC 4648 base32, which keeps all of them.
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val KEY_BYTES = 32
    private const val GROUP_SIZE = 5

    /** Encodes a 32-byte content key into a grouped, checksummed recovery string. */
    fun encode(key: ByteArray): String {
        require(key.size == KEY_BYTES) { "recovery key must wrap a $KEY_BYTES-byte key" }
        val withChecksum = key + crc8(key)
        return group(base32Encode(withChecksum))
    }

    /**
     * Parses a string produced by [encode] — whitespace, dashes and case are all
     * ignored. Returns null for anything malformed or whose checksum does not
     * match, rather than returning a key that silently decrypts nothing.
     */
    fun decode(text: String): ByteArray? {
        val cleaned = text.filter { !it.isWhitespace() && it != '-' }.uppercase()
        if (cleaned.isEmpty() || cleaned.any { it !in ALPHABET }) return null
        val bytes = base32Decode(cleaned) ?: return null
        if (bytes.size < KEY_BYTES + 1) return null
        val key = bytes.copyOfRange(0, KEY_BYTES)
        val checksum = bytes[KEY_BYTES]
        return if (checksum == crc8(key)) key else null
    }

    private fun group(s: String): String = s.chunked(GROUP_SIZE).joinToString("-")

    private fun base32Encode(data: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0L
        var bitsLeft = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toLong() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                bitsLeft -= 5
                sb.append(ALPHABET[((buffer shr bitsLeft) and 0x1F).toInt()])
            }
        }
        if (bitsLeft > 0) {
            sb.append(ALPHABET[((buffer shl (5 - bitsLeft)) and 0x1F).toInt()])
        }
        return sb.toString()
    }

    private fun base32Decode(s: String): ByteArray? {
        val out = ByteArrayOutputStream()
        var buffer = 0L
        var bitsLeft = 0
        for (c in s) {
            val index = ALPHABET.indexOf(c)
            if (index < 0) return null
            buffer = (buffer shl 5) or index.toLong()
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                out.write(((buffer shr bitsLeft) and 0xFF).toInt())
            }
        }
        return out.toByteArray()
    }

    /** CRC-8 (poly 0x07) — catches a mistyped key; not a security control. */
    private fun crc8(data: ByteArray): Byte {
        var crc = 0
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF
            }
        }
        return crc.toByte()
    }
}
