package com.simone.jarvismobile.tools

/**
 * Parses Italian number words so spoken commands work as naturally as typed
 * ones ("timer di dieci minuti", "sveglia alle sette e mezza"). Speech
 * recognition often returns words rather than digits, so this is what makes
 * voice commands feel flexible.
 */
object ItalianNumbers {

    private val UNITS = mapOf(
        "zero" to 0, "un" to 1, "uno" to 1, "una" to 1, "due" to 2, "tre" to 3,
        "quattro" to 4, "cinque" to 5, "sei" to 6, "sette" to 7, "otto" to 8,
        "nove" to 9, "dieci" to 10, "undici" to 11, "dodici" to 12,
        "tredici" to 13, "quattordici" to 14, "quindici" to 15, "sedici" to 16,
        "diciassette" to 17, "diciotto" to 18, "diciannove" to 19,
    )

    private val TENS = mapOf(
        "venti" to 20, "vent" to 20, "trenta" to 30, "trent" to 30,
        "quaranta" to 40, "quarant" to 40, "cinquanta" to 50, "cinquant" to 50,
        "sessanta" to 60, "sessant" to 60,
    )

    /** First number found in [text], as digits or Italian words. Null if none. */
    fun firstNumber(text: String): Int? {
        val t = text.lowercase()
        Regex("""\d{1,4}""").find(t)?.let { return it.value.toIntOrNull() }
        return firstWordNumber(t)
    }

    /** All numbers in [text], in order, digits or words (used for "7 e 30"). */
    fun allNumbers(text: String): List<Int> {
        val t = text.lowercase()
        val digits = Regex("""\d{1,4}""").findAll(t).mapNotNull { it.value.toIntOrNull() }.toList()
        if (digits.isNotEmpty()) return digits
        val out = mutableListOf<Int>()
        for (token in t.split(Regex("""[^a-zà-ù]+"""))) {
            wordToNumber(token)?.let { out += it }
        }
        // "sette e mezza" -> 7, 30
        if (out.size == 1 && (t.contains("mezza") || t.contains("mezzo"))) out += 30
        if (out.size == 1 && t.contains("un quarto")) out += 15
        return out
    }

    private fun firstWordNumber(t: String): Int? {
        for (token in t.split(Regex("""[^a-zà-ù]+"""))) {
            wordToNumber(token)?.let { return it }
        }
        return null
    }

    /** "venticinque" -> 25, "trenta" -> 30, "dieci" -> 10. */
    private fun wordToNumber(word: String): Int? {
        if (word.isBlank()) return null
        UNITS[word]?.let { return it }
        TENS[word]?.let { return it }
        // Compound tens: ventidue, trentacinque, quarantotto…
        for ((prefix, tens) in TENS) {
            if (word.startsWith(prefix) && word.length > prefix.length) {
                val rest = word.removePrefix(prefix)
                UNITS[rest]?.let { unit -> if (unit in 1..9) return tens + unit }
                // ventuno / trentotto (elided vowel)
                UNITS["u$rest"]?.let { unit -> if (unit in 1..9) return tens + unit }
                if (rest == "uno") return tens + 1
                if (rest == "otto") return tens + 8
            }
        }
        return null
    }

    /**
     * Reads a duration like "dieci minuti", "1 ora e mezza", "30 secondi".
     * Returns seconds, or null when no duration is present.
     *
     * A time unit is REQUIRED unless [allowBareNumber] is set. That matters
     * because "imposta un timer" must not be read as "1 minute" — "un" there is
     * the article, not a quantity. Bare numbers are only accepted when the user
     * is answering "Per quanto tempo?", where a number can only be a duration.
     */
    fun duration(text: String, allowBareNumber: Boolean = false): Int? {
        val t = text.lowercase()
        val unit = Regex("""\b(ore|ora|minuti|minuto|min|secondi|secondo|sec)\b""")
            .find(t)?.groupValues?.get(1)

        if (unit == null) {
            if (!allowBareNumber) return null
            // Answering "per quanto tempo?" with a bare number → minutes.
            val bare = firstNumber(t) ?: return null
            return (bare * 60).takeIf { it in 1..86_400 }
        }

        // With an explicit unit, an unstated quantity means one ("un'ora", "tra un minuto").
        val value = firstNumber(t) ?: 1
        val seconds = when {
            unit.startsWith("or") -> value * 3600
            unit.startsWith("min") -> value * 60
            else -> value
        }
        return seconds.takeIf { it in 1..86_400 }
    }

    /** Reads a clock time like "7:30", "sette e mezza", "alle 8". Returns hour to minute. */
    fun clockTime(text: String): Pair<Int, Int>? {
        val t = text.lowercase()
        Regex("""\b(\d{1,2})[:.](\d{2})\b""").find(t)?.let { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            if (h in 0..23 && min in 0..59) return h to min
        }
        val nums = allNumbers(t)
        val h = nums.getOrNull(0) ?: return null
        var min = nums.getOrNull(1) ?: 0
        if (t.contains("mezza") || t.contains("mezzo")) min = 30
        if (t.contains("un quarto")) min = 15
        // "di sera"/"pomeriggio" -> afternoon hour
        var hour = h
        if (hour in 1..11 && (t.contains("sera") || t.contains("pomeriggio") || t.contains("pm"))) {
            hour += 12
        }
        return if (hour in 0..23 && min in 0..59) hour to min else null
    }
}
