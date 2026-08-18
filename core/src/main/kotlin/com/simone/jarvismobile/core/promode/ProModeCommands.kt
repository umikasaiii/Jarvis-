package com.simone.jarvismobile.core.promode

import com.simone.jarvismobile.core.intent.TextNormalizer

/** The two system-level commands that must reach the user regardless of Pro Mode's own state. */
enum class ProModeCommand { ACTIVATE, DEACTIVATE }

/**
 * Recognises the handful of fixed phrases that turn Modalità Pro on/off.
 *
 * Deliberately NOT part of [com.simone.jarvismobile.core.intent.IntentAliases]:
 * those tables feed the deterministic command matcher, which Pro Mode's whole
 * point is to bypass. This check must run unconditionally, in both NORMAL and
 * PRO, before any routing decision — it is a system command, not a matched
 * intent among many.
 *
 * Whole-utterance matching (like [com.simone.jarvismobile.core.intent.IntentAliases.isListeningCancelWord]),
 * not a substring search: "attiva la modalità pro tra un minuto" is a sentence
 * about the future, not a command to act on right now, and must fall through
 * to the model instead of silently doing something the user didn't ask for yet.
 */
object ProModeCommands {

    fun parse(text: String): ProModeCommand? {
        val t = TextNormalizer.normalize(text).trim('.', '!', ' ')
        if (t.isBlank()) return null
        if (ACTIVATE.any { t == it }) return ProModeCommand.ACTIVATE
        if (DEACTIVATE.any { t == it }) return ProModeCommand.DEACTIVATE
        return null
    }

    private val ACTIVATE = setOf(
        "attiva modalita pro",
        "attiva la modalita pro",
        "entra in modalita pro",
        "vai in modalita pro",
        "modalita pro attiva",
        "accendi la modalita pro",
    )

    private val DEACTIVATE = setOf(
        "disattiva modalita pro",
        "disattiva la modalita pro",
        "esci dalla modalita pro",
        "esci da modalita pro",
        "spegni la modalita pro",
        "torna alla modalita normale",
        "modalita normale",
    )
}
