package com.simone.jarvismobile.knowledge.zim

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.simone.jarvismobile.core.knowledge.zim.ByteSource
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Random-access reads over a Storage Access Framework document, through its file descriptor: `pread` at an offset, never a
 * stream from the start, never a copy. A 50 GB archive costs the same to open as a small one. Providers that hand out a
 * non-seekable pipe (some cloud providers) cannot be used and fail with an [IOException] the UI reports as "non leggibile".
 */
class SafByteSource(context: Context, uri: Uri) : ByteSource, AutoCloseable {
    private val pfd: ParcelFileDescriptor =
        context.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("provider_returned_no_descriptor")
    private val channel: FileChannel = FileInputStream(pfd.fileDescriptor).channel

    override val size: Long = runCatching { channel.size() }.getOrElse {
        close(); throw IOException("size_unavailable")
    }.also { if (it <= 0L) { close(); throw IOException("not_seekable_or_empty") } }

    @Synchronized
    override fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || length < 0) throw IOException("bad_range")
        val n = minOf(length.toLong(), size - offset).toInt().coerceAtLeast(0)
        val buf = ByteBuffer.allocate(n)
        var pos = offset
        while (buf.hasRemaining()) {
            val r = channel.read(buf, pos)
            if (r < 0) break
            pos += r
        }
        return if (buf.position() == n) buf.array() else buf.array().copyOf(buf.position())
    }

    override fun close() {
        runCatching { channel.close() }
        runCatching { pfd.close() }
    }
}
