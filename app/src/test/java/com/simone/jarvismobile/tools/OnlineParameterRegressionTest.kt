package com.simone.jarvismobile.tools

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * § FASE 2A.7 RELEASE GATE 15 — a real regression class hit twice already:
 * FASE 2A.5-bis found `ConversationalJarvisEngine`'s call sites hardcoding
 * `online = false` to [ToolRunner.run]/`ToolRouter.execute`, and FASE 2A.6
 * found the exact same pattern in `ProModeCoordinator` — both meant a real
 * network-requiring tool (`get_weather`) could never succeed regardless of
 * actual connectivity. Rather than trust a source-review audit to catch a
 * third occurrence, this test scans the real `app/src/main` source tree at
 * test time for the literal anti-pattern (`online = false` as a call-site
 * argument) — a plain file-content check, not an Android/Robolectric test,
 * so it needs nothing beyond a JVM to run.
 *
 * Deliberately does NOT flag [ToolRunner.run]'s/`ToolRouter.execute`'s own
 * parameter DECLARATIONS (`online: Boolean = false`) — those legitimately
 * need a default. The regex only matches a bare `online = false` (no
 * preceding `: Boolean`), which is exactly the call-site shape a
 * declaration never has.
 */
class OnlineParameterRegressionTest {

    @Test
    fun `no call site in app source hardcodes online = false`() {
        val root = mainSourceRoot()
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> offendingLines(file) }
            .toList()

        assertTrue(
            "Found hardcoded `online = false` at a tool-execution call site " +
                "(this always rejects a real network-requiring tool regardless of " +
                "actual connectivity — read the live network state instead, e.g. " +
                "via ContextEngine, exactly as ConversationalJarvisEngine/" +
                "ProModeCoordinator already do):\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /** A call-site `online = false`, never a `online: Boolean = false` parameter declaration, never inside a comment. */
    private val callSitePattern = Regex("""\bonline\s*=\s*false\b""")
    private val declarationPattern = Regex(""":\s*Boolean\s*=\s*false""")

    private fun offendingLines(file: File): List<String> =
        file.readLines().withIndex().mapNotNull { (index, line) ->
            val trimmed = line.trim()
            val isComment = trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")
            if (!isComment && callSitePattern.containsMatchIn(line) && !declarationPattern.containsMatchIn(line)) {
                "${file.path}:${index + 1}: ${line.trim()}"
            } else {
                null
            }
        }

    /**
     * Resolves `app/src/main` regardless of whether the test process's
     * working directory is the `app/` module (the Gradle default) or the
     * repository root — never silently scanning zero files and reporting a
     * false "all clear".
     */
    private fun mainSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java/com/simone/jarvismobile"),
            File("app/src/main/java/com/simone/jarvismobile"),
        )
        val found = candidates.firstOrNull { it.isDirectory }
        checkNotNull(found) {
            "Could not locate app/src/main source root from working directory " +
                "${File(".").absolutePath} — this test cannot silently pass without scanning any file."
        }
        return found
    }
}
