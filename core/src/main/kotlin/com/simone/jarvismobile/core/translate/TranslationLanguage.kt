package com.simone.jarvismobile.core.translate

/**
 * The languages Live Translator supports. Kept small and closed on purpose: the
 * language identifier is restricted to exactly these five so it can't drift to a
 * near neighbour, and the codes are the BCP-47 tags ML Kit's translate and
 * language-id APIs use.
 */
enum class TranslationLanguage(val code: String, val display: String) {
    ITALIAN("it", "Italiano"),
    ENGLISH("en", "English"),
    SPANISH("es", "Español"),
    FRENCH("fr", "Français"),
    JAPANESE("ja", "日本語");

    /** The upper-case heading shown above a segment (Japanese has no case). */
    val heading: String get() = if (this == JAPANESE) display else display.uppercase()

    companion object {
        val CODES: List<String> = entries.map { it.code }

        /** A BCP-47 code (possibly region-tagged, e.g. "en-US") to a language. */
        fun fromCode(code: String?): TranslationLanguage? {
            val c = code?.trim()?.lowercase()?.substringBefore('-') ?: return null
            return entries.firstOrNull { it.code == c }
        }

        /**
         * A spoken/typed language name in Italian or English (or the endonym) to a
         * language: "giapponese", "japanese", "日本語" all map to JAPANESE.
         */
        fun fromName(raw: String): TranslationLanguage? {
            val n = raw.trim().lowercase()
                .replace('à', 'a').replace('è', 'e').replace('é', 'e')
                .replace('ì', 'i').replace('ò', 'o').replace('ù', 'u')
            return NAMES.entries.firstOrNull { (aliases, _) -> n in aliases }?.value
        }

        private val NAMES: Map<Set<String>, TranslationLanguage> = mapOf(
            setOf("italiano", "italian", "it") to ITALIAN,
            setOf("inglese", "english", "en", "americano") to ENGLISH,
            setOf("spagnolo", "spagnola", "espanol", "spanish", "castigliano", "es") to SPANISH,
            setOf("francese", "french", "francais", "fr") to FRENCH,
            setOf("giapponese", "japanese", "nihongo", "日本語", "ja", "jp") to JAPANESE,
        )
    }
}
