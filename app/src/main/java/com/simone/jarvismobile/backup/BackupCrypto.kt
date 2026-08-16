package com.simone.jarvismobile.backup

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AES-256-GCM encryption, parameterized by an explicit [SecretKey] rather than
 * always reaching for one key of its own. Two very different keys go through
 * this same pair of methods:
 *  - [localWrappingKey], a non-exportable Android Keystore key that never
 *    leaves this device — used only to wrap the backup content key for local
 *    storage between runs;
 *  - the backup content key itself ([BackupKeyManager.contentKey]), a
 *    software-held, *exportable* key that actually encrypts backup archives —
 *    exportable is the point, since it is what a recovery key restores on a
 *    new device, which a Keystore key by design can never do.
 * The 12-byte GCM IV is written as a length-prefixed header before the ciphertext.
 */
@Singleton
class BackupCrypto @Inject constructor() {

    /** The device's own non-exportable Keystore key; wraps the content key at rest only. */
    fun localWrappingKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    /** Encrypts [input] into [output] with [key]: [ivLen][iv][ciphertext+tag]. */
    fun encrypt(input: InputStream, output: OutputStream, key: SecretKey) {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key) }
        val iv = cipher.iv
        output.write(iv.size)
        output.write(iv)
        CipherOutputStream(output, cipher).use { cos -> input.copyTo(cos) }
    }

    /** Decrypts an [input] produced by [encrypt] with the same [key] into [output]. */
    fun decrypt(input: InputStream, output: OutputStream, key: SecretKey) {
        val ivLen = input.read()
        require(ivLen in 1..64) { "bad IV length" }
        val iv = ByteArray(ivLen)
        var read = 0
        while (read < ivLen) {
            val n = input.read(iv, read, ivLen - read)
            require(n >= 0) { "truncated IV" }
            read += n
        }
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
        CipherInputStream(input, cipher).use { cis -> cis.copyTo(output) }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "jarvis_backup_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
