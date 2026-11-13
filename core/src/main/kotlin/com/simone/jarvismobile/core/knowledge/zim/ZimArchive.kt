package com.simone.jarvismobile.core.knowledge.zim

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * KNOWLEDGE ENGINE — a pure-Kotlin, random-access reader for the OpenZIM file format (spec: wiki.openzim.org/wiki/ZIM_file_format).
 *
 * Why not libzim/libkiwix: both are GPLv3 and this project is Apache-2.0, so shipping them inside the APK would impose
 * GPL obligations on the whole app. The format itself is open; this reader implements only what a question-answering
 * assistant needs — look an entry up by path, search titles by prefix, read one article — and never loads the archive:
 * every access is a small positioned read ([ByteSource]) plus, for article text, ONE cluster decompressed on demand
 * (bounded, cached). A 100 GB Wikipedia is therefore as cheap to open as a 100 KB test file.
 *
 * Not implemented, on purpose and stated in the docs: the embedded Xapian full-text index (`X/fulltext/xapian`,
 * `X/title/xapian`) — querying it needs libxapian. Search here is by TITLE (the ZIM title pointer list), then the
 * retrieved article text is ranked by JARVIS's own lexical ranker.
 */
interface ByteSource {
    val size: Long
    /** Reads exactly [length] bytes at [offset] (fewer only at the end of the source). */
    fun read(offset: Long, length: Int): ByteArray
}

/** Decompresses one ZIM cluster body. [type] is the ZIM compression id (4 = xz, 5 = zstd). */
interface ZimDecompressor {
    fun decompress(type: Int, data: ByteArray, maxOutputBytes: Int): ByteArray
}

class ZimFormatException(message: String) : Exception(message)

/** One directory entry. [index] is its position in the URL-ordered pointer list. */
data class ZimEntry(
    val index: Int,
    val namespace: Char,
    val path: String,
    val title: String,
    val mimeType: String?,
    val isRedirect: Boolean,
    val redirectIndex: Int,
    val clusterNumber: Int,
    val blobNumber: Int,
) {
    val displayTitle: String get() = title.ifEmpty { path }
}

