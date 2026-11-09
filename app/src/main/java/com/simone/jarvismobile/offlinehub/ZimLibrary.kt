package com.simone.jarvismobile.offlinehub

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.simone.jarvismobile.core.offlinehub.ZimAccess
import com.simone.jarvismobile.core.offlinehub.ZimClassifier
import com.simone.jarvismobile.core.offlinehub.ZimEntry
import com.simone.jarvismobile.data.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * References to the user's ZIM archives, through the Storage Access Framework with a READ-ONLY persisted grant.
 * Nothing is ever copied or unpacked, and the file is never parsed: at most the first 4 bytes are read to check
 * the ZIM magic number. "Readable here" does not mean "Kiwix will open it".
 */
@Singleton
class ZimLibrary @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    suspend fun addFolder(tree: Uri) {
        takeRead(tree)
        settings.setHubZimTreeUri(tree.toString())
    }

    suspend fun addFile(doc: Uri) {
        takeRead(doc)
        settings.setHubZimDocUris(settings.hubZimDocUris.first() + doc.toString())
    }

    suspend fun removeFile(uri: String) {
        val rest = settings.hubZimDocUris.first().filterNot { it == uri }
        settings.setHubZimDocUris(rest)
        if (uri != settings.hubZimTreeUri.first()) release(Uri.parse(uri))
    }

    suspend fun removeFolder() {
        val uri = settings.hubZimTreeUri.first()
        if (uri.isNotBlank()) release(Uri.parse(uri))
        settings.setHubZimTreeUri("")
    }

    suspend fun folderName(): String? {
        val uri = settings.hubZimTreeUri.first()
        if (uri.isBlank()) return null
        return runCatching { DocumentFile.fromTreeUri(context, Uri.parse(uri))?.name }.getOrNull()
    }

    suspend fun hasFolder(): Boolean = settings.hubZimTreeUri.first().isNotBlank()

    /** Every selected archive with its real, freshly checked access state. */
    suspend fun entries(): List<ZimEntry> = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<String, ZimEntry>()
        val treeStr = settings.hubZimTreeUri.first()
        if (treeStr.isNotBlank()) {
            val tree = Uri.parse(treeStr)
            val persisted = isPersisted(tree)
            val root = runCatching { DocumentFile.fromTreeUri(context, tree) }.getOrNull()
            if (root != null && persisted && root.canRead()) {
                runCatching { root.listFiles() }.getOrDefault(emptyArray()).forEach { f ->
                    val name = f.name ?: return@forEach
                    if (f.isFile && ZimClassifier.isZimName(name)) out[f.uri.toString()] = entryOf(f, name, true)
                }
            }
        }
        settings.hubZimDocUris.first().forEach { u ->
            if (u in out) return@forEach
            val uri = Uri.parse(u)
            val doc = runCatching { DocumentFile.fromSingleUri(context, uri) }.getOrNull()
            val name = doc?.name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "archivio.zim"
            out[u] = if (doc == null) ZimEntry(u, name, 0, ZimAccess.UNREADABLE, isPersisted(uri))
            else entryOf(doc, name, isPersisted(uri))
        }
        out.values.toList()
    }

    private fun entryOf(doc: DocumentFile, name: String, persisted: Boolean): ZimEntry {
        val exists = runCatching { doc.exists() }.getOrDefault(false)
        val canRead = runCatching { doc.canRead() }.getOrDefault(false)
        val header = if (persisted && exists && canRead) readFirst4(doc.uri) else null
        return ZimEntry(doc.uri.toString(), name, runCatching { doc.length() }.getOrDefault(0), ZimClassifier.access(persisted, exists, canRead, header), persisted)
    }

    private fun readFirst4(uri: Uri): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(4)
            var n = 0
            while (n < 4) { val r = input.read(buf, n, 4 - n); if (r < 0) break; n += r }
            if (n == 4) buf else null
        }
    }.getOrNull()

    private fun isPersisted(uri: Uri): Boolean = runCatching {
        context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
    }.getOrDefault(false)

    private fun takeRead(uri: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            .onFailure { Log.w(TAG, "zim_persist_failed ${it.javaClass.simpleName}") }
    }

    private fun release(uri: Uri) {
        runCatching { context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    private companion object { const val TAG = "JarvisZimLibrary" }
}
