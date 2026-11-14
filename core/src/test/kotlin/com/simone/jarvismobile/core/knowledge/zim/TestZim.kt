package com.simone.jarvismobile.core.knowledge.zim

import com.github.luben.zstd.Zstd
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZInputStream
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong

/** Test-side decompressor backed by zstd-jni / xz-java (the same libraries the Android adapter uses). */
object TestDecompressor : ZimDecompressor {
    override fun decompress(type: Int, data: ByteArray, maxOutputBytes: Int): ByteArray = when (type) {
        5 -> {
            val size = Zstd.decompressedSize(data)
            if (size in 1..maxOutputBytes.toLong()) {
                Zstd.decompress(data, size.toInt())
            } else {
                val s = com.github.luben.zstd.ZstdInputStream(ByteArrayInputStream(data))
                s.readNBytes(maxOutputBytes + 1).also { if (it.size > maxOutputBytes) error("too_big") }
            }
        }
        4 -> XZInputStream(ByteArrayInputStream(data)).use { it.readNBytes(maxOutputBytes + 1).also { b -> if (b.size > maxOutputBytes) error("too_big") } }
        else -> error("unsupported_$type")
    }
}

/** A ByteSource that counts the bytes it hands out, to prove the reader never loads a whole archive. */
class CountingSource(private val bytes: ByteArray) : ByteSource {
    val bytesRead = AtomicLong(0)
    val reads = AtomicLong(0)
    override val size: Long get() = bytes.size.toLong()
    override fun read(offset: Long, length: Int): ByteArray {
        val start = offset.toInt().coerceAtLeast(0)
        val end = minOf(bytes.size, start + length)
        bytesRead.addAndGet((end - start).toLong()); reads.incrementAndGet()
        return bytes.copyOfRange(start, end)
    }
}

class FileSource(file: File) : ByteSource {
    private val raf = RandomAccessFile(file, "r")
    override val size: Long = raf.length()
    @Synchronized override fun read(offset: Long, length: Int): ByteArray {
        val n = minOf(length.toLong(), size - offset).toInt().coerceAtLeast(0)
        val b = ByteArray(n); raf.seek(offset); raf.readFully(b); return b
    }
}

/** Minimal ZIM (v6.1, modern 'C' layout) writer for tests. Written from the public format spec, not from libzim. */
/** How the title order is stored: the historical header list, the modern `X/listing/titleOrdered/v1` listing, both, or neither. */
enum class TitleLayout { LEGACY, MODERN, BOTH, NONE, MODERN_COMPRESSED_LISTING }

class TestZimBuilder {
    class E(val ns: Char, val path: String, val title: String, val mime: String, val data: ByteArray?, val redirectTo: Pair<Char, String>? = null)

    private val entries = ArrayList<E>()
    private val meta = LinkedHashMap<String, String>()
    var mainPath: String? = null
    var entriesPerCluster = 8
    var compression = 5 // 1 none, 4 xz, 5 zstd
    var uuidSeed = 1
    var titleLayout = TitleLayout.LEGACY
    /** Test hook: deliberately corrupt the header's title pointer (to prove a really invalid pointer is still rejected). */
    var corruptTitlePtr: Long? = null

    fun article(path: String, title: String, html: String) = apply { entries += E('C', path, title, "text/html", html.toByteArray()) }
    fun redirect(path: String, title: String, to: String) = apply { entries += E('C', path, title, "", null, 'C' to to) }
    fun metadata(k: String, v: String) = apply { meta[k] = v }

