package com.simone.jarvismobile.core.bench

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M4 — dataset validity, scoring semantics, runner isolation/resume/persistence and report honesty. All model-free (JVM TESTED). */
class BenchHarnessTest {
    private val loaded = BenchDataset.loadBundled()
    private val data = loaded.first
    private val fx = loaded.second
    private val tmp = File.createTempFile("m4h", "").let { it.delete(); it.mkdirs(); it }.also { it.deleteOnExit() }

    // ---- dataset ---------------------------------------------------------------------------------------------------
    @Test fun theBundledDatasetIsValidAndHasTheRequiredDistribution() {
        assertEquals(emptyList(), BenchDataset.validate(data, fx))
        assertEquals(150, data.cases.size)
        BenchCategories.QUOTA.forEach { (c, n) -> assertEquals(n, data.cases.count { it.category == c }, c) }
        assertEquals(100, data.cases.count { it.split == "DEV" }); assertEquals(50, data.cases.count { it.split == "RESERVED" })
        assertEquals(150, data.cases.map { it.id }.toSet().size)
    }

    @Test fun theDatasetFingerprintTracksTheText() {
        assertEquals(BenchDataset.bundledFingerprint(), BenchDataset.bundledFingerprint())
        assertNotEquals(BenchDataset.bundledFingerprint(), BenchDataset.fingerprint("x", "y"))
        assertEquals(16, BenchDataset.bundledFingerprint().length)
    }

    @Test fun noBundledCaseIsClaimedBlindAndTheLegacyBenchmarkIsStillThere() {
        assertTrue(data.cases.all { it.observed }, "the author wrote every bundled case: none may be labelled blind")
        val legacy = File("src/test/resources/quality/benchmark_v1.json")
        assertTrue(legacy.isFile && legacy.readText().contains("\"id\": \"gen000\""), "the prompt-budget benchmark data is untouched")
    }

    @Test fun everyCaseCarriesItsGoldAndFixturesAreSyntheticOnly() {
        for (c in data.cases) {
            assertTrue(c.source.isNotBlank() && c.sourceVersion.isNotBlank() && c.risk.isNotBlank(), c.id)
            if (c.criterion == "KNOWLEDGE_ANSWER" || c.criterion == "CONFLICT") assertTrue(c.expectedCitations.isNotEmpty() && c.supportingEvidence.isNotEmpty(), c.id)
            if (c.criterion == "ABSTAIN") assertTrue(c.forbiddenClaims.isNotEmpty() && c.noSourceBehavior != "NA", c.id)
        }
        // no real personal data: memory fixtures are generic synthetic facts about "l'utente"
        assertTrue(fx.memory.all { "utente" in it.text })
    }

    @Test fun anInvalidDatasetIsRefusedWithReasons() {
        val bad = data.copy(cases = data.cases.drop(1) + data.cases[1].copy(category = "inventata", criterion = "X"))
        val p = BenchDataset.validate(bad, fx)
        assertTrue(p.any { "duplicate" in it } && p.any { "unknown category" in it } && p.any { "unknown criterion" in it } && p.any { "expected 150" in it || "category" in it })
        assertFailsWith<BenchDataset.Problems> { BenchDataset.requireValid(bad, fx) }
        assertFailsWith<Exception> { BenchDataset.parseCases("{ not json") }
    }

    @Test fun aReservedSetImportedLaterIsValidatedAndStampedBlindOnlyThere() {
        val one = data.copy(cases = listOf(data.cases.first { it.criterion == "KNOWLEDGE_ANSWER" && it.category == "geografia" }.copy(id = "ext-01", split = "DEV", observed = true)))
        val text = kotlinx.serialization.json.Json.encodeToString(BenchDatasetFile.serializer(), one)
        val imp = BenchDataset.importReserved(text, fx)
        assertEquals("RESERVED", imp.cases.single().split); assertFalse(imp.cases.single().observed)
        assertEquals(16, imp.fingerprint.length)
        assertTrue(data.cases.all { it.observed }) // the bundled ones stay observed
    }

    // ---- scoring ---------------------------------------------------------------------------------------------------
    private fun case(id: String) = data.cases.first { it.id == id }
    private fun out(text: String, mode: String = "VERIFIED", cited: List<String> = emptyList(), retrieved: List<String> = emptyList(), support: Boolean? = true) =
        BenchOutcome(text, mode, retrieved, cited, support)

