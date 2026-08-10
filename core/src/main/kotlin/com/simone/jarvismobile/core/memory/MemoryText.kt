package com.simone.jarvismobile.core.memory

/**
 * Light Italian morphological normalisation for retrieval. Not a full stemmer and
 * not a neural embedder — a transparent, deterministic middle ground that lets a
 * query token and a note token meet even when only their surface form differs:
 *
 *  - inflection: "gatti"/"gatta" → "gatt", "preferisco"/"preferisci" → "preferisc";
 *  - a small hand-picked synonym map so different words for the same everyday
 *    concept collapse together ("macchina"/"automobile" → the "auto" concept,
 *    "allergico"/"allergie" → the "allerg" concept).
 *
 * It is deliberately conservative: over-stemming would make unrelated notes
 * collide, which is worse for a personal memory than missing a rare variant. A
 * real on-device embedder can later replace this behind the same ranker.
 */
object MemoryText {

    /** Fold [token] onto a shared concept if known, otherwise stem it. */
    fun normalize(token: String): String {
        val lower = token.lowercase()
        concept(lower)?.let { return it }
        return stem(lower)
    }

    /** Strip a clear derivational suffix and/or a trailing gender/number vowel. */
    fun stem(word: String): String {
        var s = word
        for (suffix in SUFFIXES) {
            if (s.endsWith(suffix) && s.length - suffix.length >= MIN_STEM) {
                s = s.dropLast(suffix.length)
                break
            }
        }
        if (s.length >= MIN_STEM + 1 && s.last() in VOWELS) s = s.dropLast(1)
        return s
    }

    /**
     * A synonym match, tested on the *raw* word so the prefixes stay readable.
     * Each canonical is the stem the head word would produce, so the head word
     * needs no entry ("casa" stems to "cas"; only its synonyms map onto "cas").
     */
    private fun concept(word: String): String? {
        for ((prefix, canonical) in CONCEPT_PREFIXES) {
            if (word.startsWith(prefix)) return canonical
        }
        return null
    }

    private const val MIN_STEM = 3
    private const val VOWELS = "aeio" // final unaccented gender/number vowel

    // Longest-first so "zioni" wins over a bare final vowel.
    private val SUFFIXES = listOf(
        "issimo", "issima", "amento", "imento", "zione", "zioni", "mente",
    )

    // Ordered longest-first so a shorter prefix never shadows a longer one.
    private val CONCEPT_PREFIXES: List<Pair<String, String>> = listOf(
        "autovettur" to "aut",
        "automobil" to "aut",
        "macchin" to "aut",
        "vettur" to "aut",
        "abitazion" to "cas",
        "appartament" to "cas",
        "domicili" to "cas",
        "profession" to "lavor",
        "mestier" to "lavor",
        "impieg" to "lavor",
        "dottoress" to "medic",
        "dottor" to "medic",
        "allergic" to "allerg",
        "allergi" to "allerg",
        "compagn" to "coniug",
        "coniug" to "coniug",
        "mogli" to "coniug",
        "marit" to "coniug",
        "bibit" to "bevand",
        "bevand" to "bevand",
    )
}
