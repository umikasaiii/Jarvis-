package com.simone.jarvismobile.core.bench

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Report generation from REAL recorded scores. A value that was not measured is null / "NOT_MEASURED", never zero. */
object BenchReport {
    class CategoryRow(val category: String, val total: Int, val executed: Int, val passed: Int, val notExecuted: Int) {
        /** null when nothing was executed: a rate over zero cases is not a number. */
        val passRate: Double? get() = if (executed == 0) null else passed.toDouble() / executed
    }

    class Summary(
        val total: Int, val executed: Int, val notExecuted: Int, val passed: Int, val failed: Int, val needsHumanReview: Int,
        val falseProofs: Int, val correctAbstentions: Int, val missedAbstentions: Int, val unneededAbstentions: Int,
        val invalidCitations: Int, val routingErrors: Int, val actionErrors: Int, val p50Ms: Long?, val p95Ms: Long?,
        val categories: List<CategoryRow>, val notExecutedReasons: Map<String, Int>,
    )

    fun summarize(scores: List<CaseScore>, totalCases: Int = scores.size): Summary {
        val ex = scores.filter { it.executed }
        val lat = ex.mapNotNull { it.latencyMs }.sorted()
        fun pct(p: Double) = if (lat.isEmpty()) null else lat[((lat.size - 1) * p).toInt()]
        val abstainCases = ex.filter { it.criterion == "ABSTAIN" }
        val cats = scores.groupBy { it.category }.map { (c, l) -> CategoryRow(c, l.size, l.count { it.executed }, l.count { it.passed == true }, l.count { !it.executed }) }
        return Summary(
            totalCases, ex.size, scores.size - ex.size + (totalCases - scores.size), ex.count { it.passed == true }, ex.count { it.passed == false }, ex.count { it.passed == null },
            ex.count { it.falseProof }, abstainCases.count { it.abstained }, abstainCases.count { !it.abstained },
            ex.count { it.criterion != "ABSTAIN" && it.abstained && it.dims[Dim.FACT] == false },
            ex.count { it.dims[Dim.CITATION_VALID] == false }, ex.count { it.dims[Dim.ROUTING_ACTION] == false }, ex.count { it.dims[Dim.NUMERIC] == false && it.criterion == "TOOL_NUMERIC" || it.dims[Dim.FORBIDDEN_ACTION] == false },
            pct(0.5), pct(0.95), cats, scores.filterNot { it.executed }.groupingBy { it.notExecutedReason ?: "unspecified" }.eachCount(),
        )
    }

    private fun nm(v: Any?) = v?.toString() ?: "NOT_MEASURED"
    private fun rate(r: Double?) = if (r == null) "NOT_MEASURED" else "%.1f%%".format(r * 100)

    fun markdown(id: RunIdentity, scores: List<CaseScore>, totalCases: Int = scores.size): String {
        val s = summarize(scores, totalCases); val p = id.performance
        return buildString {
            appendLine("# JARVIS benchmark report — run ${id.runId}")
            appendLine()
            appendLine("**Kind: ${id.kind}** · environment: `${id.environment}` · offline mode: ${id.offlineMode}")
            if (id.kind == "PIPELINE_CONTROL") appendLine("> PIPELINE CONTROL: scripted candidates through the real retrieval and publication gate. It validates the harness and the gate; it says NOTHING about a model's answer quality.")
            appendLine("> Device verification: **${if (id.deviceRun) "run on a device with a real model (see qualification rules)" else "NO — not a real-device run"}**")
            appendLine()
            appendLine("- commit: ${nm(id.commit)} · build SHA-256: ${nm(id.buildSha256)}")
            appendLine("- dataset ${id.datasetVersion} (fingerprint ${id.datasetFingerprint}), fixtures ${id.fixturesVersion}")
            appendLine("- model: ${nm(id.modelFile)} · SHA-256: ${nm(id.modelSha256)} · runtime ${nm(id.runtime)} ${nm(id.runtimeVersion)} · backend ${nm(id.backend)} · slot ${nm(id.slot)} · window ${nm(id.configuredWindow)}")
            appendLine("- sampling: temperature ${nm(id.temperature)}, topK ${nm(id.topK)}, topP ${nm(id.topP)}")
            appendLine()
            appendLine("## Coverage")
            appendLine("cases in dataset: ${s.total} · executed: ${s.executed} · NOT executed: ${s.notExecuted} · passed: ${s.passed} · failed: ${s.failed} · needs human review: ${s.needsHumanReview}")
            if (s.notExecutedReasons.isNotEmpty()) appendLine("not executed because: " + s.notExecutedReasons.entries.joinToString("; ") { "${it.key} (${it.value})" })
            appendLine()
            appendLine("## Per category")
            appendLine("| category | cases | executed | passed | not executed | pass rate (of executed) |")
            appendLine("|---|---|---|---|---|---|")
            s.categories.forEach { appendLine("| ${it.category} | ${it.total} | ${it.executed} | ${it.passed} | ${it.notExecuted} | ${rate(it.passRate)} |") }
            appendLine()
            appendLine("## Reliability")
            appendLine("- false proofs (a forbidden/unsupported claim published as verified or cited): **${s.falseProofs}**")
            appendLine("- correct abstentions: ${s.correctAbstentions} · missed abstentions: ${s.missedAbstentions} · abstained when an answer existed: ${s.unneededAbstentions}")
            appendLine("- invalid citations (real source that does not support the claim, or none): ${s.invalidCitations}")
            appendLine("- routing errors: ${s.routingErrors} · action/number errors: ${s.actionErrors}")
            appendLine()
            appendLine("## Performance (${id.environment})")
            appendLine("- latency p50 / p95 per case: ${nm(s.p50Ms)} / ${nm(s.p95Ms)} ms${if (id.environment == "jvm") " (JVM, in-memory: NOT comparable with a phone)" else ""}")
            appendLine("- model load: ${nm(p.loadMs)} ms · PSS before/after/peak: ${nm(p.pssBeforeMb)} / ${nm(p.pssAfterMb)} / ${nm(p.peakPssMb)} MB · first token p50: ${nm(p.firstTokenMsP50)} ms")
            appendLine("- prefill / decode tokens per second: ${nm(p.prefillTokensPerSecond)} / ${nm(p.decodeTokensPerSecond)}")
            appendLine("- battery start→end: ${nm(p.batteryStartPct)} → ${nm(p.batteryEndPct)} % · temperature start→end: ${nm(p.temperatureStartC)} → ${nm(p.temperatureEndC)} °C")
            appendLine("- SQLite search p50: ${nm(p.sqliteMsP50)} ms · ZIM reads: ${nm(p.zimReads)} · index bytes: ${nm(p.indexBytes)}")
            appendLine()
            val failed = scores.filter { it.executed && it.passed == false }
            if (failed.isNotEmpty()) { appendLine("## Failed cases"); failed.forEach { appendLine("- ${it.id} (${it.category}): ${it.notes.joinToString(" | ").ifBlank { "dimension failed: " + it.dims.filter { d -> d.value == false }.keys }}") } }
        }
    }

