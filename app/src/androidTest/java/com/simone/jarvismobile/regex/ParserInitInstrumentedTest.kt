package com.simone.jarvismobile.regex

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.simone.jarvismobile.background.AssistantTaskWorker
import com.simone.jarvismobile.core.document.DocumentQueryParser
import com.simone.jarvismobile.core.document.PassageSanitizer
import com.simone.jarvismobile.core.document.SourceCitations
import com.simone.jarvismobile.core.memory.StoredFactQuestion
import com.simone.jarvismobile.core.notes.NoteCommandParser
import com.simone.jarvismobile.core.offlinehub.HubCommand
import com.simone.jarvismobile.core.offlinehub.OfflineHubParser
import com.simone.jarvismobile.core.regex.PortableRegex
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BETA 8 hotfix — the deterministic parsers that run BEFORE the language model, on the REAL Android regex engine.
 * Regression for: "errore tecnico: com.simone.jarvismobile.core.offlinehub.OfflineHubParser" on an ordinary question,
 * caused by `(?iU)` being rejected by Android's ICU regex (ExceptionInInitializerError, then NoClassDefFoundError).
 *
 * Proves the initialisation and the routing of the parsers on an emulator. It does NOT prove that the Gemma/LiteRT-LM
 * model answers: no model is available in CI. That is checked on the Honor 200.
 */
@RunWith(AndroidJUnit4::class)
class ParserInitInstrumentedTest {
    private val ordinary = listOf(
        "Ciao Jarvis, presentati brevemente in italiano",
        "Che cosa sai fare?",
        "Perché il cielo è azzurro?",
        "Scrivi una poesia sul mare",
        "Qual è la capitale della Francia?",
    )

    @Test fun theEngineRejectsTheOldFlagAndAcceptsTheSafeForm() {
        // documents the root cause on this very runtime
        val rejected = try { Regex("(?iU)ciao"); false } catch (e: java.util.regex.PatternSyntaxException) { true }
        assertTrue("this Android runtime was expected to reject (?iU); if it does not, the hotfix is merely redundant", rejected)
        assertTrue(Regex("(?iu)ciao").matches("CIAO"))
        assertTrue(PortableRegex.of("(?iU)ciao").matches("CIAO"))          // falls back transparently
        assertTrue(PortableRegex.of("(?imU)^a").containsMatchIn("x\nA"))
        assertTrue(PortableRegex.of("(?U)\\bti\\s+ricordi\\b").containsMatchIn("ti ricordi?"))
    }

    @Test fun everyParserInitialisesAndLeavesOrdinaryQuestionsAlone() {
        ordinary.forEach { q ->
            assertNull("hub: $q", OfflineHubParser.parse(q))
            assertNull("documents: $q", DocumentQueryParser.parse(q))
            assertNull("notes: $q", NoteCommandParser.parse(q))
            assertFalse("memory: $q", StoredFactQuestion.matches(q))
        }
        assertNotNull(PassageSanitizer.clean("user: ciao <|x|>", 100))
        assertNotNull(SourceCitations.unverifiedReferences("vedi file.pdf pagina 3", emptyList()))
    }

    @Test fun kiwixAndOrganicMapsCommandsStillParse() {
        assertEquals(HubCommand.OpenKiwix, OfflineHubParser.parse("Apri Kiwix"))
        assertEquals(HubCommand.OpenOrganicMaps, OfflineHubParser.parse("apri Organic Maps"))
        assertEquals(HubCommand.OpenOfflineNavigator, OfflineHubParser.parse("Apri il navigatore offline"))
        assertEquals(HubCommand.OrganicSearch("farmacia"), OfflineHubParser.parse("Cerca una farmacia su Organic Maps"))
        val show = OfflineHubParser.parse("Mostra 41.9028, 12.4964 su Organic Maps")
        assertTrue(show is HubCommand.OrganicShow && show.point != null)
        assertTrue(OfflineHubParser.parse("Portami a Roma con Organic Maps") is HubCommand.OrganicRoute)
        assertTrue(OfflineHubParser.parse("Cerca Roma su Kiwix") is HubCommand.KiwixSearch)
        assertTrue(OfflineHubParser.parse("Cosa dice l'archivio ZIM su Roma?") is HubCommand.ZimQuestion)
    }

    @Test fun otherParsersStillRecogniseTheirCommands() {
        assertNotNull(NoteCommandParser.parse("crea una nota chiamata Spesa: latte e pane"))
        assertNotNull(DocumentQueryParser.parse("riassumi il documento contratto.pdf"))
        assertTrue(StoredFactQuestion.matches("ti avevo detto dove ho parcheggiato"))
    }

    @Test fun accentedItalianBehavesLikeUnicodeOnThisEngine() {
        assertTrue(PortableRegex.of("(?iU)\\bperch[eé]\\b").containsMatchIn("PERCHÉ no"))
        assertTrue(PortableRegex.of("(?iU)^\\s*\\p{L}+\\s*$").matches(" perché "))
    }

    /** The real chat path (coordinator → hub → documents → … → model) no longer dies with a technical error. */
    @Test fun anOrdinaryChatTurnProducesAnAnswerOrAnHonestModelMessageNeverATechnicalCrash() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val coordinator = EntryPointAccessors.fromApplication(context.applicationContext, AssistantTaskWorker.WorkerEntryPoint::class.java).coordinator()
        val reply = withTimeout(90_000) { coordinator.processQueuedText("beta8-regex-${System.nanoTime()}", ordinary.first()) }
        assertNotNull(reply)
        assertFalse(reply!!, reply.contains("errore tecnico", ignoreCase = true))
        assertFalse(reply, reply.contains("OfflineHubParser"))
        assertTrue("empty reply", reply.isNotBlank())
        // an offline command is answered by the hub instead of the model (Kiwix is not installed on the emulator)
        val hub = withTimeout(90_000) { coordinator.processQueuedText("beta8-hub-${System.nanoTime()}", "Apri Kiwix") }
        assertNotNull(hub)
        assertFalse(hub!!, hub.contains("errore tecnico", ignoreCase = true))
        assertTrue(hub, hub.contains("Kiwix", ignoreCase = true))
    }
}
