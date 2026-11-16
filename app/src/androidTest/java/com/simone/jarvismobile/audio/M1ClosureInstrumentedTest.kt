package com.simone.jarvismobile.audio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.simone.jarvismobile.background.AssistantTaskWorker
import com.simone.jarvismobile.core.knowledge.Evidence
import com.simone.jarvismobile.core.knowledge.EvidenceStrength
import com.simone.jarvismobile.core.knowledge.KnowledgeChunk
import com.simone.jarvismobile.core.knowledge.ScoredChunk
import com.simone.jarvismobile.core.memory.RecordStatus
import com.simone.jarvismobile.core.quality.TokenEstimator
import com.simone.jarvismobile.llm.ModelSlot
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * M1 final closure — real coordinator, real memory file, real document manager (with a fault-injection hook), real Room. Only the model call and
 * the knowledge retrieval are replaced. No language model is loaded: this proves state, budget and data-protection behaviour, not answer
 * quality, and it is NOT device verification.
 */
@RunWith(AndroidJUnit4::class)
class M1ClosureInstrumentedTest {
    private lateinit var context: Context
    private lateinit var entry: AssistantTaskWorker.WorkerEntryPoint
    private lateinit var coordinator: SessionCoordinator
    private var n = 0
    private val created = ArrayList<String>()
    private val memoryFile: File get() = File(context.filesDir, "memoria.md")
    private var savedArchive: String? = null

