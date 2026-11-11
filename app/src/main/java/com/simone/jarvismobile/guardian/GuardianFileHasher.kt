package com.simone.jarvismobile.guardian

import android.content.Context
import android.net.Uri
import com.simone.jarvismobile.core.guardian.GuardianPolicy
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class GuardianFileHash(val bytes: Long, val sha256: String)

/**
 * A fingerprint, NOT malware detection. Only the Uri explicitly selected in the Android
 * document picker is opened. No broad storage permission, persistence, or network.
 */
class GuardianFileHasher(private val context: Context) {
    suspend fun sha256(uri: Uri): GuardianFileHash = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Il file non è leggibile.")
        stream.use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(32 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                total += read
                if (!GuardianPolicy.fileSizeAllowed(total)) {
                    throw IllegalArgumentException("File oltre il limite di 128 MiB. Non analizzato.")
                }
                digest.update(buffer, 0, read)
            }
            val hex = digest.digest().joinToString("") { byte ->
                (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
            }
            GuardianFileHash(total, hex)
        }
    }
}
