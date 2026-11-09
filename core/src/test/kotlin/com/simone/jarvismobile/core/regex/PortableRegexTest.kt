package com.simone.jarvismobile.core.regex

import com.simone.jarvismobile.core.document.DocumentQueryParser
import com.simone.jarvismobile.core.memory.StoredFactQuestion
import com.simone.jarvismobile.core.notes.NoteCommandParser
import com.simone.jarvismobile.core.offlinehub.HubCommand
import com.simone.jarvismobile.core.offlinehub.OfflineHubParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PortableRegexTest {
    @Test fun theLeadingUnicodeFlagIsMappedToTheOneAndroidAccepts() {
        assertEquals("(?iu)^a", PortableRegex.androidSafe("(?iU)^a"))
        assertEquals("(?imu)^a", PortableRegex.androidSafe("(?imU)^a"))
        assertEquals("(?u)<x>", PortableRegex.androidSafe("(?U)<x>"))
        assertEquals("(?iu)x", PortableRegex.androidSafe("(?iuU)x")) // no duplicated flag
    }

    @Test fun expressionsWithoutTheFlagAreLeftAlone() {
        assertEquals("(?i)ciao", PortableRegex.androidSafe("(?i)ciao"))
        assertEquals("""a\sU(?U)""", PortableRegex.androidSafe("""a\sU(?U)""")) // only the LEADING group is touched
        assertEquals("ciao", PortableRegex.androidSafe("ciao"))
    }

    @Test fun anInvalidExpressionStillFailsLoudly() {
        kotlin.test.assertFailsWith<java.util.regex.PatternSyntaxException> { PortableRegex.of("(?iU)(") }
    }

    @Test fun noCoreSourceBuildsARawRegexWithTheUnicodeClassFlag() {
        val root = listOf(File("src/main/kotlin"), File("core/src/main/kotlin")).first { it.isDirectory }
        val offenders = root.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "PortableRegex.kt" }
            .filter { Regex("""Regex\(\s*(?:\"\"\"|\")\(\?[a-zA-Z]*U""").containsMatchIn(it.readText()) }
            .map { it.name }.toList()
        assertTrue(offenders.isEmpty(), "use PortableRegex.of for (?…U) expressions: $offenders")
    }
}

/** Ordinary chat must never be captured by a deterministic parser: it has to reach the language model. */
class OrdinaryQuestionsReachTheModelTest {
    private val questions = listOf(
        "Ciao Jarvis, presentati brevemente in italiano",
        "Che cosa sai fare?",
        "Spiegami la fotosintesi in due frasi",
        "Perché il cielo è azzurro?",
        "Scrivi una poesia sul mare",
        "Come stai oggi?",
        "Raccontami una barzelletta",
        "Qual è la capitale della Francia?",
    )

    @Test fun noPreModelParserCapturesAnOrdinaryQuestion() {
        questions.forEach { q ->
            assertNull(OfflineHubParser.parse(q), "hub: $q")
            assertNull(DocumentQueryParser.parse(q), "documents: $q")
            assertNull(NoteCommandParser.parse(q), "notes: $q")
            assertFalse(StoredFactQuestion.matches(q), "memory: $q")
        }
    }

    @Test fun theOfflineCommandsStillWork() {
        assertEquals(HubCommand.OpenKiwix, OfflineHubParser.parse("Apri Kiwix"))
        assertEquals(HubCommand.OpenOrganicMaps, OfflineHubParser.parse("apri Organic Maps"))
        assertEquals(HubCommand.OpenOfflineNavigator, OfflineHubParser.parse("Apri il navigatore offline"))
        assertEquals(HubCommand.OrganicSearch("farmacia"), OfflineHubParser.parse("Cerca una farmacia su Organic Maps"))
        val show = OfflineHubParser.parse("Mostra 41.9028, 12.4964 su Organic Maps")
        assertTrue(show is HubCommand.OrganicShow && show.point != null)
        assertTrue(OfflineHubParser.parse("Portami a Roma con Organic Maps") is HubCommand.OrganicRoute)
        assertTrue(OfflineHubParser.parse("Cerca Roma su Kiwix") is HubCommand.KiwixSearch)
        assertNotNull(OfflineHubParser.parse("Cosa dice l'archivio ZIM su Roma?"))
    }
}
