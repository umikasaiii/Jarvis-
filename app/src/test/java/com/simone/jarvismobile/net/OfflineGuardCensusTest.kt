package com.simone.jarvismobile.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * M3-D — source-level CENSUS of every way JARVIS's own code can open a network connection (JVM; it proves wiring, not behaviour — the behavioural proof
 * is the instrumented test that drives real OkHttp clients through [OfflineGate] against a loopback socket). A new, unguarded client fails this test.
 */
class OfflineGuardCensusTest {
    private val root: File = listOf(File("src/main/java/com/simone/jarvismobile"), File("app/src/main/java/com/simone/jarvismobile"))
        .firstOrNull { it.isDirectory } ?: error("app sources not found")
    private val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test fun everyOkHttpClientIsBuiltThroughTheGate() {
        val offenders = ArrayList<String>()
        var clients = 0
        for (f in files) {
            if (f.name == "OfflineGate.kt") continue
            val text = f.readText()
            Regex("OkHttpClient\\.Builder\\(\\)").findAll(text).forEach { m ->
                clients++
                if (!text.substring(m.range.last + 1).trimStart().startsWith(".guarded(")) offenders += f.name
            }
        }
        assertTrue("unguarded OkHttp clients: $offenders", offenders.isEmpty())
        assertEquals("the census of guarded clients changed: add the new one to docs/OFFLINE_POLICY.md", 10, clients)
    }

    @Test fun noOtherRawNetworkApiIsUsed() {
        val raw = Regex("""HttpURLConnection|\.openConnection\(|\.openStream\(|java\.net\.Socket\(|DatagramSocket|\bWebView\b|HttpsURLConnection""")
        val hits = files.filter { f -> raw.containsMatchIn(f.readText()) }.map { it.name }
        assertTrue("raw network APIs outside the gate: $hits", hits.isEmpty())
    }

    @Test fun theRemoteChatRouterIsGatedAndStrictModeIsFailClosedUntilInitialised() {
        val router = File(root, "ai/AiRouter.kt").readText()
        assertEquals(2, Regex("strictOffline\\(").findAll(router).count() - 1) // two call sites besides the definition
        val gate = File(root, "net/OfflineGate.kt").readText()
        assertTrue("if (!ready) OfflineMode.STRICT_OFFLINE" in gate)
        assertTrue("OfflineGate.initialise" in File(root, "JarvisApplication.kt").readText())
        assertTrue("OfflineGate.allows" in File(root, "translate/TranslationModelManager.kt").readText())
    }

    @Test fun theRuntimeVersionConstantMatchesTheVersionCatalogue() {
        val toml = listOf(File("../gradle/libs.versions.toml"), File("gradle/libs.versions.toml")).first { it.isFile }.readText()
        val v = Regex("""(?m)^litertlm\s*=\s*"([^"]+)"""").find(toml)!!.groupValues[1]
        assertTrue("ModelProfileStore.LITERTLM_VERSION must equal $v", ("LITERTLM_VERSION = \"$v\"") in File(root, "llm/ModelProfileStore.kt").readText())
    }

    @Test fun theTokenLedgerIsPerRequestAndNotAGlobal() {
        val c = File(root, "audio/SessionCoordinator.kt").readText()
        assertTrue("TokenLedger(java.util.UUID.randomUUID().toString())" in c && "TokenLedgerContext(tokenLedger)" in c)
        assertFalse("lastMeasuredTokens" in c)
    }

    @Test fun loadingIsSerialisedAndNeverForcesGcOrTouchesModelFilesOnFailure() {
        val e = File(root, "llm/LitertLmEngine.kt").readText()
        assertTrue("loadMutex.withLock { loadLocked(modelPath, modelName, force = false) }" in e)
        assertFalse("System.gc" in e || "Runtime.getRuntime().gc" in e)
        assertFalse("delete()" in e && "File(modelPath).delete" in e)
        assertTrue("profiles.noteInference(" in e && "profiles.noteLoad(" in e)
    }
}
