package com.simone.jarvismobile.core.tts

/**
 * Maps phoneme characters to the token ids a Kokoro/StyleTTS2 ONNX graph expects.
 *
 * The table is NOT hard-coded as a literal dictionary. It is rebuilt from the
 * same four symbol groups the upstream model uses, in the same order, because a
 * transcribed dictionary is exactly the kind of thing that is silently wrong by
 * two entries and turns every reply into noise.
 *
 * Even so, the built-in table is a *fallback*. A model package normally ships its
 * own vocabulary — `tokens.txt` (one `symbol id` per line) or a `config.json`
 * with a `vocab` object — and when the user imports one, that file wins. The UI
 * says which of the two is in force, so a mispronouncing voice can be diagnosed
 * instead of guessed at.
 */
class PhonemeVocabulary(
    private val ids: Map<Char, Int>,
    /** Where the table came from, for the Settings screen. */
    val source: String,
) {
    val size: Int get() = ids.size

    operator fun get(c: Char): Int? = ids[c]

    fun contains(c: Char): Boolean = ids.containsKey(c)

    /**
     * Turns a phoneme string into token ids, dropping anything the model has no
     * symbol for. Unknown characters are skipped rather than mapped to a padding
     * id: a wrong token is heard, a missing one usually is not.
     */
    fun encode(phonemes: String): IntArray {
        val out = ArrayList<Int>(phonemes.length + 2)
        for (c in phonemes) ids[c]?.let(out::add)
        return out.toIntArray()
    }

    companion object {
        const val PAD = "$"
        const val PUNCTUATION = ";:,.!?¡¿—…\"«»“” "
        const val LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        const val LETTERS_IPA =
            "ɑɐɒæɓʙβɔɕçɗɖðʤəɘɚɛɜɝɞɟʄɡɠɢʛɦɧħɥʜɨɪʝɭɬɫɮʟɱɯɰŋɳɲɴøɵɸθœɶʘɹɺɾɻʀʁɽʂʃʈʧʉʊʋⱱʌɣɤʍχʎʏʑʐʒʔʡʕʢǀǁǂǃˈˌːˑʼʴʰʱʲʷˠˤ˞↓↑→↗↘'ᵻ"

        /** The order the symbols are concatenated in; index == token id. */
        fun builtInSymbols(): String = PAD + PUNCTUATION + LETTERS + LETTERS_IPA

        val BUILT_IN: PhonemeVocabulary by lazy {
            val map = LinkedHashMap<Char, Int>()
            builtInSymbols().forEachIndexed { i, c -> map.putIfAbsent(c, i) }
            PhonemeVocabulary(map, "integrata")
        }

        /**
         * Reads a sherpa-onnx style `tokens.txt`: one `symbol id` per line,
         * whitespace separated. The space symbol is written as an escape in some
         * packages, so a line with a single field is treated as the space token.
         */
        fun parseTokens(text: String): PhonemeVocabulary? {
            val map = LinkedHashMap<Char, Int>()
            text.lineSequence().forEach { raw ->
                val line = raw.trimEnd('\n', '\r')
                if (line.isBlank()) return@forEach
                val cut = line.lastIndexOf(' ')
                if (cut <= 0) return@forEach
                val symbol = line.substring(0, cut)
                val id = line.substring(cut + 1).trim().toIntOrNull() ?: return@forEach
                val c = when {
                    symbol.length == 1 -> symbol[0]
                    symbol == "<space>" || symbol == "\\s" -> ' '
                    else -> return@forEach // multi-char symbols are not phonemes here
                }
                map.putIfAbsent(c, id)
            }
            return if (map.size < 32) null else PhonemeVocabulary(map, "tokens.txt")
        }

        /**
         * Reads the `vocab` object out of a Kokoro `config.json`. Parsed by hand
         * rather than with a JSON library because `:core` stays dependency-free,
         * and the shape is fixed: `"vocab": { "$": 0, "a": 43, ... }`.
         */
        fun parseConfigJson(text: String): PhonemeVocabulary? {
            val key = "\"vocab\""
            val at = text.indexOf(key)
            if (at < 0) return null
            val open = text.indexOf('{', at + key.length)
            if (open < 0) return null
            var depth = 0
            var close = -1
            for (i in open until text.length) {
                when (text[i]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) { close = i; break }
                    }
                }
            }
            if (close < 0) return null
            val body = text.substring(open + 1, close)

            val map = LinkedHashMap<Char, Int>()
            var i = 0
            while (i < body.length) {
                val q1 = body.indexOf('"', i)
                if (q1 < 0) break
                val sb = StringBuilder()
                var j = q1 + 1
                var closed = false
                while (j < body.length) {
                    val c = body[j]
                    if (c == '\\' && j + 1 < body.length) {
                        val esc = body[j + 1]
                        sb.append(
                            when (esc) {
                                'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'
                                'u' -> {
                                    val hex = body.substring(j + 2, minOf(j + 6, body.length))
                                    j += 4
                                    hex.toIntOrNull(16)?.toChar() ?: ' '
                                }
                                else -> esc
                            },
                        )
                        j += 2
                        continue
                    }
                    if (c == '"') { closed = true; break }
                    sb.append(c)
                    j++
                }
                if (!closed) break
                val colon = body.indexOf(':', j)
                if (colon < 0) break
                var k = colon + 1
                while (k < body.length && body[k].isWhitespace()) k++
                val start = k
                while (k < body.length && (body[k].isDigit() || body[k] == '-')) k++
                val id = body.substring(start, k).toIntOrNull()
                val symbol = sb.toString()
                if (id != null && symbol.length == 1) map.putIfAbsent(symbol[0], id)
                i = k
            }
            return if (map.size < 32) null else PhonemeVocabulary(map, "config.json")
        }

        /** Picks whichever format [text] actually is. Null when it is neither. */
        fun parse(text: String): PhonemeVocabulary? =
            if (text.trimStart().startsWith("{")) parseConfigJson(text) else parseTokens(text)
    }
}
