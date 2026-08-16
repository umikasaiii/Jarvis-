package com.simone.jarvismobile.backup

import android.content.Context
import com.simone.jarvismobile.core.backup.RecoveryKeyCodec
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import javax.crypto.spec.SecretKeySpec
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the one key that actually encrypts backup archives: a random 256-bit
 * key generated once per device, persisted locally wrapped by the Keystore key
 * ([BackupCrypto.localWrappingKey]) so restarting the app does not lose it.
 *
 * This key is deliberately *not* the Keystore key itself: Keystore-resident
 * keys cannot be exported by design, so a Keystore-only design could never
 * satisfy "restore an old cloud backup on a new device" — there would be
 * nothing to hand the new device. Wrapping a separate, software-held content
 * key is the standard envelope pattern for exactly this: local backups stay
 * as hardware-protected as before (the wrapping key never leaves Keystore),
 * while the content key can be exported once, deliberately, as a recovery key
 * the user is told to store somewhere safe — never inside the same cloud
 * archive it protects.
 */
@Singleton
class BackupKeyManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val crypto: BackupCrypto,
) {
    private val file: File get() = File(context.filesDir, "backup_dek.bin")

    @Volatile private var cached: SecretKey? = null

    /** The AES-256 key backup archives are encrypted with. Stable across runs. */
    @Synchronized
    fun contentKey(): SecretKey {
        cached?.let { return it }
        val key = loadWrapped() ?: generateAndStore()
        cached = key
        return key
    }

    /** The recovery key text for the current content key, to show the user once. */
    fun exportRecoveryKey(): String = RecoveryKeyCodec.encode(contentKey().encoded)

    /**
     * Replaces the content key with the one encoded in [recoveryKeyText]. Used on
     * a new device before a cloud restore, so archives encrypted with the old
     * device's key can be decrypted here. Returns false for a malformed or
     * mistyped key, leaving the current key untouched.
     */
    @Synchronized
    fun importRecoveryKey(recoveryKeyText: String): Boolean {
        val bytes = RecoveryKeyCodec.decode(recoveryKeyText) ?: return false
        val key = SecretKeySpec(bytes, "AES")
        saveWrapped(key)
        cached = key
        return true
    }

    private fun generateAndStore(): SecretKey {
        val bytes = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        val key = SecretKeySpec(bytes, "AES")
        saveWrapped(key)
        return key
    }

    private fun loadWrapped(): SecretKey? {
        if (!file.exists()) return null
        return runCatching {
            val plain = ByteArrayOutputStream()
            file.inputStream().use { crypto.decrypt(it, plain, crypto.localWrappingKey()) }
            SecretKeySpec(plain.toByteArray(), "AES")
        }.getOrNull()
    }

    private fun saveWrapped(key: SecretKey) {
        val plain = ByteArrayInputStream(key.encoded)
        file.outputStream().use { crypto.encrypt(plain, it, crypto.localWrappingKey()) }
    }

    private companion object {
        const val KEY_BYTES = 32
    }
}
