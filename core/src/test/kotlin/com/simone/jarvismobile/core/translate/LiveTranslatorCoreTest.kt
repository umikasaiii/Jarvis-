package com.simone.jarvismobile.core.translate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveTranslatorCoreTest {

    // --- language model ----------------------------------------------------

    @Test
    fun `language names and codes resolve`() {
        assertEquals(TranslationLanguage.JAPANESE, TranslationLanguage.fromName("giapponese"))
        assertEquals(TranslationLanguage.JAPANESE, TranslationLanguage.fromName("日本語"))
        assertEquals(TranslationLanguage.ENGLISH, TranslationLanguage.fromName("English"))
        assertEquals(TranslationLanguage.FRENCH, TranslationLanguage.fromName("francese"))
        assertEquals(TranslationLanguage.ITALIAN, TranslationLanguage.fromCode("it-IT"))
        assertNull(TranslationLanguage.fromName("tedesco"))
        assertNull(TranslationLanguage.fromCode("de"))
    }

    // --- bidirectional routing --------------------------------------------

    @Test
    fun `detected language routes to the other side of the pair`() {
        val it = TranslationLanguage.ITALIAN
        val ja = TranslationLanguage.JAPANESE
        assertEquals(it to ja, LivePairRouter.route(it, it, ja))
        assertEquals(ja to it, LivePairRouter.route(ja, it, ja))
        assertNull(LivePairRouter.route(TranslationLanguage.FRENCH, it, ja))
    }

    // --- language stabiliser ----------------------------------------------

    @Test
    fun `first confident detection is taken immediately`() {
        val s = LanguageStabilizer(minConfidence = 0.5f, switchThreshold = 2)
        assertEquals(TranslationLanguage.ITALIAN, s.offer(TranslationLanguage.ITALIAN, 0.9f))
    }

    @Test
    fun `a single foreign blip does not switch the language`() {
        val s = LanguageStabilizer(minConfidence = 0.5f, switchThreshold = 2)
        s.offer(TranslationLanguage.ITALIAN, 0.9f)
        // One Spanish detection (it/es look alike) must not flip it yet.
        assertEquals(TranslationLanguage.ITALIAN, s.offer(TranslationLanguage.SPANISH, 0.8f))
        // Persisting flips it.
        assertEquals(TranslationLanguage.SPANISH, s.offer(TranslationLanguage.SPANISH, 0.8f))
        assertEquals(TranslationLanguage.ITALIAN, s.previous)
    }

    @Test
    fun `low confidence holds the current language`() {
        val s = LanguageStabilizer(minConfidence = 0.6f)
        s.offer(TranslationLanguage.ITALIAN, 0.9f)
        assertEquals(TranslationLanguage.ITALIAN, s.offer(TranslationLanguage.JAPANESE, 0.2f))
    }

    // --- segmenter ---------------------------------------------------------

    @Test
    fun `a final transcript becomes one segment`() {
        val seg = TranslationSegmenter()
        assertEquals(listOf("Voglio andare alla stazione."), seg.offer("Voglio andare alla stazione.", true, 0))
    }

    @Test
    fun `tiny fragments are not emitted`() {
        val seg = TranslationSegmenter()
        assertTrue(seg.offer("I", false, 0).isEmpty())
        assertTrue(seg.offer("voglio", false, 100).isEmpty())
    }

    @Test
    fun `a sentence end inside a growing partial emits it once`() {
        val seg = TranslationSegmenter()
        assertTrue(seg.offer("Dove si trova", false, 0).isEmpty())
        assertEquals(listOf("Dove si trova la stazione?"), seg.offer("Dove si trova la stazione?", false, 50))
        // The same partial offered again does not re-emit.
        assertTrue(seg.offer("Dove si trova la stazione?", false, 80).isEmpty())
    }

    @Test
    fun `a quiet gap closes a tail without punctuation`() {
        val seg = TranslationSegmenter(quietMs = 500)
        assertTrue(seg.offer("Voglio andare", false, 0).isEmpty())
        assertEquals(listOf("Voglio andare"), seg.offer("Voglio andare", false, 600))
    }

    @Test
    fun `japanese without spaces still segments on the full stop`() {
        val seg = TranslationSegmenter()
        assertEquals(listOf("駅はどこですか？"), seg.offer("駅はどこですか？", true, 0))
    }

    // --- command parsing ---------------------------------------------------

    @Test
    fun `start with an explicit pair extracts both languages`() {
        val cmd = LiveTranslatorCommands.parse("Avvia traduzione live italiano giapponese")
        assertTrue(cmd is LiveTranslatorCommand.Start)
        cmd as LiveTranslatorCommand.Start
        assertEquals(TranslationLanguage.ITALIAN, cmd.source)
        assertEquals(TranslationLanguage.JAPANESE, cmd.target)
    }

    @Test
    fun `interpreter phrasing also starts with a pair`() {
        val cmd = LiveTranslatorCommands.parse("Fammi da interprete italiano francese") as LiveTranslatorCommand.Start
        assertEquals(TranslationLanguage.ITALIAN, cmd.source)
        assertEquals(TranslationLanguage.FRENCH, cmd.target)
    }

    @Test
    fun `start without a pair leaves languages null`() {
        val cmd = LiveTranslatorCommands.parse("Avvia il traduttore") as LiveTranslatorCommand.Start
        assertNull(cmd.source)
        assertNull(cmd.target)
    }

    @Test
    fun `stop and swap are recognised`() {
        assertEquals(LiveTranslatorCommand.Stop, LiveTranslatorCommands.parse("Termina traduzione"))
        assertEquals(LiveTranslatorCommand.Stop, LiveTranslatorCommands.parse("Stop traduzione live"))
        assertEquals(LiveTranslatorCommand.Swap, LiveTranslatorCommands.parse("Scambia le lingue"))
    }

    @Test
    fun `ordinary chat is not a translator command`() {
        assertNull(LiveTranslatorCommands.parse("Che ore sono?"))
        assertNull(LiveTranslatorCommands.parse("Accendi la torcia"))
    }
}
