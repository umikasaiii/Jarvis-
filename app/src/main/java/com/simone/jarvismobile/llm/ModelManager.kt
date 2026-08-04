package com.simone.jarvismobile.llm

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

/** A model file imported into app-private storage. */
data class LocalModel(val name: String, val path: String, val sizeBytes: Long)

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
        modelsDir().listFiles()?.filter { it.isFile }
            ?.map { LocalModel(it.name, it.absolutePath, it.length()) }
            ?.sortedBy { it.name }
            .orEmpty()

    /**
     * Copies the picked document into app-private storage. Returns the imported
     * [LocalModel] or null on failure. Large files are streamed, not held in memory.
     */
    suspend fun importModel(uri: Uri): LocalModel? = withContext(Dispatchers.IO) {
        val name = displayName(uri) ?: "model-${System.currentTimeMillis()}.task"
        val target = File(modelsDir(), name)
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                if (input == null) return@withContext null
                target.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 1 shl 20)
                }
            }
            LocalModel(target.name, target.absolutePath, target.length())
        } catch (e: Exception) {
            runCatching { if (target.exists()) target.delete() }
            null
        }
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
