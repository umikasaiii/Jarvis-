package com.simone.jarvismobile.tools

import com.simone.jarvismobile.llm.ClassifierEngineProvider
import com.simone.jarvismobile.llm.LlmEngine
import com.simone.jarvismobile.llm.LlmLoadState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * BETA 8 hotfix 2 — knowledge questions and plain statements were answered with «Quale numero devo preparare nel
 * dialer?». Reproduced on the real [CommandMatcher] (not the classifier): `chiama` inside «si chiama» and `telefon…`
 * inside «telefono» triggered the call matcher. These tests use the real matcher/classifier code with a FAKE language
 * model: they prove routing, not what Gemma/LiteRT-LM would answer.
 */
class FalseIntentRegressionTest {
    private val ramSsd1 = "Quale dei due conserva i dati anche quando spengo il telefono?"
    private val ramSsd0 = "Qual è la differenza tra RAM e SSD?"
    private val nebulix = "Ricorda che il mio animale immaginario si chiama Nebulix. Come si chiama?"
    private val recall = "Come si chiama il mio animale immaginario?"

    private fun route(m: Match?): String = when (m) {
        null -> "null"
        is Match.Ask -> "ask:${m.tool}/${m.missing}"
        is Match.Run -> "run:${m.call.name}"
    }

    private fun assertNoPhoneAction(text: String) {
        val r = route(CommandMatcher.match(text))
        assertFalse("$text -> $r", r.contains("prepare_call") || r.contains("compose_sms"))
    }

    @Test fun theReportedQuestionsDoNotBecomePhoneCommands() {
        listOf(ramSsd0, ramSsd1, nebulix, recall).forEach(::assertNoPhoneAction)
        assertNull(CommandMatcher.match(ramSsd1))
        assertNull(CommandMatcher.match(ramSsd0))
        assertNull(CommandMatcher.match(recall))
    }

    @Test fun theWholeReportedSequenceNeverProducesASpuriousPhoneAnswer() {
        // RAM/SSD in two consecutive turns, the immediate-recall statement, the recall question, a greeting.
        listOf(ramSsd0, ramSsd1, nebulix, recall, "Ciao Jarvis, presentati brevemente in italiano", "Come stai oggi?")
            .forEach(::assertNoPhoneAction)
    }

    @Test fun namesAndPhoneWordsInsideOrdinarySentencesAreNotCommands() {
        listOf(
            "Come si chiama la capitale della Francia?",
            "Quanta RAM ha il telefono?",
            "Il mio telefono è lento, che faccio?",
            "Mi telefona mia madre ogni giorno",
            "Scrivi un messaggio di auguri per mia madre",
            "Ho ricevuto un sms ieri, cos'è?",
            "Ti telefono domani",             // «mi/ti» before the verb makes it a statement about someone phoning,
            "Mi chiama sempre mio padre",     // not an imperative addressed to JARVIS
        ).forEach(::assertNoPhoneAction)
    }

    @Test fun aRealCallRequestStillOnlyPreparesTheDialer() {
        val run = CommandMatcher.match("Chiama il numero 3331234567") as Match.Run
        assertEquals("prepare_call", run.call.name)
        assertEquals("3331234567", run.call.arguments["number"]?.jsonPrimitive?.content)
        val polite = CommandMatcher.match("Potresti telefonare al +39 333 123 4567?") as Match.Run
        assertEquals("+393331234567", polite.call.arguments["number"]?.jsonPrimitive?.content)
        assertEquals("ask:prepare_call/number", route(CommandMatcher.match("Mi puoi chiamare Marco")))
        val ask = CommandMatcher.match("Chiama Marco") as Match.Ask
        assertEquals("prepare_call", ask.tool); assertEquals("number", ask.missing)
        val sms = CommandMatcher.match("Prepara un SMS al +39 333 123 4567: arrivo tra poco") as Match.Run
        assertEquals("compose_sms", sms.call.name)
    }

    @Test fun theDialerToolNeverStartsACallItself() {
        val src = sourceOf("tools/AndroidSystemTools.kt")
        val dial = src.substringAfter("class DialDraftTool").substringBefore("class SmsDraftTool")
        assertTrue(dial.contains("Intent.ACTION_DIAL"))
        assertFalse(dial.contains("ACTION_CALL"))
        assertTrue(dial.contains("ToolPolicy.EXTERNAL_COMMUNICATION")) // confirmation before even the dialer opens
        assertTrue(dial.contains("confirmationPrompt"))
    }

    // --- the classifier: a name and a score are not authority --------------------------------------------------

    private val sensitive = listOf(
        "prepare_call", "compose_sms", "navigate", "create_calendar_event", "forget_memory",
        "update_memory", "open_app", "play_media", "search_vault", "list_notifications",
    )

    @Test fun aHighConfidenceSensitiveIntentWithoutEvidenceInThisTurnIsDropped() = runTest {
        for (intent in sensitive) for (utterance in listOf(ramSsd1, nebulix, "Ciao Jarvis, presentati brevemente in italiano")) {
            val u = LlmIntentClassifier(ClassifierEngineProvider { FakeLlm("$intent|99") }).understand(utterance)
            assertNull("$intent on «$utterance» produced ${route(u.match)}", u.match)
            assertFalse("$intent on «$utterance» may execute", u.mayExecute)
        }
    }

