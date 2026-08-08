package com.simone.jarvismobile.core.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PiperVoiceConfigTest {

    /** Shaped like a real it_IT-*-medium.onnx.json, trimmed to what is read. */
    private fun config(
        speakers: String = """"speaker_id_map": {}, "num_speakers": 1""",
        sampleRate: Int = 22050,
    ) = """
        {
          "dataset": "paola",
          "audio": {"sample_rate": $sampleRate, "quality": "medium"},
          "espeak": {"voice": "it"},
          "inference": {"noise_scale": 0.667, "length_scale": 1.0, "noise_w": 0.8},
          "phoneme_type": "espeak",
          "phoneme_id_map": {
            "_": [0], "^": [1], "$": [2], " ": [3], "!": [4], "'": [5], ",": [8],
            "-": [9], ".": [10], "a": [14], "b": [15], "d": [17], "e": [18],
            "f": [19], "i": [23], "k": [25], "l": [26], "m": [27], "n": [28],
            "o": [29], "p": [30], "r": [32], "s": [33], "t": [34], "u": [35],
            "v": [36], "z": [39], "ʃ": [50], "ʧ": [51], "ɲ": [52],
            "ˈ": [120], "ɡ": [61]
          },
          $speakers
        }
    """.trimIndent()

    @Test
    fun `reads the fields that affect synthesis`() {
        val c = PiperConfigReader.parse(config())!!
        assertEquals(22050, c.sampleRate)
        assertEquals(0.667f, c.noiseScale)
        assertEquals(1.0f, c.lengthScale)
        assertEquals(0.8f, c.noiseW)
        assertEquals("it", c.language)
        assertEquals(listOf("default"), c.speakers)
        assertTrue(!c.isMultiSpeaker)
    }

    @Test
    fun `a non-default sample rate is honoured`() {
        assertEquals(16000, PiperConfigReader.parse(config(sampleRate = 16000))!!.sampleRate)
    }

    @Test
    fun `multi-speaker voices are listed in id order`() {
        val c = PiperConfigReader.parse(
            config(speakers = """"speaker_id_map": {"paola": 1, "marco": 0}, "num_speakers": 2"""),
        )!!
        assertEquals(listOf("marco", "paola"), c.speakers)
        assertEquals(1, c.speakerId("paola"))
        assertTrue(c.isMultiSpeaker)
    }

    @Test
    fun `encoding pads between phonemes and brackets the sequence`() {
        val c = PiperConfigReader.parse(config())!!
        // "ab" -> BOS, a, PAD, b, PAD, EOS
        assertEquals(listOf(1, 14, 0, 15, 0, 2), c.encode("ab").toList())
    }

    @Test
    fun `phonemes the voice has no symbol for are dropped, not mapped to pad`() {
        val c = PiperConfigReader.parse(config())!!
        // 'q' is absent from the map above; the sequence must match plain "ab".
        assertEquals(c.encode("ab").toList(), c.encode("aqb").toList())
    }

    @Test
    fun `the italian phonemiser output survives the voice's own table`() {
        val c = PiperConfigReader.parse(config())!!
        val phonemes = ItalianG2P.phonemize("Ciao Simone, come stai?")
        val ids = c.encode(phonemes)
        // BOS + EOS alone would be 2; real content must have made it through.
        assertTrue(ids.size > 12, "encoded=${ids.size} from '$phonemes'")
        assertEquals(1, ids.first())
        assertEquals(2, ids.last())
    }

    @Test
    fun `a precomposed affricate symbol matches the decomposed g2p output`() {
        val c = PiperConfigReader.parse(config())!!
        // The config keys the affricate as the single ʧ (id 51); the G2P emits
        // t + ʃ. Longest-match must use the affricate id, not t(34)+ʃ(50) — this
        // is the ce/ci, ge/gi sound that used to be lost.
        assertEquals(listOf(1, 51, 0, 2), c.encode("tʃ").toList())
    }

    @Test
    fun `a tie-bar affricate key is normalised and still matches`() {
        val json = "{\"phoneme_id_map\":{" +
            "\"_\":[0],\"^\":[1],\"\$\":[2]," +
            "\"t\":[34],\"ʃ\":[50],\"t͡ʃ\":[70]," +
            "\"a\":[14],\"b\":[15],\"d\":[17],\"e\":[18],\"f\":[19]," +
            "\"i\":[23],\"k\":[25],\"l\":[26],\"m\":[27],\"n\":[28]}}"
        val c = PiperConfigReader.parse(json)!!
        // "t͡ʃ" (with the U+0361 tie bar) normalises to "tʃ" and wins over bare t.
        assertEquals(listOf(1, 70, 0, 2), c.encode("tʃ").toList())
    }

    @Test
    fun `a file that is not a piper config is refused`() {
        assertNull(PiperConfigReader.parse("""{"vocab": {"a": 1}}"""))
        assertNull(PiperConfigReader.parse("not json at all"))
        // Too few symbols to be a real voice table.
        assertNull(PiperConfigReader.parse("""{"phoneme_id_map": {"a": [1], "b": [2]}}"""))
    }
}
