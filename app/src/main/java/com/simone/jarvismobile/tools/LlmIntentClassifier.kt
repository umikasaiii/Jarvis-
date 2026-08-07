package com.simone.jarvismobile.tools

import android.util.Log
import com.simone.jarvismobile.core.protocol.ToolCall
import com.simone.jarvismobile.core.memory.MemoryStructure
import com.simone.jarvismobile.core.routing.ComplexityHeuristic
import com.simone.jarvismobile.llm.LlmEngine
import com.simone.jarvismobile.llm.LlmGenerationTimeoutException
import com.simone.jarvismobile.llm.LlmLoadState
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Auditable result of the fast brain's Understanding V2 pass. */
data class IntentUnderstanding(
    val intent: String,
    val match: Match?,
    val confidence: Double,
    val needsReasoning: Boolean,
) {
    val mayExecute: Boolean get() = match != null && confidence >= MIN_EXECUTION_CONFIDENCE

    companion object {
        const val MIN_EXECUTION_CONFIDENCE = 0.58
    }
}

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
    /** Set by the last [classify] call: does answering need the larger model? */
    @Volatile var lastNeedsReasoning: Boolean = false
        private set

    suspend fun classify(utterance: String, recentContext: String = ""): Match? =
        understand(utterance, recentContext).match

    /**
     * Returns intent, confidence and model-routing information together. The
     * confidence is deliberately only a gate, not authority: tool arguments are
     * still rebuilt from the user's own words and the registry validates them.
     */
    suspend fun understand(utterance: String, recentContext: String = ""): IntentUnderstanding {
        // Reset on every utterance: a failed classifier call must not inherit the
        // previous request's routing decision.
        lastNeedsReasoning = ComplexityHeuristic.needsReasoning(utterance)
        if (llm.loadState.value != LlmLoadState.LOADED || utterance.isBlank()) {
            return IntentUnderstanding("unavailable", null, 0.0, lastNeedsReasoning)
        }

        val generated = try {
            llm.generate(prompt(utterance, recentContext))
        } catch (e: CancellationException) {
            // Never turn a user's Stop into an unreadable classifier result and
            // then accidentally start a second generation for the answer.
            throw e
        } catch (e: LlmGenerationTimeoutException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val reply = generated?.trim()?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
            ?: return IntentUnderstanding("unreadable", null, 0.0, lastNeedsReasoning)

        val clean = reply.removePrefix("Risposta:").trim().trim('`', '"', '.', ' ')
        val name = clean.substringBefore('|').substringBefore(' ').lowercase()
        val reportedConfidence = clean.substringAfter('|', "")
            .trim().removeSuffix("%").toIntOrNull()?.coerceIn(0, 100)?.div(100.0)
        val known = name in KNOWN_INTENTS
        val confidence = when {
            reportedConfidence != null -> reportedConfidence
            known -> DEFAULT_MODEL_CONFIDENCE
            else -> 0.0
        }
        // The fast model also judges whether the answer needs real thinking, so
        // the big model is only woken for questions that deserve it.
        lastNeedsReasoning = lastNeedsReasoning || name == "ragiona" || confidence < LOW_CONFIDENCE
        Log.i(TAG, "intent=$name confidence=${(confidence * 100).toInt()}")

        val match = when (name) {
            "get_time", "list_memories" -> Match.Run(call(name))

            // The charging state is only spoken when the user asked for it.
            "battery_status" -> Match.Run(
                call(
                    "battery_status",
                    "charging_asked" to Regex("""(?i)\b(caric\w*|ricaric\w*|aliment\w*|spina|collegat\w*)\b""")
                        .containsMatchIn(utterance).toString(),
                ),
            )

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

            // A reminder is a calendar entry: the day is parsed out of the user's
            // own words into a real date field, never left inside the description.
            "remember" -> {
                val note = CommandMatcher.rememberBody(utterance) ?: utterance.trim()
                if (note.length < 3) {
                    Match.Ask("Cosa vuoi che ricordi?", "remember", "text")
                } else {
                    Match.Run(
                        call(
                            "remember",
                            "text" to note,
                            "kind" to MemoryStructure.classify(note).name,
                        ),
                    )
                }
            }

            "add_reminder" -> {
                val note = CommandMatcher.rememberBody(utterance) ?: utterance.trim()
                if (note.length < 3) {
                    Match.Ask("Cosa vuoi mettere in agenda?", "add_reminder", "text")
                } else {
                    CommandMatcher.reminderCall(note)
                }
            }

            "search_knowledge" -> Match.Run(call("search_knowledge", "query" to utterance.trim()))

            "list_agenda" -> CommandMatcher.agendaCall(utterance)

            "complete_agenda" -> CommandMatcher.completeAgendaCall(utterance)
                ?: Match.Ask("Quale attività devo segnare come completata?", "complete_agenda", "text")

            "open_app" -> CommandMatcher.appCall(utterance)
                ?: Match.Ask("Quale app supportata devo aprire?", "open_app", "app")

            "open_settings" -> CommandMatcher.settingsCall(utterance)
                ?: Match.Run(call("open_settings", "area" to "generali"))

            "create_calendar_event" -> CommandMatcher.calendarEventCall(utterance)
                ?: Match.Ask("Qual è il titolo dell'evento?", "create_calendar_event", "title")

            "prepare_call" -> CommandMatcher.dialDraftCall(utterance)
                ?: Match.Ask("Quale numero devo preparare nel dialer?", "prepare_call", "number")

            "compose_sms" -> CommandMatcher.smsDraftCall(utterance)
                ?: Match.Ask("A quale numero preparo l'SMS?", "compose_sms", "number")

            "navigate" -> CommandMatcher.navigationCall(utterance)
                ?: Match.Ask("Verso quale destinazione?", "navigate", "destination")

            "play_media" -> CommandMatcher.playMediaCall(utterance)
                ?: Match.Ask("Cosa vuoi ascoltare?", "play_media", "query")

            "media_control" -> CommandMatcher.mediaControlCall(utterance)

            "list_notifications" -> CommandMatcher.notificationCall(utterance)
                ?: Match.Run(call("list_notifications"))

            "search_vault" -> CommandMatcher.searchVaultCall(utterance)
                ?: Match.Ask("Cosa devo cercare nel vault?", "search_vault", "query")

            // Clock arithmetic is done in code, never by the model.
            "time_until" -> CommandMatcher.timeUntilCall(utterance)

            // Digits must come from the user, never from the model.
            "calculate" -> CommandMatcher.arithmetic(utterance)
                ?.let { Match.Run(call("calculate", "expression" to it)) }

            else -> null // "none", "ragiona" and anything unexpected → conversation
        }
        return IntentUnderstanding(name, match, confidence, lastNeedsReasoning)
    }

    private fun prompt(utterance: String, recentContext: String): String = """
        Sei il pianificatore rapido di JARVIS. Considera anche il contesto recente,
        leggi la richiesta e rispondi con UNA SOLA RIGA nel formato:
        intento|fiducia
        dove fiducia è un numero da 0 a 100. Non aggiungere spiegazioni.

        Intenti ammessi: get_time, battery_status, set_timer, set_alarm,
        flashlight, add_reminder, list_agenda, complete_agenda, time_until, remember,
        list_memories, calculate, open_app, open_settings,
        create_calendar_event, prepare_call, compose_sms, navigate,
        play_media, media_control, list_notifications, search_vault,
        ragiona, none.

        Usa "none" per conversazione semplice: saluti, risposte brevi, frasi in cui
        l'utente ti informa di qualcosa.
        Usa "ragiona" quando servono spiegazioni, consigli, confronti, opinioni,
        istruzioni passo-passo o conoscenze del mondo: sono le domande che meritano
        una risposta ponderata.
        Usa "add_reminder" quando l'utente vuole fissare un impegno, un appuntamento
        o un promemoria, anche se non dice il giorno.
        Usa "list_agenda" per sapere cosa c'è in programma (impegni, appuntamenti,
        "cosa devo fare oggi pomeriggio").
        Usa "complete_agenda" per segnare come conclusa un'attività già presente.
        Usa "time_until" per "quanto manca alle …" o "fra quanto".
        Usa "search_knowledge" per domande di conoscenza a cui rispondono guide,
        manuali o voci di enciclopedia importati offline: come si fa una cosa,
        come funziona, cosa significa.
        Usa "list_memories" SOLO per elencare gli appunti liberi, non gli impegni.
        Usa "create_calendar_event" solo se l'utente chiede esplicitamente Google
        Calendar o il calendario del telefono. Il calendario predefinito è quello
        personale e offline di JARVIS, gestito da "add_reminder".
        Usa "prepare_call" e "compose_sms" per preparare una chiamata o un SMS:
        JARVIS non deve mai dichiarare di aver già chiamato o inviato.
        Usa "list_notifications" solo se l'utente chiede esplicitamente di leggere
        notifiche. Usa "search_vault" solo per una ricerca nei file/appunti del vault.
        Se manca un dettaglio (durata, orario) scrivi comunque il comando senza numeri:
        verrà chiesto all'utente.

        Esempi:
        Richiesta: che ore fanno adesso?
        Risposta: get_time|99
        Richiesta: avvisami fra dieci minuti
        Risposta: set_timer|98
        Richiesta: buttami giù una nota, devo comprare il latte
        Risposta: remember|96
        Richiesta: segnami che domani alle tre ho il dentista
        Risposta: add_reminder|97
        Richiesta: venerdì devo cambiare le gomme, ricordamelo
        Risposta: add_reminder|96
        Richiesta: cosa devo fare oggi pomeriggio?
        Risposta: list_agenda|98
        Richiesta: ho appuntamenti domani?
        Risposta: list_agenda|98
        Richiesta: quanto manca alle 16?
        Risposta: time_until|98
        Richiesta: fra quanto è mezzogiorno?
        Risposta: time_until|95
        Richiesta: fammi luce
        Risposta: flashlight|99
        Richiesta: destami alle sette e mezza
        Risposta: set_alarm|98
        Richiesta: cosa devo fare questa settimana?
        Risposta: list_agenda|98
        Richiesta: cosa hai annotato?
        Risposta: list_memories
        Richiesta: come si cambia la gomma della moto?
        Risposta: search_knowledge
        Richiesta: cosa dice il manuale sulla catena?
        Risposta: search_knowledge|95
        Richiesta: apri Spotify
        Risposta: open_app|99
        Richiesta: portami a Piazza Navona
        Risposta: navigate|97
        Richiesta: aggiungi al calendario dentista domani alle 15
        Risposta: add_reminder|98
        Richiesta: esporta su Google Calendar dentista domani alle 15
        Risposta: create_calendar_event|98
        Richiesta: segna comprare il latte come completato
        Risposta: complete_agenda|98
        Richiesta: prepara un SMS al 3331234567 dicendo arrivo tra poco
        Risposta: compose_sms|99
        Richiesta: chiama il 061234567
        Risposta: prepare_call|98
        Richiesta: leggi le notifiche di WhatsApp
        Risposta: list_notifications|98
        Richiesta: cerca nel vault fattura moto
        Risposta: search_vault|98
        Richiesta: metti un timer
        Risposta: set_timer|90
        Richiesta: quale impegno è più urgente secondo te?
        Risposta: ragiona|95
        Richiesta: come cambio la ruota della moto?
        Risposta: ragiona|97
        Richiesta: devo fare la revisione, quanto costa?
        Risposta: ragiona|94
        Richiesta: la batteria si sta caricando
        Risposta: none|98
        Contesto recente: Simone: Quanto ho di batteria? | JARVIS: Batteria al 93 per cento.
        Richiesta: È in carica in questo momento?
        Risposta: battery_status|96
        Richiesta: come stai oggi?
        Risposta: none|96
        Richiesta: sì, va bene
        Risposta: none|92

        Attenzione: se l'utente CONSTATA qualcosa invece di chiedere un'azione,
        rispondi "none|fiducia" anche se la frase contiene parole come batteria o timer.
        Se non sei sicuro dell'intento, usa una fiducia sotto 58: JARVIS non
        eseguirà azioni e userà il modello avanzato per capire meglio.

        Contesto recente: ${recentContext.replace('\n', ' ').takeLast(MAX_CONTEXT_CHARS)}
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
        const val MAX_CONTEXT_CHARS = 900
        const val DEFAULT_MODEL_CONFIDENCE = 0.72
        const val LOW_CONFIDENCE = 0.58
        val KNOWN_INTENTS = setOf(
            "get_time", "battery_status", "set_timer", "set_alarm", "flashlight",
            "add_reminder", "list_agenda", "complete_agenda", "time_until", "remember",
            "list_memories", "search_knowledge", "calculate", "open_app", "open_settings",
            "create_calendar_event", "prepare_call", "compose_sms", "navigate",
            "play_media", "media_control", "list_notifications", "search_vault",
            "ragiona", "none",
        )
    }
}
