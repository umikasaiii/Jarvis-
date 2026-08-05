package com.simone.jarvismobile.tools

import android.util.Log
import com.simone.jarvismobile.core.protocol.ToolCall
import com.simone.jarvismobile.llm.LlmEngine
import com.simone.jarvismobile.llm.LlmLoadState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Primary intent understanding: the local model reads the request and decides
 * which registered tool (if any) it means, so a command works however it is
 * phrased instead of only in the shapes a regex anticipated.
 *
 * Three design choices make this workable on a small on-device model:
 *  - it answers with ONE short line (`set_timer`), not JSON — strict JSON is
 *    where small models fail;
 *  - only the tool NAME is taken from the model. Every argument is extracted
 *    from the user's own words, because a small model asked to echo "5 * 2"
 *    may write "7 * 2" and silently answer a different question;
 *  - the name is matched against the registry, so the model gains
 *    understanding, never new capabilities (docs/SECURITY.md §15).
 *
 * It runs on a stateless [LlmEngine.generate] call, so classification never
 * pollutes the multi-turn conversation.
 */
@Singleton
class LlmIntentClassifier @Inject constructor(
    private val llm: LlmEngine,
) {

    /**
     * Understands what the user wants, using the local model.
     *
     * The model decides only WHICH action is meant. Every argument is then
     * extracted from the user's own words, never from the model's echo: asked
     * to repeat "5 * 2" a small model may write "7 * 2" and silently answer a
     * different question. Intent from the AI, data from the user.
     *
     * Returns a [Match] (run it, or ask for the missing detail), or null when
     * this isn't a command.
     */
    suspend fun classify(utterance: String): Match? {
        if (llm.loadState.value != LlmLoadState.LOADED) return null
        if (utterance.isBlank()) return null

        val reply = runCatching { llm.generate(prompt(utterance)) }.getOrNull()
            ?.trim()?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
            ?: return null

        val name = reply.removePrefix("Risposta:").trim().trim('`', '"', '.', ' ')
            .substringBefore(' ').lowercase()
        Log.i(TAG, "intent=$name")

        return when (name) {
            "get_time", "battery_status", "list_memories" -> Match.Run(call(name))

            "set_timer" -> ItalianNumbers.duration(utterance)
                ?.let { Match.Run(call("set_timer", "seconds" to it.toString())) }
                ?: Match.Ask("Per quanto tempo?", "set_timer", "seconds")

            "set_alarm" -> ItalianNumbers.clockTime(utterance)
                ?.let { (h, m) -> Match.Run(call("set_alarm", "hour" to h.toString(), "minute" to m.toString())) }
                ?: Match.Ask("A che ora?", "set_alarm", "time")

            "flashlight" -> {
                val off = Regex("""\b(spegn\w*|disattiv\w*)\b""").containsMatchIn(utterance.lowercase())
                Match.Run(call("flashlight", "on" to (!off).toString()))
            }

            "remember" -> {
                val note = CommandMatcher.rememberBody(utterance) ?: utterance.trim()
                if (note.length < 3) {
                    Match.Ask("Cosa vuoi che ricordi?", "remember", "text")
                } else if (CommandMatcher.hasTimeReference(note)) {
                    Match.Run(call("remember", "text" to note))
                } else {
                    Match.Ask("Quando?", "remember", "when", mapOf("text" to note))
                }
            }

            // Digits must come from the user, never from the model.
            "calculate" -> CommandMatcher.arithmetic(utterance)
                ?.let { Match.Run(call("calculate", "expression" to it)) }

            else -> null // "none" and anything unexpected → normal conversation
        }
    }

    private fun prompt(utterance: String): String = """
        Sei un classificatore di comandi. Leggi la richiesta e rispondi con UNA SOLA RIGA,
        scegliendo esattamente uno di questi formati. Non aggiungere spiegazioni.

        get_time
        battery_status
        set_timer <secondi>
        set_alarm <ora> <minuti>
        flashlight on
        flashlight off
        remember <testo da ricordare>
        list_memories
        calculate <espressione aritmetica>
        none

        Usa "none" se non è una richiesta di azione ma solo conversazione, oppure se
        la domanda richiede di ragionare, confrontare o dare un parere sugli appunti
        (in quel caso risponderai tu a parole, non con un comando).
        Usa "list_memories" SOLO per elencare gli appunti così come sono.
        Se manca un dettaglio (durata, orario) scrivi comunque il comando senza numeri:
        verrà chiesto all'utente.

        Esempi:
        Richiesta: che ore fanno adesso?
        Risposta: get_time
        Richiesta: avvisami fra dieci minuti
        Risposta: set_timer 600
        Richiesta: buttami giù una nota, devo comprare il latte
        Risposta: remember devo comprare il latte
        Richiesta: fammi luce
        Risposta: flashlight on
        Richiesta: destami alle sette e mezza
        Risposta: set_alarm 7 30
        Richiesta: cosa devo fare questa settimana?
        Risposta: list_memories
        Richiesta: metti un timer
        Risposta: set_timer
        Richiesta: quale impegno è più urgente secondo te?
        Risposta: none
        Richiesta: come stai oggi?
        Risposta: none

        Richiesta: $utterance
        Risposta:
    """.trimIndent()

    private fun call(name: String, vararg args: Pair<String, String>): ToolCall =
        ToolCall(
            id = UUID.randomUUID().toString(),
            name = name,
            arguments = JsonObject(args.associate { it.first to JsonPrimitive(it.second) }),
            requiresConfirmation = false,
        )

    private companion object {
        const val TAG = "JarvisIntent"
    }
}