    @Test fun aSensitiveIntentWithNoReportedConfidenceIsStillDropped() = runTest {
        // the 0.72 default (model gave a name but no number) used to be enough to open a phone question
        for (intent in sensitive) {
            val u = LlmIntentClassifier(ClassifierEngineProvider { FakeLlm(intent) }).understand(ramSsd1)
            assertNull(intent, u.match); assertFalse(intent, u.mayExecute)
        }
    }

    @Test fun aModelProposedCallNeedsANumberAndCallVocabularyInTheCurrentTurn() = runTest {
        val ok = LlmIntentClassifier(ClassifierEngineProvider { FakeLlm("prepare_call|90") })
            .understand("Fammi una telefonata al 3331234567")
        assertEquals("prepare_call", (ok.match as Match.Run).call.name)
        assertEquals("3331234567", (ok.match as Match.Run).call.arguments["number"]?.jsonPrimitive?.content)
        // the number comes from the user; a model-invented one is impossible, and a number alone is no call request
        assertNull(LlmIntentClassifier(ClassifierEngineProvider { FakeLlm("prepare_call|90") })
            .understand("Il mio numero è 3331234567").match)
        assertNull(LlmIntentClassifier(ClassifierEngineProvider { FakeLlm("prepare_call|90") })
            .understand("Fammi una telefonata a Marco").match) // no number: no invented question, the model answers
    }

    @Test fun aRealCallThroughTheClassifierStillWorks() = runTest {
        val u = LlmIntentClassifier(ClassifierEngineProvider { FakeLlm("prepare_call|97") })
            .understand("Potresti telefonare al +39 333 123 4567?")
        assertTrue(u.mayExecute)
        assertEquals("+393331234567", (u.match as Match.Run).call.arguments["number"]?.jsonPrimitive?.content)
    }

    @Test fun freeConversationFollowsAModelRequestedAction() = runTest {
        // a timer question, then ordinary chat: the second turn is not captured by anything pending in the classifier
        val timer = LlmIntentClassifier(ClassifierEngineProvider { FakeLlm("set_timer|90") }).understand("Metti un timer")
        assertTrue(timer.match is Match.Ask)
        val chat = LlmIntentClassifier(ClassifierEngineProvider { FakeLlm("none|95") }).understand(ramSsd1)
        assertNull(chat.match); assertFalse(chat.mayExecute)
    }

    @Test fun noSensitiveBranchInventsAQuestionFromTheModelNameAlone() {
        val src = sourceOf("tools/LlmIntentClassifier.kt")
        listOf("Quale numero devo preparare", "A quale numero preparo", "Verso quale destinazione", "Quale app supportata",
            "Cosa devo cercare nel vault", "Quale appunto devo eliminare").forEach { assertFalse(it, src.contains(it)) }
        assertTrue(src.contains("modelProposedDial") && src.contains("modelProposedSms"))
    }

    // --- pending question state -------------------------------------------------------------------------------

    @Test fun aPendingQuestionCannotTrapTheNextIndependentMessage() {
        val c = sourceOf("audio/SessionCoordinator.kt")
        assertTrue(c.contains("SlotAnswerPolicy.assess("))
        assertTrue(c.contains("SlotAnswerPolicy.isRepeatOfSameQuestion("))
        assertTrue(c.contains("val askedAtMs: Long = System.currentTimeMillis()"))
    }

    @Test fun newConversationClearsEveryPendingQuestion() {
        val c = sourceOf("audio/SessionCoordinator.kt")
        val reset = c.substringAfter("fun newConversation()").substringBefore("offeredFacts.clear()")
        listOf("pendingSlot = null", "lastAskedSlot = null", "pendingConfirmation = null", "pendingPick = null").forEach {
            assertTrue(it, reset.contains(it))
        }
    }

    @Test fun everyTurnLeavesARoutingTraceWithoutUserText() {
        val c = sourceOf("audio/SessionCoordinator.kt")
        listOf("route=deterministic", "route=classifier", "route=pending", "route=conversation", "route=pending-dropped").forEach {
            assertTrue(it, c.contains(it))
        }
        assertFalse(Regex("""Log\.i\(TAG, "route[^"]*\$\{?transcript""").containsMatchIn(c))
    }

    private fun sourceOf(rel: String): String {
        val roots = listOf(File("src/main/java/com/simone/jarvismobile"), File("app/src/main/java/com/simone/jarvismobile"))
        return File(roots.first { it.isDirectory }, rel).readText()
    }

    private class FakeLlm(private val answer: String) : LlmEngine {
        override val loadState: StateFlow<LlmLoadState> = MutableStateFlow(LlmLoadState.LOADED)
        override val loadedModelName: StateFlow<String?> = MutableStateFlow("fake")
        override val lastLoadDetail: StateFlow<String> = MutableStateFlow("")
        override val generating: StateFlow<Boolean> = MutableStateFlow(false)
        override suspend fun load(modelPath: String, modelName: String) = true
        override fun unload() = Unit
        override suspend fun generate(prompt: String, timeoutSeconds: Long): String = answer
        override suspend fun chat(userText: String, systemPrompt: String, timeoutSeconds: Long): String = answer
        override suspend fun chatStateless(userText: String, systemPrompt: String, timeoutSeconds: Long): String = answer
        override fun resetConversation() = Unit
        override fun cancel() = Unit
    }
}