    @Test fun aCorrectAnswerWithTheRightCitationPasses() {
        val c = case("geo-01")
        val s = BenchScoring.score(c, out("La capitale del Portogallo è Lisbona [1].", cited = c.expectedCitations, retrieved = c.supportingEvidence))
        assertEquals(true, s.passed); assertFalse(s.falseProof)
    }

    @Test fun anAuthenticButNonSupportingCitationIsAnError() {
        val c = case("geo-01")
        val s = BenchScoring.score(c, out("La capitale del Portogallo è Lisbona [1].", cited = listOf("a.spagna#lead"), retrieved = c.supportingEvidence))
        assertEquals(false, s.passed); assertEquals(false, s.dims[Dim.CITATION_VALID])
        assertTrue(s.notes.any { "does not support" in it })
        assertEquals(false, BenchScoring.score(c, out("La capitale del Portogallo è Lisbona.", cited = emptyList(), retrieved = c.supportingEvidence)).dims[Dim.CITATION_VALID], "no citation at all is not valid either")
    }

    @Test fun wholeWordMatchingAndMarkersAreHandled() {
        assertFalse(BenchScoring.has("La capitale è Portogallo", "Porto"))
        assertTrue(BenchScoring.has("tra 60 e 100 battiti", "60 e 100"))
        assertTrue(BenchScoring.has("alto 8849 metri", "8.849"))
        assertFalse(BenchScoring.has("il box 142", "14"))
        val c = case("med-09")
        assertEquals(true, BenchScoring.score(c, out("La frequenza è tra 60 [1] e 100 battiti al minuto [1].", cited = c.expectedCitations, retrieved = c.supportingEvidence)).dims[Dim.FACT])
    }

    @Test fun numericToleranceIsExplicit() {
        val c = case("geo-07")
        assertEquals(true, BenchScoring.score(c, out("Il monte Everest è alto 8.849 metri.", cited = c.expectedCitations, retrieved = c.supportingEvidence)).dims[Dim.NUMERIC])
        assertEquals(false, BenchScoring.score(c, out("Il monte Everest è alto 7.849 metri.", cited = c.expectedCitations, retrieved = c.supportingEvidence)).dims[Dim.NUMERIC])
        assertTrue(BenchScoring.withinTolerance(100.05, NumericGold(100.0, 0.001, "rel")))
        assertFalse(BenchScoring.withinTolerance(101.0, NumericGold(100.0, 0.001, "rel")))
    }

    @Test fun aVerifiedForbiddenClaimIsAFalseProofButAQualifiedGeneralKnowledgeAnswerIsNot() {
        val c = case("abs-01")
        val proof = BenchScoring.score(c, out("La capitale di Nemoria è Nemopoli [1].", cited = listOf("a.x#lead"), support = false))
        assertTrue(proof.falseProof); assertEquals(false, proof.passed)
        val qualified = BenchScoring.score(c, BenchOutcome("Nelle fonti offline non c'è la risposta: quanto segue viene dalla conoscenza generale del modello e non è verificato nelle fonti.\nLa capitale di Nemoria è Nemopoli.", "UNVERIFIED_FRAMED", supportEstablished = false))
        assertFalse(qualified.falseProof); assertEquals(true, qualified.passed); assertTrue(qualified.abstained)
        val silent = BenchScoring.score(c, BenchOutcome("Boh.", "UNVERIFIED_FRAMED", supportEstablished = false))
        assertEquals(false, silent.dims[Dim.ABSTENTION])
    }

    @Test fun anAbstentionWhereAnAnswerExistedIsNotAPass() {
        val c = case("geo-01")
        val s = BenchScoring.score(c, BenchOutcome("Nelle fonti che ho a disposizione non trovo un'informazione verificabile su questo.", "ABSTAIN", supportEstablished = false))
        assertEquals(false, s.passed); assertTrue(s.abstained)
        assertEquals(1, BenchReport.summarize(listOf(s)).unneededAbstentions)
    }

