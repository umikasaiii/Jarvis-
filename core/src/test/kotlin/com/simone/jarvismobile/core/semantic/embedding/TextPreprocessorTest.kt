package com.simone.jarvismobile.core.semantic.embedding

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Serializable
private data class GoldenVector(val name: String, val input: String, val expectedCanonical: String)

@Serializable
private data class GoldenFixture(val contractVersion: Int, val vectors: List<GoldenVector>)

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 13 §P/§Q (Golden Parity
 * Vectors + Cross-Runtime Parity). Loads the SAME
 * `preprocessing_golden.json` fixture `tools/semantic_classifier/
 * preprocessing.py`'s own self-test reads — its `expectedCanonical` values
 * were computed by REAL execution of Python's `unicodedata.normalize("NFC",
 * ...)` in this environment (not invented), and this test proves Kotlin's
 * [java.text.Normalizer] produces byte-identical output for every vector —
 * genuine cross-runtime parity for the preprocessing layer (§Q).
 *
 * This does NOT establish tokenizer-level (token id) parity — no verified
 * tokenizer implementation exists yet (§D/§Q); that gate remains explicitly
 * PENDING, see this pass's final report.
 */
class TextPreprocessorTest {

    private fun loadFixture(): GoldenFixture {
        val stream = requireNotNull(javaClass.classLoader.getResourceAsStream("semantic/preprocessing_golden.json")) {
            "golden fixture missing from test resources"
        }
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return Json { ignoreUnknownKeys = true }.decodeFromString(GoldenFixture.serializer(), text)
    }

    @Test
    fun `every golden vector canonicalizes to the exact value computed by real Python NFC execution`() {
        val fixture = loadFixture()
        assertTrue(fixture.vectors.isNotEmpty(), "golden fixture must not be empty")
        fixture.vectors.forEach { vector ->
            val actual = TextPreprocessor.canonicalize(vector.input)
            assertEquals(vector.expectedCanonical, actual, "vector=${vector.name}")
        }
    }

    @Test
    fun `ordinary Italian sentence is left unchanged`() {
        assertEquals("Che tempo fa oggi a Roma?", TextPreprocessor.canonicalize("Che tempo fa oggi a Roma?"))
    }

    @Test
    fun `punctuation is preserved exactly`() {
        assertEquals("Ciao, come va?! Bene...", TextPreprocessor.canonicalize("Ciao, come va?! Bene..."))
    }

    @Test
    fun `apostrophe is preserved exactly`() {
        assertEquals("L'appuntamento di oggi", TextPreprocessor.canonicalize("L'appuntamento di oggi"))
    }

    @Test
    fun `decomposed accented characters compose to NFC precomposed form`() {
        // "e" + combining acute accent (U+0301), twice — genuinely decomposed
        // input, not already-composed text the test would trivially pass on.
        val decomposed = "café e perché"
        val expectedComposed = "café e perché"
        assertEquals(expectedComposed, TextPreprocessor.canonicalize(decomposed))
    }

    @Test
    fun `casing is never altered — preserve policy`() {
        assertEquals("CIAO come STAI", TextPreprocessor.canonicalize("CIAO come STAI"))
    }

    @Test
    fun `repeated whitespace collapses to a single space`() {
        assertEquals("Che tempo fa", TextPreprocessor.canonicalize("Che    tempo   fa"))
    }

    @Test
    fun `newlines collapse to a single space`() {
        assertEquals("Riga uno Riga due", TextPreprocessor.canonicalize("Riga uno\nRiga due"))
    }

    @Test
    fun `tabs and multiple newlines all collapse to single spaces`() {
        assertEquals("Uno Due Tre", TextPreprocessor.canonicalize("Uno\t\tDue\n\n\nTre"))
    }

    @Test
    fun `leading and trailing whitespace is trimmed`() {
        assertEquals("Ciao mondo", TextPreprocessor.canonicalize("   Ciao mondo   "))
    }

    @Test
    fun `emoji and non-ASCII characters are preserved`() {
        assertEquals("Bello! 🎉😀", TextPreprocessor.canonicalize("Bello! 🎉😀"))
    }

    @Test
    fun `canonicalize is idempotent — applying it twice yields the same result`() {
        val once = TextPreprocessor.canonicalize("  Che    tempo\nfa  oggi?  ")
        val twice = TextPreprocessor.canonicalize(once)
        assertEquals(once, twice)
    }
}
