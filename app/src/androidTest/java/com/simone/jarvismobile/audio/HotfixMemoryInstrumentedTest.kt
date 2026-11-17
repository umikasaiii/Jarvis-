package com.simone.jarvismobile.audio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.simone.jarvismobile.background.AssistantTaskWorker
import com.simone.jarvismobile.core.knowledge.Evidence
import com.simone.jarvismobile.core.knowledge.EvidenceStrength
import com.simone.jarvismobile.core.knowledge.KnowledgeChunk
import com.simone.jarvismobile.core.knowledge.ScoredChunk
import com.simone.jarvismobile.core.memory.MemoryKind
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * HOTFIX POST-M4 / H1 — the REAL SessionCoordinator and the real MemoryIndex (production Hilt graph) with ONLY the model call and the
 * knowledge retrieval replaced through [ChatTestSeam]. It reproduces the field report: a fact saved with "Ricorda che…" was answered in the same
 * session but, after the app was reopened, the same question got "no verifiable information" plus unrelated encyclopedia articles, although the
 * note was still listed in the Memory screen. A process restart is simulated by dropping the in-memory index ([MemoryIndex.clear]), which is exactly
 * what a fresh process has. This runs on the CI emulator only: it writes ONE synthetic record and removes only that record; it never touches others.
 * It is NOT a real-model or real-device result.
 */
@RunWith(AndroidJUnit4::class)
class HotfixMemoryInstrumentedTest {
    private lateinit var coordinator: SessionCoordinator
    private val modelCalls = CopyOnWriteArrayList<String>()
    private val savedIds = ArrayList<String>()
    private var n = 0

    private val wikibooks = KnowledgeChunk(
        sourceId = "zim:wikibooks_progetti", sourceTitle = "Progetti universitari", section = "",
        text = "I progetti universitari sono attività di gruppo. Il codice di un corso identifica l'insegnamento.",
        sourceKind = "zim", collection = "Wikibooks (it)", lead = true,
    )
    private val irrelevantZim = Evidence.Grounded(listOf(ScoredChunk(wikibooks, 1.0, wikibooks.text, false)), EvidenceStrength.MODERATE, titleMatched = true)

    @Before fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        coordinator = EntryPointAccessors.fromApplication(context.applicationContext, AssistantTaskWorker.WorkerEntryPoint::class.java).coordinator()
        coordinator.newConversation()
    }

    @After fun tearDown() = runBlocking {
        coordinator.testSeam = null
        savedIds.forEach { runCatching { coordinator.memoryIndex.delete(it) } }   // only the synthetic records this test created
        coordinator.newConversation()
    }

    private fun seam(answer: suspend (String) -> String? = { "Il codice del tuo progetto di prova è Orione." }) {
        modelCalls.clear()
        coordinator.testSeam = ChatTestSeam(chat = { m -> modelCalls += m; answer(m) }, evidence = irrelevantZim)
    }

    private fun save(text: String) = runBlocking { coordinator.memoryIndex.remember(text, MemoryKind.PERMANENT, autoCategorize = false) }
        .also { assertNotNull("the synthetic fact must be saved", it); savedIds += it!!.id }!!

    private fun restart() = coordinator.memoryIndex.clear().also { coordinator.newConversation() }

    private fun say(text: String): String = runBlocking { withTimeout(90_000) { coordinator.processQueuedText("h1-${System.nanoTime()}-${n++}", text) } }
        .also { assertNotNull(text, it) }!!

    private val fact = "Il codice del mio progetto di prova è Orione."

    @Test fun theFactIsRetrievedAfterAProcessRestartAndNoEncyclopediaIsConsulted() {
        save(fact)
        seam()
        assertTrue(say("Qual è il codice del mio progetto di prova?").contains("Orione"))
        restart()   // fresh process: empty index, no vault configured
        seam()
        val reply = say("Qual è il codice del mio progetto di prova?")
        assertTrue("the note reached the model: ${modelCalls.joinToString()}", modelCalls.any { "Orione" in it })
        assertTrue(reply, "Orione" in reply)
        assertFalse("an unrelated library article was used: $reply", reply.contains("Wikibooks") || reply.contains("Progetti universitari"))
    }

    @Test fun differentWordingsAndANewConversationStillFindTheSameFact() {
        save(fact)
        for (q in listOf("Come avevo chiamato il progetto di prova?", "Ti ricordi il nome in codice del progetto?", "Qual era il codice che ti avevo detto di salvare?")) {
            restart(); seam()
            val reply = say(q)
            assertTrue("$q -> ${modelCalls.joinToString()}", modelCalls.any { "Orione" in it })
            assertFalse(q + ": " + reply, reply.contains("Wikibooks"))
        }
    }

    @Test fun aMissingFactIsNotFilledWithEncyclopediaArticles() {
        restart(); seam { "Non ho trovato questa informazione nella tua memoria." }
        val reply = say("Qual è il codice del mio progetto di prova?")
        assertFalse("library article offered for a personal question: $reply", reply.contains("Wikibooks") || reply.contains("Progetti universitari"))
        assertFalse("a personal fact was invented: $reply", "Orione" in reply)
    }

    @Test fun aDeletedNoteIsNoLongerRetrievedAndAnEditedOneIsReadFresh() = runBlocking {
        val rec = save(fact)
        coordinator.memoryIndex.update(rec.id, "Il codice del mio progetto di prova è Sirio.", MemoryKind.PERMANENT)
        restart(); seam { "Il codice è Sirio." }
        say("Qual è il codice del mio progetto di prova?")
        assertTrue(modelCalls.joinToString(), modelCalls.any { "Sirio" in it } && modelCalls.none { "Orione" in it })
        coordinator.memoryIndex.delete(rec.id); restart(); seam { "Non lo trovo." }
        say("Qual è il codice del mio progetto di prova?")
        assertTrue("a deleted note reached the model: ${modelCalls.joinToString()}", modelCalls.none { "Sirio" in it || "Orione" in it })
    }

    @Test fun anOrdinaryQuestionWithTheWordProjectStillReachesTheLibrary() {
        save(fact)
        seam { "Un progetto è un'attività pianificata." }
        val reply = say("Che cos'è un progetto?")
        assertFalse("the personal fact leaked into a general question: $reply", "Orione" in reply)
    }
}
