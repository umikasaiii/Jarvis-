package com.simone.jarvismobile.core.translate

/** Italian user-facing text for translator states and notices (kept out of the UI so it is tested). */
object TranslatorMessages {
    const val MODE_LIMIT_AUTO =
        "Il riconoscimento vocale offline ascolta una lingua alla volta: in Automatico JARVIS ascolta nella " +
            "lingua attiva e passa all'altra quando riconosce che l'interlocutore ha cambiato lingua. " +
            "Non è un riconoscimento simultaneo di due lingue: la prima frase dell'altro può andare persa. " +
            "Per risultati affidabili usa Push-to-talk."

    fun notice(n: TranslatorNotice): String = when (n) {
        TranslatorNotice.Busy -> "Sto ancora elaborando: attendi un momento."
        is TranslatorNotice.NoVoice -> "Nessuna voce offline per ${n.language.display}: leggi la traduzione sullo schermo. " +
            "Puoi installare la voce dalle impostazioni del sintetizzatore vocale."
        TranslatorNotice.SpeakFailed -> "Non sono riuscito a leggere la traduzione ad alta voce: la trovi scritta."
        is TranslatorNotice.TranslateFailed -> "Traduzione non riuscita (${n.code}). Riprova."
        is TranslatorNotice.ModelMissing -> modelMissing(n.languages)
        is TranslatorNotice.RecognizerLanguageMissing -> "Il riconoscimento vocale offline per ${n.language.display} non è installato. " +
            "Installa il pacchetto lingua del riconoscimento vocale (Impostazioni di sistema › Lingua › Input vocale) " +
            "oppure usa la traduzione di testo."
        TranslatorNotice.RecognizerUnavailable -> "Il riconoscimento vocale sul dispositivo non è disponibile (controlla il permesso del microfono)."
    }

    fun modelMissing(langs: List<TranslationLanguage>): String =
        "Modello offline mancante: ${langs.joinToString(", ") { it.display }}. Scaricalo una volta con Internet " +
            "(poi funziona anche in modalità aereo)."

    fun state(s: LiveTranslationState, listening: TranslationLanguage?): String = when (s) {
        LiveTranslationState.Idle -> "Pronto ad avviare"
        LiveTranslationState.CheckingModels -> "Controllo modelli…"
        is LiveTranslationState.DownloadingModels -> "Scarico ${s.language?.display ?: "modelli"}…"
        LiveTranslationState.Ready -> "In attesa"
        LiveTranslationState.Listening -> "In ascolto${listening?.let { " (${it.display})" } ?: ""}…"
        LiveTranslationState.Transcribing -> "Trascrizione…"
        LiveTranslationState.Translating -> "Traduco…"
        LiveTranslationState.Speaking -> "Riproduco…"
        LiveTranslationState.Paused -> "In pausa"
        LiveTranslationState.Stopping -> "Arresto…"
        is LiveTranslationState.Error -> error(s.reason)
    }

    fun error(reason: String): String {
        val code = reason.substringBefore(':')
        val arg = reason.substringAfter(':', "")
        val lang = TranslationLanguage.fromCode(arg)
        return when (code) {
            "model_missing" -> "Modello offline mancante" + (lang?.let { " (${it.display})" } ?: "") +
                ": scaricalo una volta con Internet, poi funziona anche in modalità aereo."
            "stt_language" -> TranslatorNotice.RecognizerLanguageMissing(lang ?: TranslationLanguage.ITALIAN).let { notice(it) }
            "stt_unavailable" -> notice(TranslatorNotice.RecognizerUnavailable)
            "stt_failed" -> "Il riconoscimento vocale continua a fallire. Fermalo e riprova."
            else -> "Errore: $reason"
        }
    }
}
