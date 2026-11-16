package com.simone.jarvismobile.audio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNull
import com.simone.jarvismobile.background.AssistantTaskWorker
import com.simone.jarvismobile.core.knowledge.Evidence
import com.simone.jarvismobile.core.knowledge.EvidenceStrength
import com.simone.jarvismobile.core.knowledge.KnowledgeChunk
import com.simone.jarvismobile.core.knowledge.ScoredChunk
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
 * M0 — the REAL SessionCoordinator (production Hilt graph, `processQueuedText` = the chat entry the worker uses) with ONLY the model
 * call and the knowledge retrieval replaced through [ChatTestSeam]. Routing, request classification, evidence package, policy,
 * orchestrator (one revision), attempt ledger, citations, footers, chat history and conversation invalidation are production code.
 * No language model is loaded: this proves the publication PIPELINE, not model quality, and it is NOT device verification.
 */
@RunWith(AndroidJUnit4::class)
class SessionCoordinatorPublicationInstrumentedTest {
    private lateinit var coordinator: SessionCoordinator
    private var n = 0
    private val modelCalls = CopyOnWriteArrayList<String>()

    private val portugal = KnowledgeChunk(
        sourceId = "zim:portogallo", sourceTitle = "Portogallo", section = "",
        text = "Il Portogallo è uno Stato dell'Europa. La capitale è Lisbona. Il Portogallo ha circa 10,3 milioni di abitanti.",
        sourceKind = "zim", collection = "Wikipedia (it)", lead = true,
    )
    private val evidence = Evidence.Grounded(listOf(ScoredChunk(portugal, 1.0, portugal.text, false)), EvidenceStrength.STRONG, titleMatched = true)

    @Before fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        coordinator = EntryPointAccessors.fromApplication(context.applicationContext, AssistantTaskWorker.WorkerEntryPoint::class.java).coordinator()
        coordinator.newConversation()
    }

    @After fun tearDown() { coordinator.testSeam = null; coordinator.newConversation() }

    private fun seam(evidence: Evidence? = this.evidence, answer: suspend (String) -> String?) {
        modelCalls.clear()
        coordinator.testSeam = ChatTestSeam(chat = { m -> modelCalls += m; answer(m) }, evidence = evidence)
    }

    private fun say(text: String): String = runBlocking {
        withTimeout(90_000) { coordinator.processQueuedText("m0-${System.nanoTime()}-${n++}", text) }
    }.also { assertNotNull(text, it) }!!

    @Test fun anUnsupportedPropositionIsNotPublishedAndCitationsPointToTheRealPassage() {
        seam { "La capitale è Lisbona. Il Portogallo ha 50 milioni di abitanti." }
        val reply = say("Qual è la capitale del Portogallo e quanti abitanti ha?")
        assertTrue(reply, "Lisbona" in reply)
        assertFalse("the false proposition was published: $reply", "50 milioni" in reply)
        assertTrue("marker rebuilt from the verified link: $reply", "[1]" in reply)
        assertTrue("footer names the real source as support: $reply", reply.contains("Fonti a supporto:") && reply.contains("[1] Portogallo"))
        assertTrue("at most two generations for one request: ${modelCalls.size}", modelCalls.size in 1..2)
        assertEquals("the stored assistant line is exactly what was published", reply, coordinator.messages.value.last { !it.fromUser }.text)
    }

    @Test fun aFaithfulAnswerIsPublishedWholeWithOneGeneration() {
        seam { "La capitale del Portogallo è Lisbona." }
        val reply = say("Qual è la capitale del Portogallo?")
        assertTrue(reply, "Lisbona" in reply && "[1]" in reply)
        assertEquals(1, modelCalls.size)
    }

    @Test fun aRejectedDraftNeverReturnsInTheNextTurn() {
        seam { "La capitale è Madrid." }
        val first = say("Qual è la capitale del Portogallo?")
        assertFalse(first, "Madrid" in first)
        assertTrue(first, "Lisbona" in first) // the sources' own sentence, quoted
        assertTrue("draft rejected: at most the first attempt and one revision", modelCalls.size <= 2)
        val published = coordinator.messages.value.filter { !it.fromUser }.joinToString("\n") { it.text }
        assertFalse("the discarded candidate is not in the history the next turn is rebuilt from", "Madrid" in published)
        seam { "Lisbona." }
        val second = say("Qual è la capitale del Portogallo?")
        assertFalse(second, "Madrid" in second)
        assertTrue(second, "Lisbona" in second)
        assertEquals(1, modelCalls.size)
    }

    @Test fun anInventedActionInAMixedToolAndKnowledgeTurnIsDropped() {
        seam { m ->
            val pct = Regex("(\\d{1,3})\\s*%").find(m.substringAfter("[Risultati verificati"))?.groupValues?.get(1) ?: "0"
            "Hai il $pct% di batteria. La capitale è Lisbona. Ho impostato il timer."
        }
        val reply = say("Che batteria ho? Qual è la capitale del Portogallo?")
        assertFalse("an action no tool reported was published: $reply", reply.contains("timer", ignoreCase = true) && reply.contains("impostato", ignoreCase = true))
        assertTrue(reply, "Lisbona" in reply)
    }

    @Test fun stopDoesNotPublishAStaleAnswer() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        seam { entered.complete(Unit); release.await(); "La capitale è Madrid." }
        val job = async(Dispatchers.Default) { runCatching { coordinator.processQueuedText("m0-stop-${System.nanoTime()}", "Qual è la capitale del Portogallo?") } }
        withTimeout(60_000) { entered.await() }
        coordinator.cancelTextGeneration()
        job.cancelAndJoin()
        release.complete(Unit)
        delay(1_500)
        assertFalse("a cancelled generation published its answer", coordinator.messages.value.any { !it.fromUser && "Madrid" in it.text })
    }

    @Test fun withoutAnySourceAPreciseFactIsQualifiedAndSmallTalkIsNot() {
        seam(evidence = null) { m -> if ("barzelletta" in m) "Perché il libro di matematica era triste? Aveva troppi problemi." else "La Tour Eiffel è alta 330 metri." }
        val fact = say("Quanto è alta la Tour Eiffel?")
        assertTrue(fact, fact.contains("non è verificato") || fact.contains("non verificato"))
        val chat = say("Dimmi una barzelletta")
        assertFalse(chat, chat.contains("non è verificato") || chat.contains("Fonti"))
    }
}