    fun json(id: RunIdentity, scores: List<CaseScore>, totalCases: Int = scores.size): String {
        val s = summarize(scores, totalCases)
        val enc = Json { prettyPrint = true; encodeDefaults = true }
        val obj = buildJsonObject {
            put("format", "jarvis-bench-report-v1")
            put("identity", enc.parseToJsonElement(enc.encodeToString(RunIdentity.serializer(), id)))
            put("summary", buildJsonObject {
                put("total", s.total); put("executed", s.executed); put("notExecuted", s.notExecuted); put("passed", s.passed); put("failed", s.failed)
                put("needsHumanReview", s.needsHumanReview); put("falseProofs", s.falseProofs); put("correctAbstentions", s.correctAbstentions)
                put("missedAbstentions", s.missedAbstentions); put("invalidCitations", s.invalidCitations); put("routingErrors", s.routingErrors)
                put("p50Ms", s.p50Ms?.let { JsonPrimitive(it) } ?: JsonNull); put("p95Ms", s.p95Ms?.let { JsonPrimitive(it) } ?: JsonNull)
            })
            put("categories", JsonArray(s.categories.map { c -> buildJsonObject { put("category", c.category); put("total", c.total); put("executed", c.executed); put("passed", c.passed); put("passRate", c.passRate?.let { JsonPrimitive(it) } ?: JsonNull) } }))
            put("cases", JsonArray(scores.map { enc.parseToJsonElement(enc.encodeToString(StoredScore.serializer(), StoredScore.of(it))) }))
        }
        return enc.encodeToString(JsonObject.serializer(), obj)
    }

    fun csv(scores: List<CaseScore>): String = buildString {
        appendLine("id,category,split,criterion,executed,passed,false_proof,abstained,latency_ms,not_executed_reason")
        scores.forEach { appendLine(listOf(it.id, it.category, it.split, it.criterion, it.executed, it.passed ?: "NOT_JUDGED", it.falseProof, it.abstained, it.latencyMs ?: "NOT_MEASURED", (it.notExecutedReason ?: "").replace(',', ';')).joinToString(",")) }
    }

    /** A dependency-free SVG bar chart of the pass rate per category, drawn only from executed cases (categories with none are labelled, not drawn as 0). */
    fun svgChart(id: RunIdentity, scores: List<CaseScore>): String {
        val rows = summarize(scores).categories
        val h = 26 * rows.size + 50
        return buildString {
            append("""<svg xmlns="http://www.w3.org/2000/svg" width="640" height="$h" font-family="sans-serif" font-size="12">""")
            append("""<text x="8" y="18" font-weight="bold">Pass rate per category — ${id.kind} on ${id.environment}</text>""")
            rows.forEachIndexed { i, r ->
                val y = 36 + i * 26
                append("""<text x="8" y="${y + 14}">${r.category}</text>""")
                val rate = r.passRate
                if (rate == null) append("""<text x="190" y="${y + 14}" fill="#888">NOT_MEASURED (0/${r.total} executed)</text>""")
                else append("""<rect x="190" y="$y" width="${(rate * 360).toInt()}" height="18" fill="#2a7"/><text x="${190 + (rate * 360).toInt() + 6}" y="${y + 14}">${"%.0f".format(rate * 100)}% (${r.passed}/${r.executed})</text>""")
            }
            append("</svg>")
        }
    }
}
