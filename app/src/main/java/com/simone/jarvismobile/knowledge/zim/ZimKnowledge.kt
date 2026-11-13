package com.simone.jarvismobile.knowledge.zim

import android.content.Context
import android.net.Uri
import android.util.Log
import com.simone.jarvismobile.core.knowledge.zim.ZimArchive
import com.simone.jarvismobile.core.knowledge.zim.ZimHtml
import com.simone.jarvismobile.core.knowledge.zim.ZimKnowledgeSource
import com.simone.jarvismobile.core.offlinehub.ZimAccess
import com.simone.jarvismobile.data.SettingsRepository
import com.simone.jarvismobile.offlinehub.ZimLibrary
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Direct reading of the user's ZIM archives (see [ZimArchive]). Reuses the archive registry of the Offline Hub
 * ([ZimLibrary]) — no second list of files — and adds ONE rule: an archive feeds the answer path only after the user has
 * verified, on this device, that it can really be read (open → metadata → one article decompressed through the native
 * library). The verification is stored with the file size; a different size invalidates it.
 */
@Singleton
class ZimKnowledge @Inject constructor(
    @ApplicationContext private val context: Context,
    private val library: ZimLibrary,
    private val settings: SettingsRepository,
) {
    data class Report(val uri: String, val ok: Boolean, val title: String, val date: String, val language: String, val entries: Int, val articleNamespace: Char?, val detail: String)

    private class Opened(val size: Long, val bytes: SafByteSource, val source: ZimKnowledgeSource)

    private val mutex = Mutex()
    private val opened = HashMap<String, Opened>()

    /** Opens the archive and proves it can be read. Persists the verification only on success. */
    suspend fun verify(uri: String): Report = withContext(Dispatchers.IO) {
        mutex.withLock {
            closeLocked(uri)
            val bytes = try {
                SafByteSource(context, Uri.parse(uri))
            } catch (t: Throwable) {
                return@withContext fail(uri, "Non leggibile: ${t.message ?: t.javaClass.simpleName}")
            }
            try {
                val zim = ZimArchive.open(bytes, AndroidZimDecompressor)
                val source = ZimKnowledgeSource(zim)
                // Prove the native decompression path on THIS device: read the main page (or the first article found).
                val probe = zim.mainPage?.let { zim.resolve(it) }
                    ?: zim.titlesWithPrefix(zim.articleNamespace, "A", 1).firstOrNull()?.let { zim.resolve(it) }
                    ?: throw IllegalStateException("no_readable_article")
                val html = zim.content(probe) ?: throw IllegalStateException("article_unreadable")
                if (ZimHtml.toText(String(html, Charsets.UTF_8).take(200_000)).text.isBlank()) throw IllegalStateException("article_empty")
                settings.setZimVerified(settings.zimVerified.first().filterNot { it.substringBeforeLast('|') == uri }.toSet() + "$uri|${bytes.size}")
                opened[uri] = Opened(bytes.size, bytes, source)
                Report(uri, true, source.label, source.date, source.language, zim.entryCount, zim.articleNamespace, "Lettura verificata su questo telefono.")
            } catch (t: Throwable) {
                runCatching { bytes.close() }
                Log.w(TAG, "zim_verify_failed ${t.javaClass.simpleName}")
                fail(uri, "Lettura non riuscita: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private fun fail(uri: String, detail: String) = Report(uri, false, "", "", "", 0, null, detail)

    /** True when at least one archive has been verified and is still the file that was verified. */
    suspend fun hasVerified(): Boolean = verifiedEntries().isNotEmpty()

    private suspend fun verifiedEntries(): List<Pair<String, Long>> {
        val verified = settings.zimVerified.first().mapNotNull { v -> v.substringBeforeLast('|', "").takeIf { it.isNotBlank() }?.let { it to (v.substringAfterLast('|').toLongOrNull() ?: -1L) } }
        if (verified.isEmpty()) return emptyList()
        val current = library.entries().filter { it.access == ZimAccess.READABLE }.associate { it.uri to it.sizeBytes }
        return verified.filter { (u, size) -> current[u] == size }
    }

    /** Sources of every verified archive (opened lazily and kept; closed on [forget]). */
    suspend fun sources(): List<ZimKnowledgeSource> = withContext(Dispatchers.IO) {
        mutex.withLock {
            verifiedEntries().mapNotNull { (uri, size) ->
                opened[uri]?.takeIf { it.size == size }?.source ?: run {
                    closeLocked(uri)
                    runCatching {
                        val b = SafByteSource(context, Uri.parse(uri))
                        val s = ZimKnowledgeSource(ZimArchive.open(b, AndroidZimDecompressor))
                        opened[uri] = Opened(b.size, b, s)
                        s
                    }.onFailure { Log.w(TAG, "zim_open_failed ${it.javaClass.simpleName}") }.getOrNull()
                }
            }
        }
    }

    suspend fun forget(uri: String) {
        mutex.withLock { closeLocked(uri) }
        settings.setZimVerified(settings.zimVerified.first().filterNot { it.substringBeforeLast('|') == uri }.toSet())
    }

    private fun closeLocked(uri: String) { opened.remove(uri)?.bytes?.let { runCatching { it.close() } } }

    private companion object { const val TAG = "JarvisZimKnowledge" }
}
