package com.simone.jarvismobile.core.tts

/**
 * Writes numbers out in Italian words.
 *
 * A neural TTS is fed phonemes, not characters, so "68" reaches it as nothing at
 * all unless something spells it first. The Android engine did this internally;
 * once the audio comes from an ONNX graph it has to happen here.
 *
 * Italian elides the final vowel before "uno" and "otto" (ventuno, ventotto,
 * trentuno) and writes everything as one word up to the millions, which is why
 * this is a small transducer rather than a lookup.
 */
object ItalianNumbers {

    private val UNITS = arrayOf(
        "zero", "uno", "due", "tre", "quattro", "cinque", "sei", "sette", "otto", "nove",
        "dieci", "undici", "dodici", "tredici", "quattordici", "quindici", "sedici",
        "diciassette", "diciotto", "diciannove",
    )

    private val TENS = arrayOf(
        "", "", "venti", "trenta", "quaranta", "cinquanta",
        "sessanta", "settanta", "ottanta", "novanta",
    )

    /** Spells [n]; falls back to digit-by-digit for anything out of range. */
    fun spell(n: Long): String = when {
        n < 0 -> "meno ${spell(-n)}"
        n < 20 -> UNITS[n.toInt()]
        n < 100 -> tens(n.toInt())
        n < 1_000 -> hundreds(n.toInt())
        n < 1_000_000 -> thousands(n)
        n < 1_000_000_000 -> millions(n)
        else -> n.toString().map { spell((it - '0').toLong()) }.joinToString(" ")
    }

    private fun tens(n: Int): String {
        val t = n / 10
        val u = n % 10
        val base = TENS[t]
        // venti + uno -> ventuno; trenta + otto -> trentotto.
        return if (u == 1 || u == 8) base.dropLast(1) + UNITS[u] else base + if (u == 0) "" else UNITS[u]
    }

    private fun hundreds(n: Int): String {
        val h = n / 100
        val rest = n % 100
        val prefix = if (h == 1) "cento" else UNITS[h] + "cento"
        if (rest == 0) return prefix
        val tail = if (rest < 20) UNITS[rest] else tens(rest)
        // centoottanta -> centottanta; the vowel collides and Italian drops it.
        return if (tail.startsWith("o")) prefix.dropLast(1) + tail else prefix + tail
    }

    private fun thousands(n: Long): String {
        val k = (n / 1_000).toInt()
        val rest = (n % 1_000).toInt()
        val prefix = if (k == 1) "mille" else spell(k.toLong()) + "mila"
        return if (rest == 0) prefix else prefix + spell(rest.toLong())
    }

    private fun millions(n: Long): String {
        val m = n / 1_000_000
        val rest = n % 1_000_000
        val prefix = if (m == 1L) "un milione" else spell(m) + " milioni"
        return if (rest == 0L) prefix else "$prefix ${spell(rest)}"
    }

    private val NUMBER = Regex("""\d[\d.]*""")

    /**
     * Replaces every run of digits in [text] with its spelled form. Thousands
     * separators are stripped first so "1.500" is one number and not two.
     */
    fun spellAll(text: String): String = NUMBER.replace(text) { m ->
        val digits = m.value.replace(".", "")
        val value = digits.toLongOrNull() ?: return@replace m.value
        // A leading zero is part of a code (an hour, a house number), not a
        // quantity: "07" is "zero sette", never "sette".
        if (digits.length > 1 && digits[0] == '0') {
            digits.map { spell((it - '0').toLong()) }.joinToString(" ")
        } else {
            spell(value)
        }
    }
}
