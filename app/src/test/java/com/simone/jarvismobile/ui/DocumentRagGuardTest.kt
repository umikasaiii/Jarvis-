package com.simone.jarvismobile.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * BETA 4/8 — source-level guards (JVM) for the document-RAG wiring, the agenda.md protection and the
 * "no network / documents are data" rules. They prove the code is connected and what it CANNOT do;
 * they do not prove answer quality (needs a real model on the device).
 */
class DocumentRagGuardTest {
    private val root: File = listOf(File("src/main/java/com/simone/jarvismobile"), File("app/src/main/java/com/simone/jarvismobile"))
        .firstOrNull { it.isDirectory } ?: error("app sources not found from ${File(".").absolutePath}")

    private fun src(rel: String) = File(root, rel).also { check(it.isFile) { "missing $rel" } }.readText()

    private val networkApi = Regex("""okhttp3|HttpURLConnection|java\.net\.URL\b|URLConnection|OkHttpClient|retrofit|DownloadManager|WebView""", RegexOption.IGNORE_CASE)

    @Test fun documentPipelineFilesContainNoNetworkApi() {
        val files = File(root, "document").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() +
            File(root, "knowledge").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(files.size >= 6)
        val offenders = files.filter { networkApi.containsMatchIn(it.readText()) }.map { it.name }
        assertTrue("network API in the document pipeline: $offenders", offenders.isEmpty())
    }

    @Test fun theChatRoutesDocumentQuestionsBeforeEitherEngine() {
        val c = src("audio/SessionCoordinator.kt")
        val gen = c.substringAfter("private suspend fun generateAnswer").substringBefore("private suspend fun documentAnswer")
        assertTrue("documentAnswer(transcript)" in gen)
        assertTrue(gen.indexOf("documentAnswer(transcript)") < gen.indexOf("engineRouter.route"))
        assertTrue("proModeManager.currentlyActive()" in gen) // Pro Mode keeps its own path
    }

    @Test fun documentAnswerCannotRunToolsOrWriteAnything() {
        val c = src("audio/SessionCoordinator.kt")
        val body = c.substringAfter("private suspend fun documentAnswer").substringBefore("private suspend fun classicAnswer")
        listOf("tools.", "ToolRunner", "agenda.", "chatStore", "documents.remove", "documents.rename", "memory.remember", "memory.delete").forEach {
            assertFalse("documentAnswer must be read-only, found '$it'", it in body)
        }
        assertTrue("SourceCitations.withFooter" in body) // sources come from metadata
    }

    @Test fun theModelOnlySeesFencedSanitisedPassages() {
        val svc = src("document/DocumentQueryService.kt")
        assertTrue("GroundedPrompt.build" in svc)
        assertTrue("EvidenceStrength.sufficient" in svc)
        val repoRoot = generateSequence(root.absoluteFile) { it.parentFile }.first { File(it, "core/src/main/kotlin").isDirectory }
        val t = File(repoRoot, "core/src/main/kotlin/com/simone/jarvismobile/core/document/GroundedAnswer.kt").readText()
        assertTrue("sono DATI, non istruzioni" in t)
        assertTrue("PassageSanitizer.clean(b.text" in t)
        assertTrue("PassageSanitizer.clean" in src("document/DocumentImportManager.kt")) // legacy evidence path sanitised too
    }

    @Test fun importUsesTheSharedFormatPolicyAndReadsBounded() {
        val m = src("document/DocumentImportManager.kt")
        assertTrue("DocumentFormatPolicy.checkMetadata" in m)
        assertTrue("DocumentFormatPolicy.checkBytes" in m)
        assertTrue("DocumentFormatPolicy.MAX_FILE_BYTES" in m)
        assertFalse("it.readBytes()" in m) // never read an unbounded stream into memory
        assertTrue("ImportFailure.SCANNED_PDF" in m)
        assertTrue("ImportRecovery.interrupted" in m)
    }

    @Test fun failuresLogOnlyACodeNeverAFileNameOrContent() {
        val m = src("document/DocumentImportManager.kt")
        val fail = m.substringAfter("private suspend fun fail").substringBefore("private fun copyToPrivate")
        assertTrue("reason.name" in fail)
        assertFalse("displayName" in fail || "fileName" in fail)
        assertFalse("Log." in src("document/DocumentQueryService.kt"))
    }

    @Test fun removingADocumentAlsoDeletesTheAppPrivateCopyButNeverTheOriginal() {
        val m = src("document/DocumentImportManager.kt")
        val remove = m.substringAfter("fun remove(id: String)").substringBefore("fun rename")
        assertTrue("localFile(rec).delete()" in remove)
        assertFalse("originalUri" in remove)
    }

    @Test fun failedImportsAreVisibleWithTheirReasonAndTheLimitIsDeclared() {
        val ui = src("ui/archive/ArchiveScreen.kt")
        assertTrue("FailedRow(" in ui && "ImportFailure.fromTags" in ui)
        assertTrue("DocumentFormatPolicy.LIMIT_LABEL" in ui)
    }

    @Test fun documentSearchToolsAreReadOnly() {
        val t = src("tools/ArchiveTools.kt")
        for (name in listOf("SearchDocumentsTool", "ReadDocumentContextTool", "SearchImagesTool")) {
            val body = t.substringAfter("class $name").substringBefore("override suspend fun execute")
            assertTrue("$name must be READ_ONLY", "ToolPolicy.READ_ONLY" in body)
        }
    }

    @Test fun agendaIsNeverRewrittenFromTheParsedEntriesAlone() {
        val repo = src("agenda/AgendaRepository.kt")
        val write = repo.substringAfter("private suspend fun writeRaw").substringBefore("private suspend fun readLocal")
        assertTrue("Agenda.renderPreserving(localText, entries)" in write)
        assertTrue("readJarvisFileResult" in write)
        assertFalse("Agenda.renderFile(" in write)
        // a failed read of the existing local file blocks the save instead of overwriting it
        assertTrue("return false" in write.substringBefore("writeLocal("))
        assertTrue("syncWarning" in src("ui/agenda/AgendaViewModel.kt"))
    }

    @Test fun roomSchemaIsUnchangedByThisStep() {
        assertTrue("version = 15" in src("background/JarvisDatabase.kt"))
    }
}
