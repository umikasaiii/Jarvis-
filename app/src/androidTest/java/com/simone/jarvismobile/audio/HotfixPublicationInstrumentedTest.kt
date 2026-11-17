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
import com.simone.jarvismobile.core.reliability.GeneralAnswerPolicy
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
 * HOTFIX POST-M4 / H3 + H4 — the REAL SessionCoordinator (production Hilt graph) with ONLY the model call and the knowledge retrieval replaced through
 * [ChatTestSeam]. The model here is a SIMULATED answer (including a deliberately wrong one, as Gemma 1B gave on the device): this proves what the
 * PIPELINE does with a raw reply, not what any real model says. Not a device result. Runs on the CI emulator only.
 */
@RunWith(AndroidJUnit4::class)
class HotfixPublicationInstrumentedTest {
    private lateinit var coordinator: SessionCoordinator
    private val modelCalls = CopyOnWriteArrayList<String>()
    private val savedIds = ArrayList<String>()
    private var n = 0

    private val wikibooks = KnowledgeChunk(
        sourceId = "zim:wikibooks", sourceTitle = "Progetti universitari", section = "",
        text = "I progetti universitari sono attività di gruppo.", sourceKind = "zim", collection = "Wikibooks (it)", lead = true,
    )
    private val unrelated = Evidence.Grounded(listOf(ScoredChunk(wikibooks, 1.0, wikibooks.text, false)), EvidenceStrength.MODERATE, titleMatched = true)

    @Before fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        coordinator = EntryPointAccessors.fromApplication(context.applicationContext, AssistantTaskWorker.WorkerEntryPoint::class.java).coordinator()
        coordinator.newConversation()
    }

    @After fun tearDown() = runBlocking {
        coordinator.testSeam = null
        savedIds.forEach { runCatching { coordinator.memoryIndex.delete(it) } }
        coordinator.newConversation()
    }

    private fun seam(evidence: Evidence? = unrelated, answer: suspend (String) -> String?) {
        modelCalls.clear()
        coordinator.testSeam = ChatTestSeam(chat = { m -> modelCalls += m; answer(m) }, evidence = evidence)
    }

    private fun say(text: String): String = runBlocking { withTimeout(90_000) { coordinator.processQueuedText("h3-${System.nanoTime()}-${n++}", text) } }
        .also { assertNotNull(text, it) }!!

    private val rawWrong = "L'insulina è prodotta principalmente dal fegato."

    @Test fun anExplicitGeneralKnowledgeRequestKeepsTheModelsWordsVerbatimLabelsThemAndNeverCitesAnything() {
        seam { rawWrong }
        val reply = say("Senza usare gli archivi offline come prova, spiegami usando le tue conoscenze generali quale organo produce l'insulina. Specifica chiaramente che la risposta non è verificata nelle fonti locali.")
        assertTrue("the pipeline changed or dropped the model's sentence: $reply", reply.contains(rawWrong))
        assertTrue("no label: $reply", reply.startsWith(GeneralAnswerPolicy.USER_REQUESTED_GENERAL))
        assertTrue("a general answer on health must state its limit: $reply", reply.contains(GeneralAnswerPolicy.HIGH_RISK_NOTE))
        assertFalse("a citation or source footer was attached to model knowledge: $reply", reply.contains("[1]") || reply.contains("Fonti") || reply.contains("Progetti universitari"))
        assertFalse("retrieval ran although the user asked not to use the archives: ${modelCalls.joinToString()}", modelCalls.any { "Progetti universitari" in it })
    }

    @Test fun aHighRiskAnswerWithoutAnySourceStatesItsLimitEvenWithoutANumberOrAName() {
        seam(evidence = Evidence.NoEvidence) { rawWrong }
        val reply = say("Quale organo produce l'insulina?")
        assertTrue(reply, reply.contains(rawWrong) && reply.contains(GeneralAnswerPolicy.HIGH_RISK_NOTE))
        assertFalse(reply, reply.contains("[1]") || reply.contains("Fonti a supporto"))
    }

    @Test fun anIrrelevantSourceIsNeverUsedAsProofForAGeneralAnswer() {
        seam(evidence = unrelated) { rawWrong }
        val reply = say("Quale organo produce l'insulina?")
        assertFalse("an unrelated article was presented as support: $reply", reply.contains("Fonti a supporto") && reply.contains("Progetti universitari"))
    }

    @Test fun withNoModelLoadedTheUserGetsAnHonestMessageAndTheSavedFactIsUntouched() = runBlocking {
        val rec = coordinator.memoryIndex.remember("Il codice del mio progetto di prova è Orione.", MemoryKind.PERMANENT, autoCategorize = false)
        assertNotNull(rec); savedIds += rec!!.id
        coordinator.testSeam = null                       // production routing, no model loaded on the CI emulator
        coordinator.memoryIndex.clear()                   // fresh process
        val reply = say("Qual è il codice del mio progetto di prova?")
        assertTrue("an empty or fabricated reply: $reply", reply.isNotBlank())
        assertFalse("a fact was invented or a library article offered: $reply", reply.contains("Progetti universitari"))
        assertTrue("the saved fact must still be there", coordinator.memoryIndex.listRecords().any { it.id == rec.id && "Orione" in it.text })
        assertEquals(1, coordinator.memoryIndex.listRecords().count { it.id == rec.id })
    }
}