    private val portugal = KnowledgeChunk(
        sourceId = "zim:portogallo", sourceTitle = "Portogallo", section = "",
        text = "Il Portogallo è uno Stato dell'Europa. La capitale è Lisbona. Il Portogallo ha circa 10,3 milioni di abitanti.",
        sourceKind = "zim", collection = "Wikipedia (it)", lead = true,
    )
    private val evidence = Evidence.Grounded(listOf(ScoredChunk(portugal, 1.0, portugal.text, false)), EvidenceStrength.STRONG, titleMatched = true)

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        entry = EntryPointAccessors.fromApplication(context.applicationContext, AssistantTaskWorker.WorkerEntryPoint::class.java)
        coordinator = entry.coordinator()
        coordinator.newConversation()
        savedArchive = memoryFile.takeIf { it.isFile }?.readText()
    }

    @After fun tearDown() = runBlocking {
        coordinator.testSeam = null; entry.documents().readHook = null
        coordinator.newConversation()
        created.forEach { runCatching { entry.memoryIndex().delete(it) } }
        // leave the archive exactly as the test found it
        File(memoryFile.path + ".aside").takeIf { it.exists() }?.let { memoryFile.deleteRecursively(); it.renameTo(memoryFile) }
        Unit
    }

    private fun say(text: String): String = runBlocking {
        withTimeout(90_000) { coordinator.processQueuedText("m1c-${System.nanoTime()}-${n++}", text) }
    }.also { assertNotNull(text, it) }!!

    // ---- P0-A: the memory archive is never silently shrunk ----
    @Test fun aDamagedRecordIsKeptVerbatimWhenTheArchiveIsRewritten() = runBlocking {
        val mem = entry.memoryIndex()
        val good = mem.remember("Ho una moto rossa", autoCategorize = false)!!.also { created += it.id }
        val bad = """<!-- jarvis-memory-v2:{"id":"corrotto","text":"da recuperare a mano","createdAt": -->"""
        memoryFile.appendText("\n$bad\n")
        val added = mem.remember("Ho anche una bicicletta", autoCategorize = false)!!.also { created += it.id }
        val raw = memoryFile.readText()
        assertTrue("the undecodable record survived the rewrite byte for byte", bad in raw)
        val ids = mem.listRecords().map { it.id }
        assertTrue(good.id in ids && added.id in ids)
        assertTrue("a previous good copy exists for recovery", File(memoryFile.path + ".bak").isFile)
    }

    @Test fun anUnreadableArchiveBlocksTheWriteInsteadOfOverwritingIt() = runBlocking {
        val mem = entry.memoryIndex()
        mem.remember("Dato che deve sopravvivere", autoCategorize = false)!!.also { created += it.id }
        val aside = File(memoryFile.path + ".aside")
        assertTrue(memoryFile.renameTo(aside))
        memoryFile.mkdirs() // the "file" can no longer be read as text
        val refused = mem.remember("Questo non deve cancellare nulla", autoCategorize = false)
        assertNull("a read error must fail closed", refused)
        memoryFile.deleteRecursively(); assertTrue(aside.renameTo(memoryFile))
        assertTrue("the original data is intact", memoryFile.readText().contains("Dato che deve sopravvivere"))
    }

    // ---- P1-A: persistence, rectification, conflict, reset ----
    @Test fun statementsSurviveRebuildAndResetAndConflictsCanBeResolved() = runBlocking {
        val mem = entry.memoryIndex()
        val a = mem.remember("Il mio peso è 78 kg", autoCategorize = false)!!.also { created += it.id }
        val b = mem.remember("Il mio peso è 85 kg", autoCategorize = false)!!.also { created += it.id }
        // "restart": drop everything held in memory and re-read the file
        mem.clear(); mem.rebuild()
        val offered = mem.retrieve("quanto peso", 8).map { it.chunk.text }
        assertTrue(offered.toString(), offered.any { "78 kg" in it } && offered.any { "85 kg" in it } && offered.all { "conflitto" in it })
        assertEquals(RecordStatus.CONFLICT, mem.listRecords().first { it.id == a.id }.status)
        assertTrue(mem.resolveConflict(b.id))
        mem.clear(); mem.rebuild()
        val resolved = mem.retrieve("quanto peso", 8).map { it.chunk.text }
        assertTrue(resolved.toString(), resolved.any { "85 kg" in it } && resolved.none { "78 kg" in it })
        coordinator.newConversation() // a conversation reset must not erase durable memory
        assertTrue(mem.listRecords().any { it.id == b.id && it.status == RecordStatus.ACTIVE })
    }

    // ---- P0-B / P0-C: the final prompt is measured; sources left out are not "absent" ----
    @Test fun noPromptThatTheModelReceivesExceedsTheBudgetAndExcludedSourcesAreNamedAsSuch() {
        val received = CopyOnWriteArrayList<Int>()
        coordinator.testSeam = ChatTestSeam(chat = { m -> received += TokenEstimator.message(m); "La capitale è Lisbona." }, evidence = evidence)
        val replies = listOf(3_000, 4_500, 5_500, 6_200, 6_800).map { len -> say("Parlami della capitale del Portogallo " + "x".repeat(len)) }
        assertTrue("the model never received a message above the prompt ceiling: $received", received.all { it < 3_072 })
        assertTrue("some size of request shows the budget-exclusion wording or the explicit refusal", replies.any { "non ho potuto usarle" in it || "supera la memoria" in it || "troppo lunga" in it })
        replies.filter { "non ho potuto usarle" in it }.forEach {
            assertFalse("an excluded source is never cited as support: $it", "Fonti a supporto" in it)
            assertFalse("and is not presented as nonexistent: $it", "non ho trovato" in it.lowercase())
        }
    }

    // ---- P1-B: FAST -> ADVANCED -> FAST ----
    @Test fun switchingBrainsAndBackKeepsThePublishedConversationAndResetsTheNativeContext() {
        var slot = ModelSlot.FAST
        coordinator.testSeam = ChatTestSeam(chat = { "Risposta del cervello ${slot.name}." }, slot = { slot })
        val r1 = say("Come stai oggi?")
        slot = ModelSlot.ADVANCED; val r2 = say("Parlami della storia di Roma antica")
        slot = ModelSlot.FAST; val r3 = say("Grazie, ora cambiamo discorso")
        assertTrue(r1.contains("FAST") && r2.contains("ADVANCED") && r3.contains("FAST"))
        val published = coordinator.messages.value
        assertEquals(6, published.size) // three user turns, three answers, nothing lost or duplicated by the switches
        val diag = coordinator.exportContextDiagnostics()
        assertTrue(diag, diag.contains("slot_change"))
        assertTrue(diag, diag.contains("(stima)") && !diag.contains("Come stai"))
    }

    // ---- P1-C: real document read failures ----
    @Test fun aFailingDocumentReadIsReportedAsAReadErrorNotAsNothingFound() {
        coordinator.testSeam = ChatTestSeam(chat = { "La capitale è Lisbona." })
        entry.documents().readHook = { throw java.io.IOException("simulated disk failure") }
        val reply = say("Qual è la capitale del Portogallo?")
        assertTrue(reply, reply.contains("errore di lettura"))
        assertFalse(reply, reply.lowercase().contains("nessun documento") || reply.lowercase().contains("non ho trovato"))
        entry.documents().readHook = null
        val ok = say("Qual è la capitale del Portogallo?")
        assertFalse("with a healthy (empty) store there is no read-error wording: $ok", ok.contains("errore di lettura"))
    }

    @Test fun cancellingWhileADocumentReadIsSuspendedPublishesNothing() = runBlocking {
        val inRead = CompletableDeferred<Unit>(); val never = CompletableDeferred<Unit>()
        coordinator.testSeam = ChatTestSeam(chat = { "La capitale è Madrid." })
        entry.documents().readHook = { inRead.complete(Unit); never.await() }
        val job = async(Dispatchers.Default) { runCatching { coordinator.processQueuedText("m1c-cancel-${System.nanoTime()}", "Qual è la capitale della Spagna?") } }
        withTimeout(60_000) { inRead.await() }
        coordinator.cancelTextGeneration(); job.cancelAndJoin()
        delay(1_000)
        assertTrue(coordinator.messages.value.none { !it.fromUser && "Madrid" in it.text })
        // and the archive of documents was not touched by the failed/cancelled read
        entry.documents().readHook = null
        assertNotNull(entry.documents().documents.value)
    }
}
