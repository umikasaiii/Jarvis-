package com.simone.jarvismobile.audio.silero

import com.simone.jarvismobile.core.voice.silero.SileroFailure
import com.simone.jarvismobile.core.voice.silero.SileroGraphContract
import com.simone.jarvismobile.core.voice.silero.SileroImportValidator
import com.simone.jarvismobile.core.voice.silero.SileroModelManifest
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

sealed interface SileroImportOutcome {
    data class Ok(val file: File) : SileroImportOutcome
    data class Rejected(val failure: SileroFailure) : SileroImportOutcome
}

/**
 * App-private home of the user-imported Silero model (never bundled, never in Room, never in shared storage).
 * Layout: `<base>/silero_vad/<modelId>/silero_vad.onnx`; imports stage in `<base>/silero_vad/tmp/`.
 * Pure java.io: the model is promoted only after size, SHA-256 and graph checks all pass, so a failed
 * replacement can never damage a previously accepted model. Identity is model id + SHA-256, not the file name.
 * Plain `java.io` only so the whole flow runs in JVM tests.
 */
class SileroModelStore(
    baseDir: File,
    private val manifest: SileroModelManifest = SileroModelManifest.CANONICAL,
) {
    private val root = File(baseDir, "silero_vad")
    private val tmpDir = File(root, "tmp")
    val modelFile: File = File(File(root, manifest.modelId), manifest.filename)

    fun isInstalled(): Boolean = modelFile.isFile

    /** Re-verifies the installed file (guards against on-disk corruption). null = fine. */
    fun verifyInstalled(): SileroFailure? {
        if (!modelFile.isFile) return SileroFailure.NOT_IMPORTED
        return try {
            val (size, sha) = sizeAndSha(modelFile.inputStream(), Long.MAX_VALUE)
            SileroImportValidator.verifyIdentity(size, sha, manifest)
        } catch (e: IOException) {
            SileroFailure.UNREADABLE_FILE
        }
    }

    fun import(input: InputStream, inspector: SileroGraphInspector): SileroImportOutcome {
        cleanStaleTemp()
        if (!tmpDir.isDirectory && !tmpDir.mkdirs()) return SileroImportOutcome.Rejected(SileroFailure.STORAGE_FAILED)
        val tmp = File(tmpDir, "import-${UUID.randomUUID()}.part")
        try {
            // Stream-copy with a hard cap of expected+1 bytes: a wrong (e.g. huge) file is rejected early.
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            try {
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        val allowed = (manifest.expectedSizeBytes + 1 - total).coerceAtMost(n.toLong()).toInt()
                        digest.update(buf, 0, allowed)
                        out.write(buf, 0, allowed)
                        total += allowed
                        if (total > manifest.expectedSizeBytes) break
                    }
                }
            } catch (e: IOException) {
                return SileroImportOutcome.Rejected(if (tmp.exists() && total > 0) SileroFailure.STORAGE_FAILED else SileroFailure.UNREADABLE_FILE)
            }
            SileroImportValidator.verifyIdentity(total, hex(digest.digest()), manifest)?.let { return SileroImportOutcome.Rejected(it) }

            when (val inspection = inspector.inspect(tmp)) {
                is SileroGraphInspection.Failed -> return SileroImportOutcome.Rejected(inspection.failure)
                is SileroGraphInspection.Ok -> SileroGraphContract.verify(inspection.graph)?.let { return SileroImportOutcome.Rejected(it) }
            }

            modelFile.parentFile?.mkdirs()
            return try {
                Files.move(tmp.toPath(), modelFile.toPath(), StandardCopyOption.ATOMIC_MOVE) // atomic replace on one filesystem
                SileroImportOutcome.Ok(modelFile)
            } catch (e: IOException) {
                SileroImportOutcome.Rejected(SileroFailure.STORAGE_FAILED)
            }
        } finally {
            runCatching { if (tmp.exists()) tmp.delete() }
        }
    }

    /** Explicit user delete/reset: the only thing that ever removes the imported model. */
    fun remove(): Boolean {
        cleanStaleTemp()
        return !modelFile.exists() || modelFile.delete()
    }

    private fun cleanStaleTemp() {
        tmpDir.listFiles()?.forEach { runCatching { it.delete() } }
    }

    private fun sizeAndSha(input: InputStream, cap: Long): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        input.use {
            val buf = ByteArray(64 * 1024)
            while (total < cap) {
                val n = it.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
                total += n
            }
        }
        return total to hex(digest.digest())
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
