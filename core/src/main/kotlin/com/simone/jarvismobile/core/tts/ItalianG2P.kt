package com.simone.jarvismobile.core.tts

/**
 * Italian grapheme-to-phoneme: written Italian in, IPA out.
 *
 * A Kokoro-style model is not fed text, it is fed phoneme tokens, so something
 * has to do the job espeak-ng does in the Python tooling. Shipping espeak-ng
 * would mean an NDK build plus its data files; Italian spelling is regular
 * enough that a rule transducer gets most of the way there, and it lives in
 * `:core` where it can actually be tested.
 *
 * What it does NOT do: pick open vs closed mid vowels (that is lexical — "pesca"
 * is two different words), or resolve a diphthong that is really a hiatus
 * ("mio" comes out as one syllable). Both are audible to a native ear and both
 * are the price of not bundling a lexicon. Everything else — the c/g softening,
 * gn/gl, s voicing, geminates, penultimate stress and written accents — is
 * handled.
 */
object ItalianG2P {

    private const val VOWELS = "aeiou"
    private const val ACCENTED = "àèéìíòóùúÀÈÉÌÍÒÓÙÚ"

    /** Consonants that make a preceding `s` voice into /z/. */
    private const val VOICED = "bdgjlmnrvz"

    private val KEEP_PUNCTUATION = setOf(',', '.', '!', '?', ';', ':', '…', '—')

    private data class Base(
        /** Accent-stripped lowercase letters. */
        val chars: CharArray,
        /** Index into [chars] of the written accent, or -1. */
        val accentAt: Int,
        /** Phoneme forced by the written accent (è -> ɛ, ò -> ɔ), else null. */
        val accentPhoneme: Char?,
    )

    /**
     * Phonemises a whole reply. Words are separated by spaces and sentence
     * punctuation is preserved, because that is what the model listens to for
     * phrasing — strip the commas and it reads everything in one breath.
     */
    fun phonemize(text: String): String {
        val spelled = ItalianNumbers.spellAll(text)
        val out = StringBuilder()
        var word = StringBuilder()

        fun flush() {
            if (word.isEmpty()) return
            val p = word(word.toString())
            if (p.isNotEmpty()) {
                if (out.isNotEmpty() && out.last() != ' ') out.append(' ')
                out.append(p)
            }
            word = StringBuilder()
        }

        for (c in spelled) {
            when {
                c.isLetter() || c == '\'' -> word.append(c)
                else -> {
                    flush()
                    if (c in KEEP_PUNCTUATION) {
                        out.append(c)
                    } else if (c.isWhitespace() && out.isNotEmpty() && out.last() != ' ') {
                        out.append(' ')
                    }
                }
            }
        }
        flush()
        return out.toString().trim()
    }

