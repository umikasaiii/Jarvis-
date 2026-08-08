package com.simone.jarvismobile.core.tts

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

/**
 * The style vectors for one voice.
 *
 * Kokoro does not have a single embedding per voice: it has one per utterance
 * length, so a short phrase and a long sentence are spoken with different
 * prosody. [rows] is how many lengths the pack covers and [dim] the embedding
 * width (256 for v1.0).
 */
class VoiceStyles(val rows: Int, val dim: Int, private val data: FloatArray) {

    /**
     * The embedding for an utterance of [tokenCount] tokens, clamped to what the
     * pack actually holds — an over-long line must sound slightly off, not crash.
     */
    fun styleFor(tokenCount: Int): FloatArray {
        val row = tokenCount.coerceIn(0, rows - 1)
        val from = row * dim
        return data.copyOfRange(from, from + dim)
    }
}

/** Every voice found in a voices file, by name. */
class VoiceBank(val voices: Map<String, VoiceStyles>) {
    val names: List<String> get() = voices.keys.sorted()
    fun get(name: String): VoiceStyles? = voices[name]
    val isEmpty: Boolean get() = voices.isEmpty()
}

/**
 * Reads a Kokoro `voices-v1.0.bin`.
 *
 * Despite the extension the file is normally a NumPy `.npz` — a zip of `.npy`
 * members, one per voice — which is why this parses zip entries and NPY headers
 * rather than reading a flat blob. Some redistributions really are a flat blob,
 * so that case is handled too, as a single unnamed voice.
 */
object VoiceBankReader {

    private const val MAGIC = "NUMPY"
    private const val DEFAULT_DIM = 256

    fun read(input: InputStream): VoiceBank {
        val bytes = input.readBytesCompat()
        return if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            readNpz(bytes)
        } else {
            readFlat(bytes)
        }
    }

    /**
     * Just the voice names, without decoding a single sample.
     *
     * The pack is tens of megabytes of float32; parsing all of it to answer
     * "which voices are in here?" would cost seconds and the memory of every
     * embedding, for a dropdown. The names are zip entry names, so this walks
     * the central structure and stops. It also means the list can be shown the
     * moment the file is imported, before the ONNX graph exists.
     */
    fun readNames(input: InputStream): List<String> {
        val out = ArrayList<String>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = try {
                    zip.nextEntry ?: break
                } catch (e: Exception) {
                    break // not a zip after all
                }
                if (!entry.isDirectory) {
                    val name = entry.name.substringAfterLast('/').removeSuffix(".npy")
                    if (name.isNotBlank()) out.add(name)
                }
                zip.closeEntry()
            }
        }
        // A flat blob has no entries at all: it is one unnamed voice.
        return if (out.isEmpty()) listOf("default") else out.sorted()
    }

    private fun readNpz(bytes: ByteArray): VoiceBank {
        val out = LinkedHashMap<String, VoiceStyles>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) { zip.closeEntry(); continue }
                val name = entry.name.substringAfterLast('/').removeSuffix(".npy")
                val body = zip.readBytesCompat()
                runCatching { parseNpy(body) }.getOrNull()?.let { out[name] = it }
                zip.closeEntry()
            }
        }
        return VoiceBank(out)
    }

    /**
     * Minimal NPY v1/v2 reader for little-endian float32. Anything else is
     * rejected rather than reinterpreted — a wrong dtype would produce a voice
     * made of noise, which is worse than a clear failure.
     */
    private fun parseNpy(body: ByteArray): VoiceStyles? {
        if (body.size < 12) return null
        for (i in MAGIC.indices) if (body[i] != MAGIC[i].code.toByte()) return null
        val major = body[6].toInt()
        val headerLen: Int
        val dataStart: Int
        if (major >= 2) {
            headerLen = ByteBuffer.wrap(body, 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
            dataStart = 12 + headerLen
        } else {
            headerLen = ByteBuffer.wrap(body, 8, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
            dataStart = 10 + headerLen
        }
        if (dataStart > body.size) return null
        val header = String(body, dataStart - headerLen, headerLen, Charsets.US_ASCII)
        if (!header.contains("'<f4'") && !header.contains("\"<f4\"")) return null
        if (header.contains("'fortran_order': True")) return null

        val shape = SHAPE.find(header)?.groupValues?.get(1)
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?: return null
        if (shape.isEmpty()) return null

        val total = (body.size - dataStart) / 4
        // Kokoro packs are (rows, 1, dim); anything else is flattened to
        // (total/lastDim, lastDim) so an unexpected rank still plays.
        val dim = shape.last()
        if (dim <= 0) return null
        val rows = total / dim
        if (rows <= 0) return null

        val data = FloatArray(rows * dim)
        val buf = ByteBuffer.wrap(body, dataStart, rows * dim * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in data.indices) data[i] = buf.float
        return VoiceStyles(rows, dim, data)
    }

    private val SHAPE = Regex("""'shape'\s*:\s*\(([^)]*)\)""")

    private fun readFlat(bytes: ByteArray): VoiceBank {
        val total = bytes.size / 4
        val rows = total / DEFAULT_DIM
        if (rows <= 0) return VoiceBank(emptyMap())
        val data = FloatArray(rows * DEFAULT_DIM)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in data.indices) data[i] = buf.float
        return VoiceBank(mapOf("default" to VoiceStyles(rows, DEFAULT_DIM, data)))
    }

    /** `readBytes()` on a stream is JDK 9+; `:core` targets a lower baseline. */
    private fun InputStream.readBytesCompat(): ByteArray {
        val out = ByteArrayOutputStream(1 shl 16)
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val n = read(buffer)
            if (n <= 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