/**
 * M1 — conversation state, context budget and memory provenance through the REAL coordinator / real memory file. As above: only the model call
 * and the retrieval are replaced; no language model, so this proves state handling, not answer quality, and it is not device verification.
 */
@RunWith(AndroidJUnit4::class)
class ConversationStateInstrumentedTest {
    private lateinit var entry: AssistantTaskWorker.WorkerEntryPoint
    private lateinit var coordinator: SessionCoordinator
    private var n = 0
    private val created = ArrayList<String>()

    @Before fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        entry = EntryPointAccessors.fromApplication(context.applicationContext, AssistantTaskWorker.WorkerEntryPoint::class.java)
        coordinator = entry.coordinator()
        coordinator.newConversation()
    }

    @After fun tearDown() = runBlocking {
        coordinator.testSeam = null; coordinator.newConversation()
        created.forEach { runCatching { entry.memoryIndex().delete(it) } }
    }

    private fun say(text: String): String = runBlocking {
        withTimeout(90_000) { coordinator.processQueuedText("m1-${System.nanoTime()}-${n++}", text) }
    }.also { assertNotNull(text, it) }!!

    @Test fun aLateAnswerFromBeforeAResetIsNeverPublishedOrStored() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        coordinator.testSeam = ChatTestSeam(chat = { entered.complete(Unit); release.await(); "Risposta del turno precedente: Madrid." })
        val job = async(Dispatchers.Default) { runCatching { coordinator.processQueuedText("m1-late-${System.nanoTime()}", "Qual è la capitale della Spagna?") } }
        withTimeout(60_000) { entered.await() }
        coordinator.newConversation() // the user starts over while the old turn is still generating
        release.complete(Unit)
        job.await()
        delay(1_500)
        assertTrue("the old turn leaked into the new conversation: ${coordinator.messages.value}", coordinator.messages.value.none { "Madrid" in it.text })
    }

    @Test fun twelveTurnsWithTopicChangesKeepTheContextBounded() {
        val seen = java.util.concurrent.CopyOnWriteArrayList<Int>()
        coordinator.testSeam = ChatTestSeam(chat = { m -> seen += m.length; "Risposta numero ${seen.size} su un argomento qualsiasi, abbastanza lunga da occupare spazio nella conversazione." })
        val topics = listOf("la cucina", "i treni", "la musica", "il giardino", "la storia romana", "il calcio", "la fotografia", "i vulcani", "il caffè", "la chimica", "il cinema", "lo spazio")
        topics.forEach { say("Parlami di $it e dei suoi aspetti più importanti nella vita quotidiana") }
        assertEquals(12, coordinator.messages.value.count { !it.fromUser })
        assertTrue("each prompt stayed far below the window (chars): $seen", seen.all { it < 6_000 })
    }

    @Test fun aRequestLargerThanTheWindowIsRefusedExplicitlyNotSilentlyTruncated() {
        var calls = 0
        coordinator.testSeam = ChatTestSeam(chat = { calls++; "ok" })
        val reply = say("Parlami di " + "argomento ".repeat(6_000))
        assertTrue(reply, reply.contains("troppo lunga"))
        assertEquals("no generation pretends to have read it", 0, calls)
    }

    @Test fun aCorrectionSupersedesWithoutErasingAndModelOutputNeverEntersMemory() = runBlocking {
        val mem = entry.memoryIndex()
        val a = mem.remember("Il mio peso è 78 kg", autoCategorize = false)!!.also { created += it.id }
        val b = mem.remember("Correggo: ora peso 80 kg", autoCategorize = false)!!.also { created += it.id }
        val all = mem.listRecords()
        assertEquals(com.simone.jarvismobile.core.memory.RecordStatus.SUPERSEDED, all.first { it.id == a.id }.status)
        assertEquals(a.id, all.first { it.id == b.id }.supersedes)
        mem.rebuild()
        val offered = mem.retrieve("quanto peso", 8).map { it.chunk.text }
        assertTrue(offered.toString(), offered.any { "80 kg" in it } && offered.none { "78 kg" in it })
        // a draft the gate rejected, a model inference and a supposition are not stored
        assertNull(mem.remember("Il tuo peso è 90 kg", source = com.simone.jarvismobile.core.memory.MemorySource.REJECTED_DRAFT))
        assertNull(mem.remember("Probabilmente pesi 90 kg", source = com.simone.jarvismobile.core.memory.MemorySource.MODEL_INFERENCE))
        assertTrue(mem.listRecords().none { "90 kg" in it.text })
    }
}