    /** Phonemises a single word, stress included. Public for tests. */
    fun word(raw: String): String {
        val cleaned = raw.trim('\'', '’').lowercase()
        if (cleaned.isEmpty()) return ""
        val base = strip(cleaned)
        val phones = ArrayList<Char>(cleaned.length + 4)
        // Index in [phones] of each syllable nucleus, and the source index it came from.
        val nuclei = ArrayList<Int>(4)
        val nucleusSource = ArrayList<Int>(4)

        val cs = base.chars
        var i = 0

        fun at(k: Int): Char? = cs.getOrNull(k)
        fun isVowel(k: Int): Boolean = at(k)?.let { it in VOWELS } ?: false
        fun emit(c: Char) = phones.add(c)
        fun emitNucleus(c: Char, src: Int) {
            nuclei.add(phones.size)
            nucleusSource.add(src)
            phones.add(c)
        }

        while (i < cs.size) {
            val c = cs[i]
            val n1 = at(i + 1)
            val n2 = at(i + 2)
            when {
                c == 'h' -> i++ // always silent in Italian

                c == 'c' && n1 == 'h' -> { emit('k'); i += 2 }
                // "cce"/"cci" is a geminate affricate: /tːʃ/, written t + tʃ.
                c == 'c' && n1 == 'c' && (n2 == 'e' || n2 == 'i') -> { emit('t'); i++ }
                c == 'c' && n1 == 'i' && isVowel(i + 2) -> { emit('t'); emit('ʃ'); i += 2 }
                c == 'c' && (n1 == 'e' || n1 == 'i') -> { emit('t'); emit('ʃ'); i++ }
                c == 'c' -> { emit('k'); i++ }

                c == 'g' && n1 == 'h' -> { emit('ɡ'); i += 2 }
                c == 'g' && n1 == 'n' -> { emit('ɲ'); i += 2 }
                c == 'g' && n1 == 'l' && n2 == 'i' -> {
                    emit('ʎ')
                    if (isVowel(i + 3)) i += 3 else { emitNucleus('i', i + 2); i += 3 }
                }
                c == 'g' && n1 == 'g' && (n2 == 'e' || n2 == 'i') -> { emit('d'); i++ }
                c == 'g' && n1 == 'i' && isVowel(i + 2) -> { emit('d'); emit('ʒ'); i += 2 }
                c == 'g' && (n1 == 'e' || n1 == 'i') -> { emit('d'); emit('ʒ'); i++ }
                c == 'g' && n1 == 'u' && isVowel(i + 2) -> { emit('ɡ'); emit('w'); i += 2 }
                c == 'g' -> { emit('ɡ'); i++ }

                c == 's' && n1 == 'c' && n2 == 'h' -> { emit('s'); emit('k'); i += 3 }
                c == 's' && n1 == 'c' && n2 == 'i' && isVowel(i + 3) -> { emit('ʃ'); i += 3 }
                c == 's' && n1 == 'c' && (n2 == 'e' || n2 == 'i') -> { emit('ʃ'); i += 2 }
                c == 's' && n1 == 'c' -> { emit('s'); emit('k'); i += 2 }
                c == 's' -> {
                    // /z/ between vowels and before a voiced consonant, /s/ otherwise.
                    val voiced = (i > 0 && isVowel(i - 1) && isVowel(i + 1)) ||
                        (n1 != null && n1 in VOICED)
                    emit(if (voiced) 'z' else 's')
                    i++
                }

                c == 'z' && n1 == 'z' -> { emit('t'); emit('t'); emit('s'); i += 2 }
                c == 'z' -> { emit('t'); emit('s'); i++ }

                c == 'q' && n1 == 'u' -> { emit('k'); emit('w'); i += 2 }
                c == 'q' -> { emit('k'); i++ }

                c == 'x' -> { emit('k'); emit('s'); i++ }
                c == 'y' -> { emitNucleus('i', i); i++ }
                c == 'j' -> { emit('j'); i++ }
                c == 'w' -> { emit('w'); i++ }

                c in VOWELS -> {
                    val forced = if (i == base.accentAt) base.accentPhoneme else null
                    // A short word ending in i/u + vowel is almost always a hiatus
                    // ("zio", "mio", "due"), while a longer one is a diphthong
                    // ("occhio", "piano"). Without a lexicon that length test is
                    // the best available split between the two.
                    val finalHiatus = isVowel(i + 1) && cs.size <= 4 && i == cs.size - 2
                    val glide = forced == null && (c == 'i' || c == 'u') && !finalHiatus &&
                        (isVowel(i + 1) || (i > 0 && isVowel(i - 1) && !isVowel(i + 1)))
                    if (glide) {
                        emit(if (c == 'i') 'j' else 'w')
                    } else {
                        emitNucleus(forced ?: c, i)
                    }
                    i++
                }

                c in "bdfklmnprtv" -> { emit(c); i++ }
                else -> i++ // anything else has no Italian value
            }
        }

        if (phones.isEmpty()) return ""

        // --- stress ---------------------------------------------------------
        // A written accent wins. Otherwise Italian stresses the penultimate
        // syllable, which is right far more often than any other single guess.
        // Monosyllables are left unmarked: most of them are function words and
        // marking them makes the whole line sound stamped out.
        val target = when {
            base.accentAt >= 0 -> nucleusSource.indexOf(base.accentAt).takeIf { it >= 0 }
            nuclei.size >= 2 -> nuclei.size - 2
            else -> null
        }
        if (target != null) {
            // The mark goes before the syllable's onset, and an Italian syllable
            // takes at most one consonant plus a liquid/glide (or an initial s).
            // Swallowing the whole cluster would put "sessantotto" as
            // "sessaˈntotto" — the /n/ closes the previous syllable, it does not
            // open this one.
            val previous = if (target > 0) nuclei[target - 1] else -1
            var onset = nuclei[target]
            if (onset - 1 > previous) onset--
            if (onset - 1 > previous && isOnsetCluster(phones[onset - 1], phones[onset])) onset--
            phones.add(onset, 'ˈ')
        }
        return phones.joinToString("")
    }

    /**
     * Obstruent + liquid/glide, s/z + consonant, or an affricate.
     *
     * The affricate case is not phonotactics, it is bookkeeping: /tʃ/ and /dʒ/
     * are written here as two characters, and the stress mark must land in front
     * of the pair. Without it "ciao" comes out as "tˈʃao" — stress inside a
     * single sound.
     */
    private fun isOnsetCluster(first: Char, second: Char): Boolean =
        (second in "lrjw" && first in "pbtdkɡfvsz") ||
            (first in "sz" && second in "pbtdkɡfvmnlr") ||
            (first == 't' && (second == 'ʃ' || second == 's')) ||
            (first == 'd' && (second == 'ʒ' || second == 'z'))

    /**
     * Splits the written accent off the letters: "perché" becomes "perche" plus
     * "the accent is on index 5, and it is a closed e".
     */
    private fun strip(word: String): Base {
        val sb = StringBuilder(word.length)
        var accentAt = -1
        var phoneme: Char? = null
        for (c in word) {
            val idx = ACCENTED.indexOf(c)
            if (idx >= 0) {
                accentAt = sb.length
                val (letter, ipa) = when (c.lowercaseChar()) {
                    'à' -> 'a' to 'a'
                    'è' -> 'e' to 'ɛ'
                    'é' -> 'e' to 'e'
                    'ì', 'í' -> 'i' to 'i'
                    'ò' -> 'o' to 'ɔ'
                    'ó' -> 'o' to 'o'
                    else -> 'u' to 'u'
                }
                phoneme = ipa
                sb.append(letter)
            } else {
                sb.append(c)
            }
        }
        return Base(sb.toString().toCharArray(), accentAt, phoneme)
    }
}
