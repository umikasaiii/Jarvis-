package com.simone.jarvismobile.regex

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.simone.jarvismobile.core.document.DocumentQueryParser
import com.simone.jarvismobile.core.document.PassageSanitizer
import com.simone.jarvismobile.core.document.SourceCitations
import com.simone.jarvismobile.core.memory.StoredFactQuestion
import com.simone.jarvismobile.core.notes.NoteCommandParser
import com.simone.jarvismobile.core.offlinehub.OfflineHubParser
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** TEMPORARY DIAGNOSTIC (always fails on purpose): reports what the real Android regex engine does. */
@RunWith(AndroidJUnit4::class)
class RegexProbeInstrumentedTest {
    private fun chain(t: Throwable): String = generateSequence(t) { it.cause }.joinToString(" <- ") { it.javaClass.name + ": " + it.message }

    private fun probe(name: String, block: () -> Any?): String =
        try { "$name OK -> ${block()}" } catch (t: Throwable) { "$name THROWS ${chain(t)}" }

    @Test fun report() {
        val sb = StringBuilder("\n=== REGEX PROBE (Android ${android.os.Build.VERSION.SDK_INT}) ===\n")
        fun add(s: String) { sb.append(s).append('\n') }
        listOf("(?iU)ciao", "(?iu)ciao", "(?i)ciao", "(?U)ciao", "(?imU)^a", "(?imu)^a", "(?u)ciao").forEach { p ->
            add(probe("Regex[$p]") { Regex(p).matches("CIAO") })
        }
        add(probe("flag i+UNICODE_CHARACTER_CLASS const") { Regex("ciao", setOf(RegexOption.IGNORE_CASE)).matches("CIAO") })
        add(probe("\\b after accent (?i)") { Regex("""(?i)\bperch[eé]\b""").containsMatchIn("perché si") })
        add(probe("\\b after accent (?iu)") { Regex("""(?iu)\bperch[eé]\b""").containsMatchIn("perché si") })
        add(probe("\\w accent") { Regex("""(?i)^\w+$""").matches("perché") })
        add(probe("\\p{L}") { Regex("""^\p{L}+$""").matches("perché") })
        add(probe("case Ü/ü (?iu)") { Regex("""(?iu)ÜBER""").matches("über") })
        add(probe("case Ü/ü (?i)") { Regex("""(?i)ÜBER""").matches("über") })
        add(probe("OfflineHubParser.parse(ciao)") { OfflineHubParser.parse("Ciao Jarvis, presentati brevemente in italiano") })
        add(probe("OfflineHubParser.parse(apri kiwix)") { OfflineHubParser.parse("apri Kiwix") })
        add(probe("DocumentQueryParser.parse") { DocumentQueryParser.parse("Ciao Jarvis, presentati brevemente in italiano") })
        add(probe("StoredFactQuestion.matches") { StoredFactQuestion.matches("Ciao Jarvis, presentati brevemente in italiano") })
        add(probe("NoteCommandParser.parse") { NoteCommandParser.parse("Ciao Jarvis, presentati brevemente in italiano") })
        add(probe("PassageSanitizer.clean") { PassageSanitizer.clean("user: ciao <|x|>", 100) })
        add(probe("SourceCitations.unverifiedReferences") { SourceCitations.unverifiedReferences("vedi file.pdf pagina 3", emptyList()) })
        fail(sb.toString())
    }
}
