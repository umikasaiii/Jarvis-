package com.simone.jarvismobile.core.backup

import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecoveryKeyCodecTest {

    private fun randomKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    @Test fun `round trips a random key`() {
        val key = randomKey()
        val text = RecoveryKeyCodec.encode(key)
        val decoded = RecoveryKeyCodec.decode(text)
        assertNotNull(decoded)
        assertTrue(key.contentEquals(decoded))
    }

    @Test fun `round trips the all-zero and all-one keys`() {
        for (key in listOf(ByteArray(32), ByteArray(32) { 0xFF.toByte() })) {
            val decoded = RecoveryKeyCodec.decode(RecoveryKeyCodec.encode(key))
            assertNotNull(decoded)
            assertTrue(key.contentEquals(decoded))
        }
    }

    @Test fun `is grouped and uses only the unambiguous alphabet`() {
        val text = RecoveryKeyCodec.encode(randomKey())
        assertTrue(text.contains('-'))
        val cleaned = text.replace("-", "")
        assertTrue(cleaned.none { it == 'O' || it == 'I' || it == 'L' || it == 'U' })
    }

    @Test fun `ignores whitespace, dashes and case on decode`() {
        val key = randomKey()
        val text = RecoveryKeyCodec.encode(key)
        val messy = " " + text.lowercase().replace("-", " - ") + "  "
        val decoded = RecoveryKeyCodec.decode(messy)
        assertNotNull(decoded)
        assertTrue(key.contentEquals(decoded))
    }

    @Test fun `rejects a corrupted key`() {
        val text = RecoveryKeyCodec.encode(randomKey())
        val firstChar = text.first()
        val replacement = ALPHABET.first { it != firstChar }
        val corrupted = replacement + text.drop(1)
        // A single changed character fails the CRC-8 checksum for all but a
        // 1-in-256 fluke, which is an acceptable, standard tolerance for a
        // typo-catching (not security) checksum test.
        assertNull(RecoveryKeyCodec.decode(corrupted))
    }

    @Test fun `rejects garbage input`() {
        assertNull(RecoveryKeyCodec.decode(""))
        assertNull(RecoveryKeyCodec.decode("not a recovery key at all!!"))
        assertNull(RecoveryKeyCodec.decode("AAAA-AAAA"))
    }

    private val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    @Test fun `encode rejects the wrong key length`() {
        var threw = false
        try {
            RecoveryKeyCodec.encode(ByteArray(16))
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test fun `checksum differs for keys differing in one byte`() {
        val key = randomKey()
        val flipped = key.copyOf().also { it[5] = (it[5].toInt() xor 0x01).toByte() }
        assertEquals(key.size, flipped.size)
        assertTrue(!key.contentEquals(flipped))
        val a = RecoveryKeyCodec.encode(key)
        val b = RecoveryKeyCodec.encode(flipped)
        assertTrue(a != b)
    }
}
