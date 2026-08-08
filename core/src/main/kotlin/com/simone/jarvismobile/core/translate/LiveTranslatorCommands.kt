package com.simone.jarvismobile.core.translate

/** A voice/chat command aimed at Live Translator. */
sealed interface LiveTranslatorCommand {
    /**
     * Start (or reconfigure) live translation. Languages are null when the user
     * didn't name them — the app then reuses the last pair or opens the picker.
     */
    data class Start(
        val source: TranslationLanguage? = null,
        val target: TranslationLanguage? = null,
    ) : LiveTranslatorCommand

    data object Stop : LiveTranslatorCommand
    data object Swap : LiveTranslatorCommand
}

/**
 * Parses the handful of Italian phrases that drive Live Translator, extracting a
 * language pair when the user names one ("avvia traduzione live italiano
 * giapponese"). Deliberately narrow so it never fires on ordinary chat: a start
 * needs an explicit translator keyword.
 */
object LiveTranslatorCommands {

    fun parse(raw: String): LiveTranslatorCommand? {
        val t = normalize(raw)
        if (t.isBlank()) return null

        if (STOP_RE.containsMatchIn(t)) return LiveTranslatorCommand.Stop
        if (SWAP_RE.containsMatchIn(t)) return LiveTranslatorCommand.Swap
        if (!START_RE.containsMatchIn(t)) return null

        val langs = languagesIn(t)
        return LiveTranslatorCommand.Start(
            source = langs.getOrNull(0),
            target = langs.getOrNull(1),
        )
    }

    /** Languages named in the text, in spoken order, de-duplicated consecutively. */
    fun languagesIn(text: String): List<TranslationLanguage> {
        val found = ArrayList<TranslationLanguage>()
        for (token in normalize(text).split(WS)) {
            TranslationLanguage.fromName(token)?.let { lang ->
                if (found.lastOrNull() != lang) found += lang
            }
        }
        return found
    }

    private fun normalize(s: String): String = s.lowercase()
        .replace('à', 'a').replace('è', 'e').replace('é', 'e')
        .replace('ì', 'i').replace('ò', 'o').replace('ù', 'u')
        .trim().trim('.', '!', '?', ',')

    private val WS = Regex("\\s+")

    // Fires only on an explicit translator phrase, never on ordinary chat.
    private val START_RE = Regex(
        "\\btraduttore\\b" +
            "|\\btraduzione\\s+live\\b" +
            "|\\binterprete\\b" +
            "|\\b(?:avvia|apri|attiva|inizia|parti|fai|start)\\b.{0,25}\\b(?:traduzione|traduci|tradurre)\\b" +
            "|\\btraduci\\s+in\\s+tempo\\s+reale\\b" +
            "|\\btraduzione\\s+(?:italiano|inglese|spagnolo|francese|giapponese)\\b",
    )

    private val STOP_RE = Regex(
        "\\b(?:termina|ferma|chiudi|arresta|stop|basta)\\b.{0,20}" +
            "\\b(?:traduzione|traduttore|interprete)\\b" +
            "|\\bstop\\s+traduzione\\b",
    )

    private val SWAP_RE = Regex(
        "\\b(?:scambia|inverti|cambia)\\b.{0,12}\\blingue?\\b" +
            "|\\binverti\\s+le\\s+lingue\\b",
    )
}