    fun build(): ByteArray {
        meta.forEach { (k, v) -> entries += E('M', k, "", "text/plain", v.toByteArray()) }
        val wantsListing = titleLayout != TitleLayout.LEGACY && titleLayout != TitleLayout.NONE
        val writeLegacy = titleLayout == TitleLayout.LEGACY || titleLayout == TitleLayout.BOTH
        if (wantsListing) {
            // 'C' < 'M' < 'X': the indexes of the C entries do not depend on the listing entry itself.
            val cSorted = entries.filter { it.ns == 'C' }.sortedWith { a, b -> cmp(a.ns, a.path, b.ns, b.path) }
            // like real modern writers: front articles only — redirects are NOT listed
            val byTitle = cSorted.indices.filter { cSorted[it].redirectTo == null }.sortedWith { a, b -> cmp('C', cSorted[a].title.ifEmpty { cSorted[a].path }, 'C', cSorted[b].title.ifEmpty { cSorted[b].path }) }
            val buf = ByteBuffer.allocate(4 * byTitle.size).order(ByteOrder.LITTLE_ENDIAN); byTitle.forEach { buf.putInt(it) }
            entries += E('X', "listing/titleOrdered/v1", "", "application/octet-stream", buf.array())
        }
        val sorted = entries.sortedWith { a, b -> cmp(a.ns, a.path, b.ns, b.path) }
        val mimes = (sorted.mapNotNull { it.mime.takeIf { m -> m.isNotEmpty() } }).distinct()
        val index = HashMap<Pair<Char, String>, Int>(); sorted.forEachIndexed { i, e -> index[e.ns to e.path] = i }

        // clusters: content entries in url order, entriesPerCluster blobs each
        val content = sorted.filter { it.redirectTo == null }
        val clusterOf = HashMap<E, Pair<Int, Int>>()
        val clusters = ArrayList<List<E>>()
        val clusterComp = ArrayList<Int>()
        content.filter { it.ns != 'X' }.chunked(entriesPerCluster).forEachIndexed { ci, group -> clusters += group; clusterComp += compression; group.forEachIndexed { bi, e -> clusterOf[e] = ci to bi } }
        content.filter { it.ns == 'X' }.forEach { e ->
            // the listing lives alone in its own cluster; libzim requires it to be UNCOMPRESSED (the compressed variant is the broken case)
            clusters += listOf(e); clusterComp += if (titleLayout == TitleLayout.MODERN_COMPRESSED_LISTING) compression else 1
            clusterOf[e] = (clusters.size - 1) to 0
        }

        val mimeList = ByteArrayOutputStream().also { o -> mimes.forEach { o.write(it.toByteArray()); o.write(0) }; o.write(0) }.toByteArray()
        val n = sorted.size
        val headerEnd = 80
        val mimePos = headerEnd
        val urlPtrPos = mimePos + mimeList.size
        val titlePtrPos = urlPtrPos + 8 * n
        val clusterPtrPos = titlePtrPos + if (writeLegacy) 4 * n else 0
        val direntsPos = clusterPtrPos + 8 * clusters.size

        val dirents = ArrayList<ByteArray>()
        for (e in sorted) {
            val b = ByteArrayOutputStream()
            fun u16(v: Int) = b.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array())
            fun u32(v: Long) = b.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v.toInt()).array())
            if (e.redirectTo != null) {
                u16(0xFFFF); b.write(0); b.write(e.ns.code); u32(0); u32(index.getValue(e.redirectTo).toLong())
            } else {
                u16(mimes.indexOf(e.mime)); b.write(0); b.write(e.ns.code); u32(0)
                val (c, bl) = clusterOf.getValue(e); u32(c.toLong()); u32(bl.toLong())
            }
            b.write(e.path.toByteArray()); b.write(0); b.write(e.title.toByteArray()); b.write(0)
            dirents += b.toByteArray()
        }
        val direntOffsets = LongArray(n); var pos = direntsPos.toLong()
        dirents.forEachIndexed { i, d -> direntOffsets[i] = pos; pos += d.size }
        val clusterStart = pos
        val clusterBytes = clusters.mapIndexed { ci, group ->
            val o = ByteArrayOutputStream()
            val blobs = group.map { it.data!! }
            var off = 4L * (blobs.size + 1)
            val table = ByteBuffer.allocate(4 * (blobs.size + 1)).order(ByteOrder.LITTLE_ENDIAN)
            for (bl in blobs) { table.putInt(off.toInt()); off += bl.size }
            table.putInt(off.toInt())
            o.write(table.array()); blobs.forEach { o.write(it) }
            val raw = o.toByteArray()
            when (clusterComp[ci]) {
                4 -> byteArrayOf(4) + ByteArrayOutputStream().also { bo -> XZOutputStream(bo, LZMA2Options()).use { it.write(raw) } }.toByteArray()
                5 -> byteArrayOf(5) + Zstd.compress(raw)
                else -> byteArrayOf(1) + raw
            }
        }
        val clusterOffsets = LongArray(clusters.size); var cpos = clusterStart
        clusterBytes.forEachIndexed { i, c -> clusterOffsets[i] = cpos; cpos += c.size }
        val checksumPos = cpos

        val out = ByteArrayOutputStream()
        val h = ByteBuffer.allocate(80).order(ByteOrder.LITTLE_ENDIAN)
        h.putInt(72173914); h.putShort(6); h.putShort(1)
        h.put(ByteArray(16) { (it + uuidSeed).toByte() })
        h.putInt(n); h.putInt(clusters.size)
        h.putLong(urlPtrPos.toLong()); h.putLong(corruptTitlePtr ?: if (writeLegacy) titlePtrPos.toLong() else -1L); h.putLong(clusterPtrPos.toLong()); h.putLong(mimePos.toLong())
        h.putInt(mainPath?.let { index[('C') to it] } ?: -1); h.putInt(-1); h.putLong(checksumPos)
        out.write(h.array()); out.write(mimeList)
        val up = ByteBuffer.allocate(8 * n).order(ByteOrder.LITTLE_ENDIAN); direntOffsets.forEach { up.putLong(it) }; out.write(up.array())
        val titleOrder = sorted.indices.sortedWith { a, b -> cmp(sorted[a].ns, sorted[a].title.ifEmpty { sorted[a].path }, sorted[b].ns, sorted[b].title.ifEmpty { sorted[b].path }) }
        if (writeLegacy) { val tp = ByteBuffer.allocate(4 * n).order(ByteOrder.LITTLE_ENDIAN); titleOrder.forEach { tp.putInt(it) }; out.write(tp.array()) }
        val cp = ByteBuffer.allocate(8 * clusters.size).order(ByteOrder.LITTLE_ENDIAN); clusterOffsets.forEach { cp.putLong(it) }; out.write(cp.array())
        dirents.forEach { out.write(it) }; clusterBytes.forEach { out.write(it) }
        out.write(ByteArray(16))
        return out.toByteArray()
    }

    private fun cmp(na: Char, a: String, nb: Char, b: String): Int {
        if (na != nb) return na.code - nb.code
        val x = a.toByteArray(); val y = b.toByteArray()
        for (i in 0 until minOf(x.size, y.size)) { val d = (x[i].toInt() and 0xFF) - (y[i].toInt() and 0xFF); if (d != 0) return d }
        return x.size - y.size
    }
}
