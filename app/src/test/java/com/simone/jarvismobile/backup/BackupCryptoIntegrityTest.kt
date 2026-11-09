package com.simone.jarvismobile.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * BETA 7 — the encryption format behind every backup archive, exercised with software AES keys (the Android Keystore is
 * only used to wrap the content key and cannot run on a JVM). Proves: round trip, wrong key refused, corrupted or
 * truncated archive refused, and that a refused decryption never reports success. Whether the Keystore wrapping works on
 * the Honor 200 is a physical test.
 */
class BackupCryptoIntegrityTest {
    private val crypto = BackupCrypto()
    private fun key(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val payload = ByteArray(200_000) { (it * 31 + 7).toByte() }

    private fun encrypt(k: SecretKey, data: ByteArray = payload): ByteArray =
        ByteArrayOutputStream().also { crypto.encrypt(ByteArrayInputStream(data), it, k) }.toByteArray()

    private fun decryptOrNull(bytes: ByteArray, k: SecretKey): ByteArray? = try {
        ByteArrayOutputStream().also { crypto.decrypt(ByteArrayInputStream(bytes), it, k) }.toByteArray()
    } catch (e: Exception) {
        null
    }

    @Test fun roundTripWithTheRightKey() {
        val k = key()
        assertArrayEquals(payload, decryptOrNull(encrypt(k), k))
    }

    @Test fun aWrongKeyIsRefused() {
        assertEquals(null, decryptOrNull(encrypt(key()), key()))
    }

    @Test fun aFlippedByteInTheMiddleIsRefused() {
        val k = key()
        val enc = encrypt(k)
        enc[enc.size / 2] = (enc[enc.size / 2].toInt() xor 0x01).toByte()
        assertEquals(null, decryptOrNull(enc, k))
    }

    @Test fun aFlippedByteInTheTagIsRefused() {
        val k = key()
        val enc = encrypt(k)
        enc[enc.size - 1] = (enc[enc.size - 1].toInt() xor 0x80).toByte()
        assertEquals(null, decryptOrNull(enc, k))
    }

    @Test fun aTruncatedArchiveIsRefused() {
        val k = key()
        val enc = encrypt(k)
        assertEquals(null, decryptOrNull(enc.copyOf(enc.size - 40), k))
        assertEquals(null, decryptOrNull(enc.copyOf(10), k))
    }

    @Test fun anEmptyOrGarbageFileIsRefused() {
        val k = key()
        assertEquals(null, decryptOrNull(ByteArray(0), k))
        assertEquals(null, decryptOrNull(ByteArray(64) { 0x41 }, k))
    }

    @Test fun everyEncryptionUsesAFreshIv() {
        val k = key()
        val a = encrypt(k, payload.copyOf(64)); val b = encrypt(k, payload.copyOf(64))
        assertTrue(!a.contentEquals(b))
    }

    @Test fun theCiphertextDoesNotContainThePlaintext() {
        val k = key()
        val secret = "IBAN IT60X0542811101000000123456".toByteArray()
        val enc = encrypt(k, secret)
        assertTrue(String(enc, Charsets.ISO_8859_1).indexOf("IBAN") < 0)
        try { decryptOrNull(enc, k)?.let { assertArrayEquals(secret, it) } ?: fail("round trip failed") } catch (e: Exception) { fail() }
    }
}
