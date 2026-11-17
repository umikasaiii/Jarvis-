package com.simone.jarvismobile.core.bench

import com.simone.jarvismobile.core.storage.AtomicTextFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/** Exactly what a result was measured on. Missing physical measurements stay null (rendered NOT_MEASURED), never 0. */
@Serializable
data class RunIdentity(
    val runId: String,
    /** PIPELINE_CONTROL (scripted candidates through the real retrieval/publication gate: validates the harness and the gate, NOT a model) or MODEL (a real model answered). */
    val kind: String,
    val commit: String? = null,
    val buildSha256: String? = null,
    val datasetFingerprint: String,
    val datasetVersion: String,
    val fixturesVersion: String,
    val offlineMode: String = "STRICT_OFFLINE",
    val modelFile: String? = null,
    val modelSha256: String? = null,
    val runtime: String? = null,
    val runtimeVersion: String? = null,
    val backend: String? = null,
    val slot: String? = null,
    val configuredWindow: Int? = null,
    val temperature: Double? = null,
    val topK: Int? = null,
    val topP: Double? = null,
    /** "jvm" | "emulator" | "device:<coarse hardware key>". Only "device:*" with a real model can ever be DEVICE VERIFIED. */
    val environment: String,
    val startedAt: Long,
    val performance: PerfFacts = PerfFacts(),
) {
    val deviceRun: Boolean get() = kind == "MODEL" && environment.startsWith("device:") && modelSha256 != null
}

@Serializable
data class PerfFacts(
    val loadMs: Long? = null, val pssBeforeMb: Long? = null, val pssAfterMb: Long? = null, val peakPssMb: Long? = null,
    val firstTokenMsP50: Long? = null, val prefillTokensPerSecond: Double? = null, val decodeTokensPerSecond: Double? = null,
    val batteryStartPct: Int? = null, val batteryEndPct: Int? = null, val temperatureStartC: Double? = null, val temperatureEndC: Double? = null,
    val sqliteMsP50: Long? = null, val zimReads: Long? = null, val indexBytes: Long? = null,
)

/** Executes one case against the system under test. Must be STATELESS between independent cases (the runner calls [reset] first); multi-turn cases keep their own context inside one call. */
interface BenchExecutor {
    suspend fun reset(caseId: String) {}
    /** null = this executor cannot judge the case (reported as NOT_EXECUTED with [whyNot]). */
    suspend fun execute(c: BenchCase, fixtures: BenchFixtures): BenchOutcome?
    fun whyNot(c: BenchCase): String = when { c.requiresLlm -> "needs a real model's generation"; c.requiresAndroidTools -> "needs Android tools"; else -> "not supported by this executor" }
}

/** Append-only, resumable store: one atomic file per run; a result is on disk before the next case starts. A damaged line is skipped, never fatal. */
class BenchStore(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private fun file(runId: String) = File(dir, "bench-${runId.filter { it.isLetterOrDigit() || it == '-' }}.jsonl")

    fun save(identity: RunIdentity, scores: List<CaseScore>) {
        dir.mkdirs()
        val lines = ArrayList<String>()
        lines += "H\t" + json.encodeToString(RunIdentity.serializer(), identity)
        scores.forEach { lines += "R\t" + json.encodeToString(StoredScore.serializer(), StoredScore.of(it)) }
        AtomicTextFile(file(identity.runId)).write(lines.joinToString("\n"))
    }

    fun load(runId: String): Pair<RunIdentity, List<CaseScore>>? {
        val text = AtomicTextFile(file(runId)).readOrNull()?.takeIf { it.isNotBlank() } ?: return null
        var id: RunIdentity? = null; val out = ArrayList<CaseScore>()
        for (line in text.lines()) {
            val tab = line.indexOf('\t'); if (tab < 0) continue
            try {
                when (line.substring(0, tab)) {
                    "H" -> id = json.decodeFromString(RunIdentity.serializer(), line.substring(tab + 1))
                    "R" -> out += json.decodeFromString(StoredScore.serializer(), line.substring(tab + 1)).toScore()
                }
            } catch (_: Exception) { /* a damaged line is skipped; the case will simply run again */ }
        }
        return id?.let { it to out }
    }

    fun runs(): List<String> = dir.listFiles { f -> f.name.startsWith("bench-") && f.name.endsWith(".jsonl") }?.map { it.name.removePrefix("bench-").removeSuffix(".jsonl") }?.sorted().orEmpty()
}

@Serializable
data class StoredScore(
    val id: String, val category: String, val split: String, val criterion: String, val executed: Boolean, val why: String? = null,
    val dims: Map<String, Boolean?> = emptyMap(), val passed: Boolean? = null, val notes: List<String> = emptyList(),
    val falseProof: Boolean = false, val abstained: Boolean = false, val latencyMs: Long? = null,
) {
    fun toScore() = CaseScore(id, category, split, criterion, executed, why, dims.mapKeys { Dim.valueOf(it.key) }, passed, notes, falseProof, abstained, latencyMs)
    companion object { fun of(s: CaseScore) = StoredScore(s.id, s.category, s.split, s.criterion, s.executed, s.notExecutedReason, s.dims.mapKeys { it.key.name }, s.passed, s.notes, s.falseProof, s.abstained, s.latencyMs) }
}

class BenchRunner(
    private val dataset: BenchDatasetFile,
    private val fixtures: BenchFixtures,
    private val executor: BenchExecutor,
    private val store: BenchStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Runs [select]ed cases in dataset order (single case, one category, a split or the whole suite). With [resume], cases already stored for this run id are
     * kept and skipped. Results are saved after EVERY case, so an interruption (cancellation, crash) loses at most the case in flight.
     */
    suspend fun run(identity: RunIdentity, select: (BenchCase) -> Boolean = { true }, resume: Boolean = true): List<CaseScore> {
        val done = LinkedHashMap<String, CaseScore>()
        if (resume) store?.load(identity.runId)?.let { (old, scores) ->
            // results of another dataset/fixtures/build must never be mixed into this run
            if (old.datasetFingerprint == identity.datasetFingerprint && old.fixturesVersion == identity.fixturesVersion && old.kind == identity.kind) scores.forEach { done[it.id] = it }
        }
        for (c in dataset.cases.filter(select)) {
            coroutineContext.ensureActive()
            if (c.id in done) continue
            executor.reset(c.id)
            val t0 = clock()
            val score = try {
                val o = executor.execute(c, fixtures)
                if (o == null) BenchScoring.notExecuted(c, executor.whyNot(c)) else BenchScoring.score(c, if (o.latencyMs == null) o.copy(latencyMs = clock() - t0) else o)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                BenchScoring.score(c, BenchOutcome("", "ERROR", error = e.javaClass.simpleName))
            }
            done[c.id] = score
            store?.save(identity, done.values.toList())
        }
        return dataset.cases.filter(select).mapNotNull { done[it.id] }
    }
}
