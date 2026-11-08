package com.simone.jarvismobile.llm

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.os.StatFs
import androidx.documentfile.provider.DocumentFile
import com.simone.jarvismobile.core.llm.ImportVerdict
import com.simone.jarvismobile.core.llm.ModelImportPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A model file imported into app-private storage. */
data class LocalModel(val name: String, val path: String, val sizeBytes: Long)

/** Outcome of importing a model file. */
sealed interface ImportResult {
    data class Ok(val model: LocalModel) : ImportResult
    /** The copy did not match the source size (truncated) — file was removed. */
    data class Incomplete(val expectedBytes: Long, val copiedBytes: Long) : ImportResult
    data class Failed(val reason: String) : ImportResult

    /** BETA 2/8 — refused BEFORE copying (wrong format, too small, no space, model in use); nothing was touched. */
    data class Rejected(val message: String) : ImportResult
}

/**
 * Imports and lists on-device LLM model files. Models are copied into the app's
 * private `files/models` directory (no MANAGE_EXTERNAL_STORAGE, no arbitrary file
 * access) and are never bundled in the repo (docs/MODELS.md, docs/SECURITY.md).
 */
@Singleton
class ModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private fun modelsDir(): File = File(context.filesDir, "models").apply { mkdirs() }

    fun listModels(): List<LocalModel> =
        modelsDir().listFiles()?.filter { it.isFile && !it.name.endsWith(".importing") }
            ?.map { LocalModel(it.name, it.absolutePath, it.length()) }
            ?.sortedBy { it.name }
            .orEmpty()

    /**
     * Copies the picked document into app-private storage, streaming (not held in
     * memory), and verifies the copied size against the source size to catch a
     * truncated/incomplete copy before the user tries to load a broken model.
     */
    suspend fun importModel(uri: Uri, inUsePaths: Set<String> = emptySet()): ImportResult = withContext(Dispatchers.IO) {
        val expected = sourceSize(uri)
        val dir = modelsDir()
        val fallback = "model-${System.currentTimeMillis()}.litertlm"
        val shownName = displayName(uri)
        val free = runCatching { StatFs(dir.path).availableBytes }.getOrDefault(-1L)
        val targetCandidate = File(dir, ModelImportPolicy.safeFileName(shownName, fallback))
        val verdict = ModelImportPolicy.validate(
            displayName = shownName,
            sizeBytes = expected,
            freeBytes = free,
            inUse = targetCandidate.absolutePath in inUsePaths,
            fallbackName = fallback,
        )
        val name = when (verdict) {
            is ImportVerdict.Reject -> return@withContext ImportResult.Rejected(verdict.message)
            is ImportVerdict.Accept -> verdict.safeName
        }
        val target = File(dir, name)
        // Copy to a temporary file and only then swap it in: a failed or interrupted import must never
        // destroy an existing, working model with the same name.
        val temp = File(dir, "$name.importing")
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                if (input == null) return@withContext ImportResult.Failed("stream_null")
                temp.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 1 shl 20)
                    output.flush()
                }
            }
            val copied = temp.length()
            if (expected > 0 && copied != expected) {
                runCatching { temp.delete() }
                ImportResult.Incomplete(expected, copied)
            } else if (!temp.renameTo(target)) {
                runCatching { temp.delete() }
                ImportResult.Failed("rename_failed")
            } else {
                ImportResult.Ok(LocalModel(target.name, target.absolutePath, copied))
            }
        } catch (e: Exception) {
            runCatching { if (temp.exists()) temp.delete() }
            ImportResult.Failed(e.javaClass.simpleName)
        }
    }

    private fun sourceSize(uri: Uri): Long {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx)
                }
            }
        return -1L
    }

    fun deleteModel(path: String): Boolean = runCatching { File(path).delete() }.getOrDefault(false)

    private fun displayName(uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return c.getString(idx)
                }
            }
        return DocumentFile.fromSingleUri(context, uri)?.name
    }
}
