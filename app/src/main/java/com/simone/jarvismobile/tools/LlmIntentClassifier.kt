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
 * Second-stage intent understanding: when [CommandMatcher]'s explicit patterns
 * don't fire, the local model is asked to classify the utterance into one of the
 * registered tools.
 *
 * Two deliberate design choices make this workable on a small on-device model:
 *  - the model must answer with ONE short line (`set_timer 600`), not JSON —
 *    strict JSON is where small models fail;
 *  - the answer is parsed and re-validated here, and can only ever name a tool
 *    that already exists in the registry. The model still cannot invent
 *    capabilities or arguments that bypass validation (docs/SECURITY.md §15).
 *
 * It runs on a stateless [LlmEngine.generate] call, so classification never
 * pollutes the multi-turn conversation, and only for utterances that look like
 * an action — ordinary chat pays no latency cost.
 */
@Singleton
class LlmIntentClassifier @Inject constructor(
    private val llm: LlmEngine,
) {

    /** Returns a tool call the model recognised, or null to fall through to chat. */
    suspend fun classify(utterance: String): ToolCall? {
        if (llm.loadState.value != LlmLoadState.LOADED) return null
        if (!looksLikeAction(utterance)) return null

        val reply = runCatching { llm.generate(prompt(utterance)) }.getOrNull()
            ?.trim()?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
            ?: return null

        return parse(reply).also {
            Log.i(TAG, "intent_classified=${it?.name ?: "none"}")
        }
    }

    /** Cheap pre-filter: does the sentence contain any action-ish word at all? */
    private fun looksLikeAction(text: String): Boolean {
        val t = text.lowercase()
        return ACTION_HINTS.any { t.contains(it) }
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
        calculate <espressione aritmetica>
        none

        Usa "none" se non è una richiesta di azione ma solo conversazione.

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
        Richiesta: come stai oggi?
        Risposta: none

        Richiesta: $utterance
        Risposta:
    """.trimIndent()

    /** Strictly parses one line into a validated [ToolCall], or null. */
    private fun parse(line: String): ToolCall? {
        val cleaned = line.removePrefix("Risposta:").trim().trim('`', '"', '.', ' ')
        if (cleaned.isEmpty()) return null
        val name = cleaned.substringBefore(' ').lowercase()
        val rest = cleaned.substringAfter(' ', "").trim()

        return when (name) {
            "get_time", "battery_status" -> call(name)

            "set_timer" -> rest.takeWhile { it.isDigit() }.toIntOrNull()
                ?.takeIf { it in 1..86_400 }
                ?.let { call("set_timer", "seconds" to it.toString()) }

            "set_alarm" -> {
                val nums = Regex("""\d{1,2}""").findAll(rest).map { it.value.toInt() }.toList()
                val h = nums.getOrNull(0)
                val m = nums.getOrNull(1) ?: 0
                if (h != null && h in 0..23 && m in 0..59) {
                    call("set_alarm", "hour" to h.toString(), "minute" to m.toString())
                } else null
            }

            "flashlight" -> when {
                rest.startsWith("on") || rest.startsWith("true") -> call("flashlight", "on" to "true")
                rest.startsWith("off") || rest.startsWith("false") -> call("flashlight", "on" to "false")
                else -> null
            }

            "remember" -> rest.takeIf { it.length >= 3 }?.let { call("remember", "text" to it) }

            "calculate" -> rest.takeIf { r ->
                r.any { it.isDigit() } && r.all { it.isDigit() || it in "+-*/(). \t" }
            }?.let { call("calculate", "expression" to it) }

            else -> null // includes "none" and anything unexpected
        }
    }

    private fun call(name: String, vararg args: Pair<String, String>): ToolCall =
        ToolCall(
            id = UUID.randomUUID().toString(),
            name = name,
            arguments = JsonObject(args.associate { it.first to JsonPrimitive(it.second) }),
            requiresConfirmation = false,
        )

    private companion object {
        const val TAG = "JarvisIntent"

        /**
         * Words that suggest the user wants something DONE. Keeps plain chat off
         * the classifier path (and therefore fast).
         */
        val ACTION_HINTS = listOf(
            "timer", "sveglia", "svegli", "avvis", "ricord", "nota", "annota", "segna",
            "torcia", "flash", "luce", "batteria", "ora", "ore", "orario", "giorno", "data",
            "calcol", "quanto fa", "accend", "spegn", "attiv", "disattiv", "imposta",
            "metti", "avvia", "fai partire", "promemoria", "conta", "minut", "second",
        )
    }
}
