package com.simone.jarvismobile.core.memory

import kotlin.test.Test
import kotlin.test.assertEquals

class WordPieceTokenizerTest {

    // A tiny uncased vocab, ids arbitrary but stable.
    private val vocab = mapOf(
        "[PAD]" to 0, "[UNK]" to 1, "[CLS]" to 2, "[SEP]" to 3,
        "gioco" to 10, "play" to 11, "##ing" to 12, "casa" to 13, "!" to 14, "a" to 15,
    )
    private val tok = WordPieceTokenizer(vocab)

    @Test fun wrapsWithClsAndSep() {
        val ids = tok.encode("casa")
        assertEquals(2, ids.first())
        assertEquals(3, ids.last())
        assertEquals(listOf(2, 13, 3), ids.toList())
    }

    @Test fun lowercasesBeforeLookup() {
        assertEquals(listOf(2, 13, 3), tok.encode("CASA").toList())
    }

    @Test fun greedySubWordsWithContinuation() {
        // "playing" -> "play" + "##ing"
        assertEquals(listOf(2, 11, 12, 3), tok.encode("playing").toList())
    }

    @Test fun punctuationIsSplitOff() {
        // "casa!" -> "casa", "!"
        assertEquals(listOf(2, 13, 14, 3), tok.encode("casa!").toList())
    }

    @Test fun outOfVocabWordBecomesUnk() {
        assertEquals(listOf(2, 1, 3), tok.encode("xyzzy").toList())
    }

    @Test fun multipleWords() {
        assertEquals(listOf(2, 10, 13, 3), tok.encode("gioco casa").toList())
    }
}
