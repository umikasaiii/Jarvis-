package com.simone.jarvismobile.knowledge.zim

import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdInputStream
import com.simone.jarvismobile.core.knowledge.zim.ZimDecompressor
import com.simone.jarvismobile.core.knowledge.zim.ZimFormatException
import org.tukaani.xz.XZInputStream
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * zstd (ZIM 6.x archives, the default since 2021) and xz (older archives), with a hard cap on the decompressed size.
 * Decompresses straight from the cluster buffer (no copy of the compressed body) and, when the zstd frame states its content
 * size, in one call into an exactly-sized array; otherwise streams into a growing array (no intermediate stream copy).
 */
object AndroidZimDecompressor : ZimDecompressor {
    override fun decompress(type: Int, data: ByteArray, maxOutputBytes: Int): ByteArray = decompress(type, data, 0, data.size, maxOutputBytes)

    override fun decompress(type: Int, data: ByteArray, offset: Int, length: Int, maxOutputBytes: Int): ByteArray = when (type) {
        5 -> {
            val size = Zstd.decompressedSize(data, offset, length)
            if (size in 1..maxOutputBytes.toLong()) {
                ByteArray(size.toInt()).also { out ->
                    val n = Zstd.decompressByteArray(out, 0, out.size, data, offset, length)
                    if (Zstd.isError(n) || n != out.size.toLong()) throw ZimFormatException("zstd_decode_failed")
                }
            } else readCapped(ZstdInputStream(ByteArrayInputStream(data, offset, length)), maxOutputBytes, length)
        }
        4 -> readCapped(XZInputStream(ByteArrayInputStream(data, offset, length)), maxOutputBytes, length)
        else -> throw ZimFormatException("compression_unsupported_$type")
    }

    private fun readCapped(input: InputStream, max: Int, compressed: Int): ByteArray = input.use { s ->
        var buf = ByteArray(minOf(max.toLong(), maxOf(64L * 1024, compressed * 4L)).toInt())
        var pos = 0
        while (true) {
            if (pos == buf.size) {
                if (buf.size >= max) throw ZimFormatException("decompressed_cluster_over_cap")
                buf = buf.copyOf(minOf(max.toLong(), buf.size * 2L).toInt())
            }
            val r = s.read(buf, pos, buf.size - pos)
            if (r < 0) break
            pos += r
            if (pos > max) throw ZimFormatException("decompressed_cluster_over_cap")
        }
        if (pos == buf.size) buf else buf.copyOf(pos)
    }
}
