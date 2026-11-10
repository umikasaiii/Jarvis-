package com.simone.jarvismobile.regex

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.simone.jarvismobile.audio.SessionCoordinator
import com.simone.jarvismobile.background.AssistantTaskWorker
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BETA 8 hotfix 2 — the REAL chat path (SessionCoordinator: matcher, pending-question state, classifier gate, chat) on an
 * emulator, for the sequence reported on the Honor 200. No language model is loaded in CI, so a plain chat turn ends in
 * the honest "model unavailable" message: this proves ROUTING and pending-state hygiene, NOT that Gemma/LiteRT-LM answers.
 * That is verified on the phone.
 */
@RunWith(AndroidJUnit4::class)
class ConversationRoutingInstrumentedTest {
    private lateinit var coordinator: SessionCoordinator
    private var n = 0

    @Before fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        coordinator = EntryPointAccessors.fromApplication(context.applicationContext, AssistantTaskWorker.WorkerEntryPoint::class.java).coordinator()
        coordinator.newConversation()
    }

    @After fun tearDown() { coordinator.newConversation() }

    private fun say(text: String): String = runBlocking {
        withTimeout(90_000) { coordinator.processQueuedText("beta8-hf2-${System.nanoTime()}-${n++}", text) }
    }.also { assertNotNull(text, it) }!!

    private fun assertNotAPhoneAnswer(reply: String, said: String) {
        listOf("dialer", "preparare nel", "numero devo", "A quale numero", "SMS").forEach {
            assertFalse("«$said» got a phone reply: $reply", reply.contains(it, ignoreCase = true))
        }
        assertFalse(reply, reply.contains("errore tecnico", ignoreCase = true))
    }

    @Test fun theReportedSequenceNeverGetsAPhoneAnswer() {
        listOf(
            "Qual è la differenza tra RAM e SSD?",
            "Quale dei due conserva i dati anche quando spengo il telefono?",
            "Ricorda che il mio animale immaginario si chiama Nebulix.",
            "Come si chiama il mio animale immaginario?",
            "Ciao Jarvis, presentati brevemente in italiano",
        ).forEach { q -> assertNotAPhoneAnswer(say(q), q) }
    }

    @Test fun aRealCallRequestOnlyPreparesTheDialerAfterConfirmation() {
        val reply = say("Chiama il numero 3331234567")
        // the confirmation prompt: nothing is launched until the user confirms, and even then only the dialer opens
        assertTrue(reply, reply.contains("3331234567") && reply.contains("dialer", ignoreCase = true))
        assertTrue(reply, reply.contains("Chiama", ignoreCase = false)) // "…partirà solo se premi Chiama"
    }

    @Test fun aPendingNumberQuestionDoesNotTrapAnIndependentMessage() {
        val ask = say("Chiama Marco")
        assertTrue(ask, ask.contains("numero", ignoreCase = true))
        // an unrelated question instead of a number: the pending question is dropped, no second phone question
        val next = say("Quale dei due conserva i dati anche quando spengo il telefono?")
        assertNotAPhoneAnswer(next, "RAM/SSD while pending")
        // and nothing is pending any more
        assertNotAPhoneAnswer(say("Ciao Jarvis, presentati brevemente in italiano"), "greeting after a dropped question")
    }

    @Test fun aPendingNumberQuestionStillAcceptsARealNumber() {
        say("Chiama Marco")
        val reply = say("3331234567")
        assertTrue(reply, reply.contains("3331234567") && reply.contains("dialer", ignoreCase = true))
    }

    @Test fun newConversationClearsAPendingQuestion() {
        val ask = say("Chiama Marco")
        assertTrue(ask, ask.contains("numero", ignoreCase = true))
        coordinator.newConversation()
        assertNotAPhoneAnswer(say("Ciao Jarvis, presentati brevemente in italiano"), "greeting after Nuova conversazione")
    }

    @Test fun theSameQuestionIsNeverAskedTwiceInARow() {
        say("Chiama Marco")
        // "Marco" is not a number: the question is not repeated, the message continues as a normal request
        val second = say("Marco Rossi")
        assertFalse(second, second.contains("numero devo", ignoreCase = true))
    }
}
