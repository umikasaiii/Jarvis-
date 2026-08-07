package com.simone.jarvismobile.core.tts

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class VoiceBankAndVocabularyTest {

    // --- vocabulary ---------------------------------------------------------

    @Test
    fun `built-in table is built from the symbol groups in order`() {
        val v = PhonemeVocabulary.BUILT_IN
        assertEquals(0, v['$'])
        assertEquals(16, v[' '])   // last punctuation symbol
        assertEquals(17, v['A'])   // letters start right after
        assertEquals(43, v['a'])
        assertEquals(68, v['z'])
        assertEquals(69, v['ɑ'])   // IPA block starts right after
        assertEquals("integrata", v.source)
    }

    @Test
    fun `built-in table has no duplicate symbols`() {
        val symbols = PhonemeVocabulary.builtInSymbols()
        assertEquals(symbols.length, symbols.toSet().size)
    }

    @Test
    fun `encode drops symbols the model does not know`() {
        val v = PhonemeVocabulary.BUILT_IN
        // '@' is in no group, so it must not become a token at all.
        assertEquals(v.encode("ao").size, v.encode("a@o").size)
    }

    @Test
    fun `tokens_txt overrides the built-in table`() {
        val text = buildString {
            (0 until 40).forEach { append("${('a' + it % 26)}$it\n".let { _ -> "" }) }
        }
        // A realistic tokens.txt: "symbol id" per line, space written as <space>.
        val real = StringBuilder()
        "abcdefghijklmnopqrstuvwxyzɑɔəɛʃʒɲʎɡtsdʒ".toSet().forEachIndexed { i, c ->
            real.append("$c ${i + 1}\n")
        }
        real.append("<space> 0\n")
        val v = PhonemeVocabulary.parseTokens(real.toString())
        assertNotNull(v, text)
        assertEquals("tokens.txt", v!!.source)
        assertEquals(0, v[' '])
        assertEquals(1, v['a'])
    }

    @Test
    fun `a too-small tokens file is rejected rather than half-applied`() {
        assertNull(PhonemeVocabulary.parseTokens("a 1\nb 2\n"))
    }

    @Test
    fun `config json vocab is read`() {
        val vocab = buildString {
            append("""{"istftnet": {}, "vocab": {"$": 0, " ": 16, "a": 43""")
            "bcdefghijklmnopqrstuvwxyz".forEachIndexed { i, c -> append(""", "$c": ${44 + i}""") }
            append(""", "ɑ": 69""")
            "ɐɒæɓʙβɔɕç".forEachIndexed { i, c -> append(""", "$c": ${70 + i}""") }
            append("""}, "n_token": 178}""")
        }
        val v = PhonemeVocabulary.parse(vocab)
        assertNotNull(v)
        assertEquals("config.json", v!!.source)
        assertEquals(0, v['$'])
        assertEquals(43, v['a'])
        assertEquals(69, v['ɑ'])   // written as a \u escape
        assertEquals(76, v['ɔ'])
    }

    // --- voice bank ---------------------------------------------------------

    private fun npy(rows: Int, dim: Int, fill: (Int) -> Float): ByteArray {
        val header = "{'descr': '<f4', 'fortran_order': False, 'shape': ($rows, 1, $dim), }"
        // v1 headers are padded so the data starts on a 64-byte boundary.
        val pad = (64 - (10 + header.length + 1) % 64) % 64
        val full = header + " ".repeat(pad) + "\n"
        val out = ByteArrayOutputStream()
        out.write(0x93)
        out.write("NUMPY".toByteArray(Charsets.US_ASCII))
        out.write(1); out.write(0)
        val len = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(full.length.toShort())
        out.write(len.array())
        out.write(full.toByteArray(Charsets.US_ASCII))
        val body = ByteBuffer.allocate(rows * dim * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until rows * dim) body.putFloat(fill(i))
        out.write(body.array())
        return out.toByteArray()
    }

    private fun npz(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(body)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `reads every voice out of an npz`() {
        val blob = npz(
            "if_sara.npy" to npy(4, 8) { it.toFloat() },
            "im_nicola.npy" to npy(4, 8) { -it.toFloat() },
        )
        val bank = VoiceBankReader.read(blob.inputStream())
        assertEquals(listOf("if_sara", "im_nicola"), bank.names)
        val sara = bank.get("if_sara")!!
        assertEquals(8, sara.styleFor(0).size)
        assertEquals(0f, sara.styleFor(0)[0], 1e-6f)
        // Row 2 starts at 2*dim.
        assertEquals(16f, sara.styleFor(2)[0], 1e-6f)
    }

    @Test
    fun `a style request past the end of the pack clamps instead of crashing`() {
        val bank = VoiceBankReader.read(npz("v.npy" to npy(3, 4) { it.toFloat() }).inputStream())
        val v = bank.get("v")!!
        assertEquals(v.styleFor(2).toList(), v.styleFor(9_999).toList())
        assertEquals(v.styleFor(0).toList(), v.styleFor(-5).toList())
    }

    @Test
    fun `a flat blob is accepted as one unnamed voice`() {
        val floats = ByteBuffer.allocate(512 * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(512) { floats.putFloat(it.toFloat()) }
        val bank = VoiceBankReader.read(floats.array().inputStream())
        assertEquals(listOf("default"), bank.names)
        assertEquals(256, bank.get("default")!!.styleFor(0).size)
    }

    @Test
    fun `a wrong dtype is refused rather than reinterpreted`() {
        val header = "{'descr': '<f8', 'fortran_order': False, 'shape': (2, 1, 4), }\n"
        val out = ByteArrayOutputStream()
        out.write(0x93); out.write("NUMPY".toByteArray()); out.write(1); out.write(0)
        out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(header.length.toShort()).array())
        out.write(header.toByteArray())
        out.write(ByteArray(2 * 4 * 8))
        val bank = VoiceBankReader.read(npz("bad.npy" to out.toByteArray()).inputStream())
        assertTrue(bank.isEmpty)
    }
}
