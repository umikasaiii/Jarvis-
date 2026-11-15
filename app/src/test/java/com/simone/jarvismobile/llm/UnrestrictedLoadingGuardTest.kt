package com.simone.jarvismobile.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level guards for "unrestricted local model loading": the memory estimate informs and never forbids, every load goes
 * through one serialised loader, a durable marker is written before native initialisation, and nothing that failed deletes
 * anything. They pin the wiring; they do NOT prove that a 4B model loads — that needs the real file on the Honor 200.
 */
class UnrestrictedLoadingGuardTest {
    private val root: File = listOf(File("src/main/java/com/simone/jarvismobile"), File("app/src/main/java/com/simone/jarvismobile"))
        .firstOrNull { it.isDirectory } ?: error("app sources not found")
    private fun src(rel: String) = File(root, rel).readText()
    private fun all() = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test fun theEngineNeverRefusesOnTheMemoryEstimate() {
        val e = src("llm/LitertLmEngine.kt")
        assertFalse("InsufficientMemoryException" in e)
        assertFalse(Regex("""advice\.level\s*==\s*LoadAdviceLevel\.INSUFFICIENT\)\s*throw""").containsMatchIn(e))
        assertTrue("ModelLoadAdvisor.assess" in e) // still computed, for the report
        assertTrue("e.initialize()" in e) // the real attempt
    }

    @Test fun aDurableMarkerIsWrittenBeforeNativeInitialisationAndClearedAfterwards() {
        val e = src("llm/LitertLmEngine.kt")
        val begin = e.indexOf("loadGuard.guard.begin(")
        val init = e.indexOf("e.initialize()")
        assertTrue("marker must precede the native call", begin in 1 until init)
        assertEquals("finish after success and after a caught failure", 2, Regex("""loadGuard\.guard\.finish\(""").findAll(e).count())
        val g = src("llm/ModelLoadGuard.kt")
        assertTrue("e.commit()" in g, "synchronous, durable write")
        assertFalse("apply()" in g)
    }

    @Test fun everyLoadGoesThroughTheSingleSerialisedLoader() {
        val offenders = all().filter { it.name != "ModelLoader.kt" && it.name != "LitertLmEngine.kt" && it.name != "LlmEngine.kt" && it.name != "SemanticEmbeddingEngine.kt" && it.name != "EmbeddingGemmaEngine.kt" }
            .filter { Regex("""(?:fast|advanced|classifier|llm)\.load\(""").containsMatchIn(it.readText()) }.map { it.name }
        assertTrue("direct engine loads outside ModelLoader: $offenders", offenders.isEmpty())
        val l = src("llm/ModelLoader.kt")
        assertTrue("Mutex()" in l && "gate.withLock" in l)
        assertTrue("unloadAndAwait" in l, "the previous model is released and awaited before the next")
    }

    @Test fun startupNeverReloadsAnInterruptedModelAndNeverAutoLoadsInsufficientUnlessExperimental() {
        val l = src("llm/ModelLoader.kt")
        assertTrue("autoLoadAllowed(path)" in l && "ModelLoadPolicy.autoLoadAllowed" in l)
        val c = src("audio/SessionCoordinator.kt")
        assertEquals(3, Regex("""modelLoader\.autoLoad\(""").findAll(c).count())
    }

    @Test fun thereIsAnExplicitWayToAttemptAndAPersistentPreference() {
        assertTrue("Carica comunque" in src("ui/models/ModelsScreen.kt"))
        assertTrue("Consenti caricamento sperimentale di modelli pesanti" in src("ui/models/ModelsScreen.kt"))
        assertTrue("experimentalHeavyModels" in src("data/SettingsRepository.kt"))
        assertFalse("sealed interface Result" in src("llm/ModelLoader.kt") && "Refused" in src("llm/ModelLoader.kt"), "no refusal outcome exists")
    }

    @Test fun aFailedLoadNeverDeletesAnything() {
        val loaderAndEngine = src("llm/ModelLoader.kt") + src("llm/LitertLmEngine.kt") + src("llm/ModelLoadGuard.kt")
        assertFalse(Regex("""\.delete\(|deleteModel|clearActiveModel|clearAdvancedModel|clearClassifierModel|deleteDatabase|deleteRecursively""").containsMatchIn(loaderAndEngine))
        // the Models screen keeps deletion as the user's own, explicit button
        assertTrue("viewModel.delete(model)" in src("ui/models/ModelsScreen.kt"))
    }

    @Test fun theMemoryProbeDoesNotUseRootPrivateApisOrRepeatedGc() {
        val m = src("llm/DeviceMemory.kt") + src("llm/ModelLoader.kt") + src("llm/LitertLmEngine.kt")
        assertFalse(Regex("""System\.gc\(|Runtime\.getRuntime\(\)\.gc\(|su -c|/proc/sys|setprop|hidden|reflection|getDeclaredMethod""", RegexOption.IGNORE_CASE).containsMatchIn(m))
    }
}