class ZimArchive private constructor(
    private val source: ByteSource,
    private val decompressor: ZimDecompressor,
    val majorVersion: Int,
    val minorVersion: Int,
    val entryCount: Int,
    val clusterCount: Int,
    private val urlPtrPos: Long,
    private val titlePtrPos: Long,
    private val clusterPtrPos: Long,
    private val checksumPos: Long,
    val mainPageIndex: Int,
    val mimeTypes: List<String>,
    val uuid: String,
) {
    /** 'C' for the modern layout, 'A' for archives written before 2020. */
    val articleNamespace: Char by lazy {
        if (hasNamespace('C')) 'C' else 'A'
    }

    // Small, bounded cache of decompressed clusters: an article lookup usually touches 1-2 clusters, and neighbouring
    // articles tend to share one.
    private val clusterCache = object : LinkedHashMap<Int, ByteArray>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>): Boolean {
            return size > MAX_CACHED_CLUSTERS || totalCached() > MAX_CACHED_BYTES
        }

        private fun totalCached(): Long = values.sumOf { it.size.toLong() }
    }

    // ---- entries ----------------------------------------------------------------------------------------------

    fun entry(index: Int): ZimEntry {
        if (index < 0 || index >= entryCount) throw ZimFormatException("entry_index_out_of_range")
        val offset = u64(source.read(urlPtrPos + 8L * index, 8), 0)
        if (offset <= 0 || offset >= source.size) throw ZimFormatException("dirent_offset_invalid")
        return parseDirent(index, offset)
    }

    private fun parseDirent(index: Int, offset: Long): ZimEntry {
        var want = 512
        while (true) {
            val len = minOf(want.toLong(), source.size - offset).toInt()
            val b = source.read(offset, len)
            if (b.size < 16) throw ZimFormatException("dirent_truncated")
            val mime = u16(b, 0)
            val ns = (b[3].toInt() and 0xFF).toChar()
            val redirect = mime == 0xFFFF
            val deleted = mime == 0xFFFD || mime == 0xFFFE
            val headerLen = if (redirect) 12 else 16
            if (deleted) return ZimEntry(index, ns, "", "", null, false, -1, -1, -1)
            val urlEnd = indexOfZero(b, headerLen)
            val titleEnd = if (urlEnd >= 0) indexOfZero(b, urlEnd + 1) else -1
            if (urlEnd < 0 || titleEnd < 0) {
                if (len.toLong() >= source.size - offset || want >= MAX_DIRENT_BYTES) throw ZimFormatException("dirent_unterminated")
                want *= 4
                continue
            }
            val path = String(b, headerLen, urlEnd - headerLen, Charsets.UTF_8)
            val title = String(b, urlEnd + 1, titleEnd - urlEnd - 1, Charsets.UTF_8)
            return if (redirect) {
                ZimEntry(index, ns, path, title, null, true, u32(b, 8).toInt(), -1, -1)
            } else {
                ZimEntry(index, ns, path, title, mimeTypes.getOrNull(mime), false, -1, u32(b, 8).toInt(), u32(b, 12).toInt())
            }
        }
    }

    /** Looks up by namespace + path with a binary search over the URL pointer list. */
    fun findByPath(namespace: Char, path: String): ZimEntry? {
        var lo = 0
        var hi = entryCount - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val e = entry(mid)
            val c = compareKey(e.namespace, e.path, namespace, path)
            when {
                c == 0 -> return e
                c < 0 -> lo = mid + 1
                else -> hi = mid - 1
            }
        }
        return null
    }

    /** Follows redirects (bounded) to the entry that actually holds content. */
    fun resolve(entry: ZimEntry): ZimEntry? {
        var e = entry
        repeat(MAX_REDIRECTS) {
            if (!e.isRedirect) return if (e.clusterNumber >= 0) e else null
            if (e.redirectIndex < 0 || e.redirectIndex >= entryCount) return null
            e = entry(e.redirectIndex)
        }
        return null
    }

    val mainPage: ZimEntry? get() = if (mainPageIndex in 0 until entryCount) entry(mainPageIndex) else null

    // ---- title index ------------------------------------------------------------------------------------------

    private fun titleEntry(titleListPos: Int): ZimEntry {
        val idx = u32(source.read(titlePtrPos + 4L * titleListPos, 4), 0).toInt()
        return entry(idx)
    }

    /**
     * Entries of [namespace] whose title starts with [prefix] (case-sensitive: callers try the case variants), by
     * binary search over the title pointer list — no scan, so it stays fast on huge archives.
     */
    fun titlesWithPrefix(namespace: Char, prefix: String, limit: Int): List<ZimEntry> {
        if (prefix.isEmpty() || limit <= 0) return emptyList()
        var lo = 0
        var hi = entryCount
        while (lo < hi) { // lower bound of (namespace, prefix)
            val mid = (lo + hi) ushr 1
            val e = titleEntry(mid)
            if (compareKey(e.namespace, e.displayTitle, namespace, prefix) < 0) lo = mid + 1 else hi = mid
        }
        val out = ArrayList<ZimEntry>()
        var i = lo
        while (i < entryCount && out.size < limit) {
            val e = titleEntry(i)
            if (e.namespace != namespace || !e.displayTitle.startsWith(prefix)) break
            out += e
            i++
        }
        return out
    }

    private fun hasNamespace(ns: Char): Boolean {
        if (entryCount == 0) return false
        var lo = 0
        var hi = entryCount
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (entry(mid).namespace.code < ns.code) lo = mid + 1 else hi = mid
        }
        return lo < entryCount && entry(lo).namespace == ns
    }

    // ---- content ------------------------------------------------------------------------------------------------

    /** Raw bytes of an entry's blob (following redirects). Null for a missing/unsupported entry. */
    fun content(entry: ZimEntry): ByteArray? {
        val e = resolve(entry) ?: return null
        val cluster = cluster(e.clusterNumber)
        val extended = cluster.extended
        val offSize = if (extended) 8 else 4
        val data = cluster.data
        if (data.size < offSize) return null
        val firstOffset = if (extended) u64(data, 0) else u32(data, 0)
        val blobCount = (firstOffset / offSize).toInt() - 1
        if (blobCount <= 0 || e.blobNumber < 0 || e.blobNumber >= blobCount) return null
        val start = readOffset(data, e.blobNumber, extended)
        val end = readOffset(data, e.blobNumber + 1, extended)
        if (start < 0 || end < start || end > data.size) throw ZimFormatException("blob_range_invalid")
        if (end - start > MAX_BLOB_BYTES) throw ZimFormatException("blob_too_large")
        return data.copyOfRange(start.toInt(), end.toInt())
    }

    /** Metadata value from the `M` namespace (Title, Description, Language, Date, Creator, Publisher…). */
    fun metadata(key: String): String? {
        val e = findByPath('M', key) ?: return null
        return content(e)?.toString(Charsets.UTF_8)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private class Cluster(val data: ByteArray, val extended: Boolean)

    private val parsedClusters = HashMap<Int, Boolean>()

    private fun cluster(number: Int): Cluster {
        if (number < 0 || number >= clusterCount) throw ZimFormatException("cluster_index_out_of_range")
        val start = u64(source.read(clusterPtrPos + 8L * number, 8), 0)
        val end = if (number + 1 < clusterCount) u64(source.read(clusterPtrPos + 8L * (number + 1), 8), 0) else checksumPos
        if (start <= 0 || end <= start || end > source.size) throw ZimFormatException("cluster_range_invalid")
        val total = end - start
        if (total > MAX_COMPRESSED_CLUSTER_BYTES) throw ZimFormatException("cluster_too_large")
        synchronized(clusterCache) {
            clusterCache[number]?.let { return Cluster(it, parsedClusters[number] == true) }
        }
        val raw = source.read(start, total.toInt())
        val info = raw[0].toInt() and 0xFF
        val type = info and 0x0F
        val extended = (info and 0x10) != 0
        val body = raw.copyOfRange(1, raw.size)
        val data = when (type) {
            0, 1 -> body
            4, 5 -> decompressor.decompress(type, body, MAX_UNCOMPRESSED_CLUSTER_BYTES)
            else -> throw ZimFormatException("compression_unsupported_$type")
        }
        synchronized(clusterCache) {
            clusterCache[number] = data
            parsedClusters[number] = extended
        }
        return Cluster(data, extended)
    }

    private fun readOffset(data: ByteArray, i: Int, extended: Boolean): Long {
        val pos = i * if (extended) 8 else 4
        if (pos + (if (extended) 8 else 4) > data.size) throw ZimFormatException("offset_table_truncated")
        return if (extended) u64(data, pos) else u32(data, pos)
    }

    companion object {
        const val MAGIC = 72173914L
        private const val HEADER_BYTES = 80
        private const val MAX_REDIRECTS = 8
        private const val MAX_DIRENT_BYTES = 64 * 1024
        private const val MAX_CACHED_CLUSTERS = 3
        private const val MAX_CACHED_BYTES = 24L * 1024 * 1024
        const val MAX_COMPRESSED_CLUSTER_BYTES = 256L * 1024 * 1024
        const val MAX_UNCOMPRESSED_CLUSTER_BYTES = 96 * 1024 * 1024
        const val MAX_BLOB_BYTES = 32L * 1024 * 1024

        /** Opens and validates the archive; throws [ZimFormatException] on anything that is not a sane ZIM. */
        fun open(source: ByteSource, decompressor: ZimDecompressor): ZimArchive {
            if (source.size < HEADER_BYTES) throw ZimFormatException("file_too_small")
            val h = source.read(0, HEADER_BYTES)
            if (u32(h, 0) != MAGIC) throw ZimFormatException("not_a_zim_file")
            val major = u16(h, 4)
            val minor = u16(h, 6)
            if (major !in 5..6) throw ZimFormatException("unsupported_version_$major")
            val entries = u32(h, 24)
            val clusters = u32(h, 28)
            val urlPtr = u64(h, 32)
            val titlePtr = u64(h, 40)
            val clusterPtr = u64(h, 48)
            val mimePtr = u64(h, 56)
            val mainPage = u32(h, 64)
            val checksumPos = u64(h, 72)
            val size = source.size
            if (entries > Int.MAX_VALUE || clusters > Int.MAX_VALUE) throw ZimFormatException("counts_invalid")
            fun inside(pos: Long, bytes: Long) = pos in HEADER_BYTES..size && bytes >= 0 && pos + bytes <= size
            if (!inside(urlPtr, 8L * entries) || !inside(titlePtr, 4L * entries) || !inside(clusterPtr, 8L * clusters) || mimePtr !in HEADER_BYTES..size) {
                throw ZimFormatException("pointer_lists_out_of_bounds")
            }
            val effectiveChecksumPos = if (checksumPos in clusterPtr..size) checksumPos else (size - 16).coerceAtLeast(clusterPtr)
            val mimes = readMimeList(source, mimePtr)
            val uuid = h.copyOfRange(8, 24).joinToString("") { "%02x".format(it) }
            return ZimArchive(
                source, decompressor, major, minor, entries.toInt(), clusters.toInt(), urlPtr, titlePtr, clusterPtr,
                effectiveChecksumPos, if (mainPage == 0xFFFFFFFFL) -1 else mainPage.toInt(), mimes, uuid,
            )
        }

        private fun readMimeList(source: ByteSource, pos: Long): List<String> {
            val out = ArrayList<String>()
            var offset = pos
            val window = minOf(4096L, source.size - offset).toInt()
            val b = source.read(offset, window)
            var i = 0
            while (i < b.size) {
                val end = indexOfZero(b, i)
                if (end < 0) break
                if (end == i) break // empty string terminates the list
                out += String(b, i, end - i, Charsets.UTF_8)
                i = end + 1
                if (out.size > 512) throw ZimFormatException("mime_list_unbounded")
            }
            offset += i
            return out
        }

        /** Namespace byte first, then the UTF-8 bytes of the text, unsigned — the order ZIM files are sorted in. */
        internal fun compareKey(nsA: Char, a: String, nsB: Char, b: String): Int {
            if (nsA != nsB) return nsA.code - nsB.code
            val x = a.toByteArray(Charsets.UTF_8)
            val y = b.toByteArray(Charsets.UTF_8)
            val n = minOf(x.size, y.size)
            for (i in 0 until n) {
                val d = (x[i].toInt() and 0xFF) - (y[i].toInt() and 0xFF)
                if (d != 0) return d
            }
            return x.size - y.size
        }

        private fun indexOfZero(b: ByteArray, from: Int): Int {
            for (i in from until b.size) if (b[i].toInt() == 0) return i
            return -1
        }

        private fun u16(b: ByteArray, pos: Int): Int = ByteBuffer.wrap(b, pos, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
        private fun u32(b: ByteArray, pos: Int): Long = ByteBuffer.wrap(b, pos, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
        private fun u64(b: ByteArray, pos: Int): Long = ByteBuffer.wrap(b, pos, 8).order(ByteOrder.LITTLE_ENDIAN).long
    }

    private fun u32(b: ByteArray, pos: Int): Long = Companion.u32(b, pos)
    private fun u64(b: ByteArray, pos: Int): Long = Companion.u64(b, pos)
    private fun u16(b: ByteArray, pos: Int): Int = Companion.u16(b, pos)
    private fun compareKey(nsA: Char, a: String, nsB: Char, b: String) = Companion.compareKey(nsA, a, nsB, b)
    private fun indexOfZero(b: ByteArray, from: Int) = Companion.indexOfZero(b, from)
}
