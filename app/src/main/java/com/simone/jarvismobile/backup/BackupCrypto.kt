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
 * AES-256-GCM encryption for backups, with the key held in the Android Keystore
 * (spec). The key never leaves the secure store — encryption/decryption go
 * through it — so a stolen backup file is useless without this device. The 12-byte
 * GCM IV is written as a length-prefixed header before the ciphertext.
 */
@Singleton
class BackupCrypto @Inject constructor() {

    private fun key(): SecretKey {
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

    /** Encrypts [input] into [output]: [ivLen][iv][ciphertext+tag]. */
    fun encrypt(input: InputStream, output: OutputStream) {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val iv = cipher.iv
        output.write(iv.size)
        output.write(iv)
        CipherOutputStream(output, cipher).use { cos -> input.copyTo(cos) }
    }

    /** Decrypts an [input] produced by [encrypt] into [output]. */
    fun decrypt(input: InputStream, output: OutputStream) {
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
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        }
        CipherInputStream(input, cipher).use { cis -> cis.copyTo(output) }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "jarvis_backup_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
