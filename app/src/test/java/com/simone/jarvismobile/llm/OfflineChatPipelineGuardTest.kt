package com.simone.jarvismobile.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * BETA 2/8 — source-level guards for the offline local-AI chat pipeline (no Android runtime needed).
 * They prove what the code CANNOT do (reach the network from the chat path) and pin the wiring of the
 * safety fixes, so a later refactor cannot silently remove them. They do NOT prove inference works:
 * that needs a real `.litertlm` model on a device.
 */
class OfflineChatPipelineGuardTest {

    private val root: File = listOf(File("src/main/java/com/simone/jarvismobile"), File("app/src/main/java/com/simone/jarvismobile"))
        .firstOrNull { it.isDirectory } ?: error("app sources not found from ${File(".").absolutePath}")

    private fun src(rel: String) = File(root, rel).also { check(it.isFile) { "missing $rel" } }.readText()

    private fun kotlinFiles(vararg dirs: String): List<File> =
        dirs.flatMap { d -> File(root, d).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    private val networkApi = Regex("""okhttp3|HttpURLConnection|java\.net\.URL\b|URLConnection|OkHttpClient|retrofit|DownloadManager|WebView""", RegexOption.IGNORE_CASE)

    @Test fun theChatPathContainsNoNetworkApi() {
        // Local engine, model management, queue/worker, conversation engine, memory, documents, tools, chat UI.
        val files = kotlinFiles("llm", "engine", "background", "memory", "document", "knowledge", "tools", "ui/home", "ui/models", "ui/memory", "ui/archive")
        assertTrue(files.size > 20)
        val offenders = files.filter { networkApi.containsMatchIn(it.readText()) }.map { it.name }
        assertTrue("network API reachable from the local chat pipeline: $offenders", offenders.isEmpty())
    }

    @Test fun modelsAreNeverDownloadedByTheApp() {
        val vm = src("ui/models/ModelsViewModel.kt") + src("llm/ModelManager.kt")
        assertFalse("http" in vm.lowercase().replace("https://developer", ""))
        assertTrue("importModel" in vm) // import from a user-chosen file, never a fetch
    }

    @Test fun remoteCoreIsOffByDefault() {
        val s = src("data/SettingsRepository.kt")
        assertTrue(Regex("""REMOTE_AI_ENABLED\]\s*\?:\s*false""").containsMatchIn(s))
        assertTrue(Regex("""CORE_ENABLED\]\s*\?:\s*false""").containsMatchIn(s))
    }

    @Test fun aRemoteFailureFallsBackToTheLocalModelInsteadOfFailingTheTurn() {
        val c = src("audio/SessionCoordinator.kt")
        // tryRemoteChat(...)?.let { return it } — null means "not answered remotely": the local engine runs next.
        assertTrue("tryRemoteChat(message, system, needsReasoning)?.let { return it }" in c)
        assertTrue("router.chat(message, system, slot)" in c)
    }

    @Test fun engineSerializesLoadUnloadWithGeneration() {
        val e = src("llm/LitertLmEngine.kt")
        assertTrue("private val loadMutex = Mutex()" in e)
        assertTrue("loadMutex.withLock" in e)
        // HOTFIX 3: generation/reset/close are serialised by the tested core InferenceSlot, not by ad-hoc mutexes.
        assertTrue("InferenceSlot(" in e)
        assertTrue("old.awaitClosed(UNWIND_TIMEOUT_MS)" in e) // a retired engine is closed by the slot, never under use
        assertTrue("ModelLoadAdvisor.assess" in e)
    }

    @Test fun reloadingTheSameFileIsANoOp() {
        assertTrue("loadedPath == modelPath" in src("llm/LitertLmEngine.kt"))
    }

    @Test fun importIsAtomicAndNeverOverwritesALoadedModel() {
        val m = src("llm/ModelManager.kt")
        assertTrue(".importing" in m && "renameTo(target)" in m)
        assertTrue("ModelImportPolicy.validate" in m)
        val vm = src("ui/models/ModelsViewModel.kt")
        assertTrue("importModel(uri, inUse)" in vm)
        assertTrue("llm.unload(); settings.clearActiveModel()" in vm) // delete releases the slot first
    }

    @Test fun queueRejectsDuplicatesAndKeepsArrivalOrder() {
        val q = src("background/AssistantTaskQueue.kt")
        assertTrue("EnqueueGuard.decide" in q && "enqueueMutex.withLock" in q)
        val w = src("background/AssistantTaskWorker.kt")
        assertTrue("olderActiveCount" in w)
        assertTrue("reportQueuedFailure" in w) // a final failure is visible in the chat
    }

    @Test fun noModelMessagesComeFromTheResolverAndNeverEchoTheUser() {
        val c = src("audio/SessionCoordinator.kt")
        assertFalse("Ho capito: \$transcript. Carica un modello" in c)
        assertTrue("modelUnavailableMessage()" in c)
        assertTrue("StoredFactQuestion.matches(transcript)" in c)
    }

    @Test fun aQueuedAnswerWaitsForAModelLoadAlreadyInProgress() {
        assertTrue("llm.loadState.first { it != LlmLoadState.LOADING }" in src("audio/SessionCoordinator.kt"))
    }

    @Test fun noModelBinaryIsTrackedInTheRepository() {
        val repo = generateSequence(root.absoluteFile) { it.parentFile }.first { File(it, ".git").exists() || File(it, "gradlew").exists() }
        val bad = repo.walkTopDown().onEnter { it.name != ".git" && it.name != "build" && it.name != ".gradle" }
            .filter { it.isFile && (it.extension == "litertlm" || it.extension == "gguf" || it.extension == "tflite") }
            .map { it.path }.toList()
        assertTrue("model binaries must never be committed: $bad", bad.isEmpty())
    }
}