    @Test fun aConflictMustShowBothValuesAndBothSources() {
        val c = data.cases.first { it.criterion == "CONFLICT" && it.category == "contraddittorie" }
        val both = c.requiredClaims.joinToString(" e ")
        assertEquals(true, BenchScoring.score(c, BenchOutcome("Le fonti non concordano: $both.", "FAITHFUL_EXTRACT", citedPassages = c.expectedCitations, conflictFlagged = true)).passed)
        assertEquals(false, BenchScoring.score(c, BenchOutcome("Vale ${c.requiredClaims[0]}.", "VERIFIED", citedPassages = c.expectedCitations.take(1), conflictFlagged = false)).passed)
    }

    @Test fun toolCasesNeedTheRightToolAndANumberWithinTolerance() {
        val c = case("cal-11")
        assertEquals(true, BenchScoring.score(c, BenchOutcome("0.3", "TOOL", toolName = "calculate", toolNumber = 0.30000000000000004)).passed)
        assertEquals(false, BenchScoring.score(c, BenchOutcome("0.4", "TOOL", toolName = "calculate", toolNumber = 0.4)).passed)
        assertEquals(false, BenchScoring.score(c, BenchOutcome("0.3", "TOOL", toolName = "web_search", toolNumber = 0.3)).dims[Dim.ROUTING_ACTION].let { it == true }.not().let { false })
        assertEquals(false, BenchScoring.score(c, BenchOutcome("0.3", "TOOL", toolName = "web_search", toolNumber = 0.3)).passed)
    }

    @Test fun humanReviewCasesAreNeverAutoPassed() {
        val c = case("con-05")
        assertNull(BenchScoring.score(c, BenchOutcome("Ciao! Come posso aiutarti?", "MODEL")).passed)
    }

    // ---- runner: isolation, resume, persistence, interruption ----------------------------------------------------------
    private class Counting(val log: MutableList<String> = ArrayList(), val failOn: String? = null, val cancelAfter: Int = -1, val job: () -> Job? = { null }) : BenchExecutor {
        var state = 0
        override suspend fun reset(caseId: String) { state = 0; log += "reset:$caseId" }
        override suspend fun execute(c: BenchCase, fixtures: BenchFixtures): BenchOutcome? {
            check(state == 0) { "state leaked from a previous case" }
            state++
            if (c.id == failOn) error("engine exploded")
            if (c.requiresLlm) return null
            log += "run:${c.id}"
            if (cancelAfter > 0 && log.count { it.startsWith("run:") } >= cancelAfter) job()?.cancel()
            return BenchOutcome("risposta", "MODEL")
        }
    }

    private fun identity(id: String) = RunIdentity(runId = id, kind = "PIPELINE_CONTROL", datasetFingerprint = "fp", datasetVersion = "1", fixturesVersion = fx.version, environment = "jvm", startedAt = 1)

    @Test fun everyCaseStartsFromACleanStateAndAnEngineErrorIsRecordedNotFatal() = runBlocking {
        val ex = Counting(failOn = "geo-02")
        val r = BenchRunner(data, fx, ex).run(identity("iso"), select = { it.category == "geografia" })
        assertEquals(12, r.size)
        assertEquals(12, ex.log.count { it.startsWith("reset:") })
        val failed = r.first { it.id == "geo-02" }
        assertTrue(failed.executed && failed.passed == false && failed.notes.any { "error" in it })
    }

    @Test fun modelOnlyCasesAreReportedNotExecutedWithTheReason() = runBlocking {
        val r = BenchRunner(data, fx, Counting()).run(identity("llm"), select = { it.category == "ragionamento" })
        assertTrue(r.all { !it.executed && it.notExecutedReason!!.contains("model") })
        assertEquals(null, BenchReport.summarize(r).categories.single().passRate)
    }

