package com.simone.jarvismobile.memory

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.simone.jarvismobile.core.memory.MemoryNoteThemes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * User-imported note background images (§ richiesta esplicita dell'utente,
 * dopo aver chiarito perché un pacchetto di immagini con personaggi/loghi di
 * franchise con licenza non può essere bundlato nell'app — questo repository
 * e le sue release APK sono pubblici, quindi includerle significherebbe
 * ridistribuirle: "Il progetto lo sto usando esclusivamente ad uso
 * personale" → "Perfetto, mi piace" sull'alternativa proposta). L'utente
 * sceglie un'immagine dalla propria galleria; il file resta **solo** in
 * storage app-privato sul suo dispositivo, non è mai committato in questo
 * repository né incluso nell'APK — a differenza di [MemoryNoteThemes.IMAGES]
 * (i 9 temi generici bundlati), questi vivono esclusivamente qui.
 */
@Singleton
class NoteBackgroundStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dir: File get() = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    /** Every imported background, most recent first, as ids ready to store in [com.simone.jarvismobile.core.memory.MemoryRecord.theme]. */
    fun list(): List<String> =
        dir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.map { MemoryNoteThemes.userImageId(it.name) }
            ?: emptyList()

    /**
     * The backing file for a `"user:…"` theme id, or null if the id is
     * malformed or the file no longer exists — a note referencing a since-
     * deleted background simply falls back to its plain-colour theme at
     * render time, no dangling reference or crash.
     */
    fun file(id: String): File? {
        val name = MemoryNoteThemes.userImageFile(id)
        // Defensive: the theme string round-trips through Room/Markdown as
        // plain text, so refuse anything that isn't a bare filename before
        // it ever reaches java.io.File — this store only ever writes UUID
        // names itself, but never trust a value read back from storage.
        if (name.isBlank() || name.contains('/') || name.contains("..")) return null
        val f = File(dir, name)
        return if (f.isFile) f else null
    }

    /** Copies [uri]'s image bytes into app-private storage, downscaled, and returns its theme id — or null on failure. */
    suspend fun import(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
            val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
            val scaled = downscale(original, MAX_DIMENSION_PX)
            val fileName = "${UUID.randomUUID()}.jpg"
            File(dir, fileName).outputStream().use { out -> scaled.compress(Bitmap.CompressFormat.JPEG, 85, out) }
            if (scaled !== original) original.recycle()
            MemoryNoteThemes.userImageId(fileName)
        }.onFailure { Log.w(TAG, "import_failed ${it.javaClass.simpleName}") }.getOrNull()
    }

    fun delete(id: String) {
        file(id)?.delete()
    }

    /** A note background doesn't need the original phone-photo resolution — this keeps app storage/backup size sane. */
    private fun downscale(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxDimension) return bitmap
        val scale = maxDimension.toFloat() / longest
        val w = max(1, (bitmap.width * scale).toInt())
        val h = max(1, (bitmap.height * scale).toInt())
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }

    companion object {
        private const val TAG = "NoteBackgroundStore"
        private const val DIR_NAME = "note_backgrounds"
        private const val MAX_DIMENSION_PX = 1600
    }
}
