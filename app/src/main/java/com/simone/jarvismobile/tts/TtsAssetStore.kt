package com.simone.jarvismobile.tts

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** One imported voice file on disk. */
data class TtsAsset(val kind: TtsAssetKind, val name: String, val path: String, val sizeBytes: Long) {
    val exists: Boolean get() = File(path).isFile

    /** "412,3 MB" — what the Settings screen shows. */
    val sizeLabel: String
        get() = when {
            sizeBytes >= 1L shl 30 -> String.format("%.1f GB", sizeBytes / 1024.0 / 1024.0 / 1024.0)
            sizeBytes >= 1L shl 20 -> String.format("%.1f MB", sizeBytes / 1024.0 / 1024.0)
            sizeBytes >= 1024 -> String.format("%.0f kB", sizeBytes / 1024.0)
            else -> "$sizeBytes B"
        }
}

sealed interface TtsImportResult {
    data class Ok(val asset: TtsAsset) : TtsImportResult
    data class Incomplete(val expectedBytes: Long, val copiedBytes: Long) : TtsImportResult
    data class Failed(val reason: String) : TtsImportResult
}

/**
 * Imports and keeps the voice files.
 *
 * The picked document is *copied* into app-private storage rather than kept as a
 * SAF grant. A persisted URI permission survives a restart but not a file being
 * moved, a card being unmounted or the picker returning a non-persistable
 * `content://` — and a voice that silently stops existing is worse than one that
 * costs a one-off copy. The copy is streamed and size-checked, so a truncated
 * model is caught at import instead of at the first reply.
 *
 * Nothing here is bundled in the APK; the directory starts empty.
 */
@Singleton
class TtsAssetStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private fun dir(): File = File(context.filesDir, "tts").apply { mkdirs() }

    fun fileFor(path: String?): File? = path?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile }

    suspend fun import(uri: Uri, kind: TtsAssetKind): TtsImportResult = withContext(Dispatchers.IO) {
        val name = displayName(uri) ?: "${kind.name.lowercase()}-${System.currentTimeMillis()}"
        val expected = sourceSize(uri)
        val target = File(dir(), name)
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                if (input == null) return@withContext TtsImportResult.Failed("stream_null")
                target.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 1 shl 20)
                    output.flush()
                }
            }
            val copied = target.length()
            if (expected > 0 && copied != expected) {
                runCatching { target.delete() }
                TtsImportResult.Incomplete(expected, copied)
            } else {
                TtsImportResult.Ok(TtsAsset(kind, target.name, target.absolutePath, copied))
            }
        } catch (e: Exception) {
            runCatching { if (target.exists()) target.delete() }
            TtsImportResult.Failed(e.javaClass.simpleName)
        }
    }

    /** Everything already imported, so a file can be re-selected without re-copying. */
    fun list(): List<TtsAsset> = dir().listFiles()?.filter { it.isFile }?.map { f ->
        TtsAsset(kindOf(f.name), f.name, f.absolutePath, f.length())
    }?.sortedBy { it.name }.orEmpty()

    fun describe(path: String?, kind: TtsAssetKind): TtsAsset? {
        val f = fileFor(path) ?: return null
        return TtsAsset(kind, f.name, f.absolutePath, f.length())
    }

    fun delete(path: String): Boolean = runCatching { File(path).delete() }.getOrDefault(false)

    private fun kindOf(name: String): TtsAssetKind {
        val ext = name.substringAfterLast('.', "").lowercase()
        return TtsAssetKind.entries.firstOrNull { ext in it.extensions } ?: TtsAssetKind.MODEL
    }

    private fun sourceSize(uri: Uri): Long {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx)
            }
        }
        return -1L
    }

    private fun displayName(uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return c.getString(idx)
            }
        }
        return DocumentFile.fromSingleUri(context, uri)?.name
    }
}