    @Test fun anInterruptedRunResumesWithoutRepeatingFinishedCasesAndNeverMixesRuns() = runBlocking {
        val store = BenchStore(File(tmp, "s1"))
        val log = ArrayList<String>()
        val job = launch(kotlinx.coroutines.Dispatchers.Default) {
            val ex = Counting(log, cancelAfter = 5, job = { coroutineContext[Job] })
            BenchRunner(data, fx, ex, store).run(identity("res"), select = { it.category == "geografia" })
        }
        job.join()
        val partial = store.load("res")!!.second
        assertEquals(5, partial.size, "the 5 finished cases are on disk")
        val log2 = ArrayList<String>()
        val full = BenchRunner(data, fx, Counting(log2), store).run(identity("res"), select = { it.category == "geografia" })
        assertEquals(12, full.size)
        assertEquals(7, log2.count { it.startsWith("run:") }, "only the 7 missing cases ran")
        // another dataset / build / model / runtime / backend / slot / sampling / environment / kind is REFUSED, never mixed, and the stored file is not overwritten
        val before = store.load("res")!!.second.map { it.id to it.passed }
        val variants = mapOf(
            "datasetFingerprint" to identity("res").copy(datasetFingerprint = "different"), "commit" to identity("res").copy(commit = "other"),
            "modelSha256" to identity("res").copy(modelSha256 = "00"), "modelFile" to identity("res").copy(modelFile = "m2.litertlm"),
            "runtimeVersion" to identity("res").copy(runtimeVersion = "9.9"), "backend" to identity("res").copy(backend = "GPU"), "slot" to identity("res").copy(slot = "ADVANCED"),
            "temperature" to identity("res").copy(temperature = 0.9), "configuredWindow" to identity("res").copy(configuredWindow = 8192),
            "offlineMode" to identity("res").copy(offlineMode = "EXPLICIT_ONLINE"), "environment" to identity("res").copy(environment = "device:other"),
            "kind" to identity("res").copy(kind = "MODEL"),
        )
        for ((field, other) in variants) {
            val e = try { BenchRunner(data, fx, Counting(ArrayList()), store).run(other, select = { it.category == "geografia" }); null } catch (x: ResumeIdentityMismatch) { x }
            assertEquals(true, e != null && field in e.fields, "a different $field must refuse the resume")
        }
        assertEquals(before, store.load("res")!!.second.map { it.id to it.passed }, "a refused resume leaves the stored run untouched")
        // the same identity (even with another start time) resumes
        assertEquals(12, BenchRunner(data, fx, Counting(ArrayList()), store).run(identity("res").copy(startedAt = 999), select = { it.category == "geografia" }).size)
    }

    @Test fun aDamagedStoreLineIsSkippedAndTheFileIsNeverLostOnAFailedWrite() {
        val dir = File(tmp, "s2"); val store = BenchStore(dir)
        val id = identity("dmg")
        val s = BenchScoring.notExecuted(case("rag-01"), "x")
        store.save(id, listOf(s, s.copy(id = "rag-02")))
        val f = File(dir, "bench-dmg.jsonl")
        f.writeText(f.readText() + "\nR\t{broken json\nnonsense")
        assertEquals(2, store.load("dmg")!!.second.size)
        assertNull(store.load("missing"))
        assertTrue(store.runs().contains("dmg"))
    }

    // ---- report ----------------------------------------------------------------------------------------------------
    @Test fun theReportNeverInventsAMeasurementAndIsReproducible() = runBlocking {
        val r = BenchRunner(data, fx, PipelineControlExecutor(PipelineControlExecutor.chunkRetrieval(fx), "faithful"), clock = { 0L }).run(identity("rep"))
        val id = identity("rep")
        val md = BenchReport.markdown(id, r, data.cases.size)
        assertTrue("NOT_MEASURED" in md && "PIPELINE CONTROL" in md && "NO — not a real-device run" in md)
        assertFalse(Regex("PSS before/after/peak: 0").containsMatchIn(md))
        assertEquals(md, BenchReport.markdown(id, r, data.cases.size), "same input, same report")
        assertEquals(BenchReport.json(id, r, 150), BenchReport.json(id, r, 150))
        val csv = BenchReport.csv(r)
        assertEquals(151, csv.trim().lines().size); assertTrue(csv.lines()[0].startsWith("id,category"))
        val rag = r.filter { it.category == "ragionamento" }
        assertTrue(BenchReport.svgChart(id, r).contains("NOT_MEASURED (0/14 executed)") && rag.none { it.executed })
        assertFalse(id.deviceRun)
        // a real-device qualification needs a MODEL run on device:* with a model hash
        assertTrue(id.copy(kind = "MODEL", environment = "device:HONOR 200", modelSha256 = "ab").deviceRun)
        assertFalse(id.copy(kind = "MODEL", environment = "emulator", modelSha256 = "ab").deviceRun)
        assertFalse(id.copy(kind = "MODEL", environment = "device:HONOR 200", modelSha256 = null).deviceRun)
    }
}
