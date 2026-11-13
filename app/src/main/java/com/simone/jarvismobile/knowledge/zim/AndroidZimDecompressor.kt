package com.simone.jarvismobile.knowledge.zim

import com.github.luben.zstd.ZstdInputStream
import com.simone.jarvismobile.core.knowledge.zim.ZimDecompressor
import com.simone.jarvismobile.core.knowledge.zim.ZimFormatException
import org.tukaani.xz.XZInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** zstd (ZIM 6.x archives, the default since 2021) and xz (older archives), with a hard cap on the decompressed size. */
object AndroidZimDecompressor : ZimDecompressor {
    override fun decompress(type: Int, data: ByteArray, maxOutputBytes: Int): ByteArray = when (type) {
        5 -> readCapped(ZstdInputStream(ByteArrayInputStream(data)), maxOutputBytes)
        4 -> readCapped(XZInputStream(ByteArrayInputStream(data)), maxOutputBytes)
        else -> throw ZimFormatException("compression_unsupported_$type")
    }

    private fun readCapped(input: InputStream, max: Int): ByteArray = input.use { s ->
        val out = ByteArrayOutputStream(minOf(max, 1 shl 20))
        val buf = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val r = s.read(buf)
            if (r < 0) break
            total += r
            if (total > max) throw ZimFormatException("decompressed_cluster_over_cap")
            out.write(buf, 0, r)
        }
        out.toByteArray()
    }
}
