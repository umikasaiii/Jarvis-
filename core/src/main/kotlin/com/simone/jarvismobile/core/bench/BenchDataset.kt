package com.simone.jarvismobile.core.bench

import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** Loads and VALIDATES the dataset; a malformed or inconsistent set is refused with the list of problems, never partly used. */
object BenchDataset {
    private val json = Json { ignoreUnknownKeys = true }
    const val RESOURCE_CASES = "/bench/bench_v1_cases.json"
    const val RESOURCE_FIXTURES = "/bench/bench_v1_fixtures.json"

    class Problems(val list: List<String>) : IllegalArgumentException(list.take(20).joinToString("; ") + if (list.size > 20) " (+${list.size - 20})" else "")

    fun parseCases(text: String): BenchDatasetFile = json.decodeFromString(BenchDatasetFile.serializer(), text)
    fun parseFixtures(text: String): BenchFixtures = json.decodeFromString(BenchFixtures.serializer(), text)

    fun loadBundled(): Pair<BenchDatasetFile, BenchFixtures> {
        fun res(p: String) = BenchDataset::class.java.getResourceAsStream(p)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("missing resource $p")
        return parseCases(res(RESOURCE_CASES)) to parseFixtures(res(RESOURCE_FIXTURES))
    }

    /** Fingerprint of the bundled dataset + fixtures TEXT (any edit of a case or a fixture changes it). */
    fun bundledFingerprint(): String {
        fun res(p: String) = BenchDataset::class.java.getResourceAsStream(p)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("missing resource $p")
        return fingerprint(res(RESOURCE_CASES), res(RESOURCE_FIXTURES))
    }

    /** SHA-256 of the dataset text: recorded in every report so a result names the exact set it was measured on. */
    fun fingerprint(vararg texts: String): String =
        MessageDigest.getInstance("SHA-256").digest(texts.joinToString("\u0001").toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    fun validate(d: BenchDatasetFile, f: BenchFixtures, requireQuota: Boolean = true): List<String> {
        val p = ArrayList<String>()
        if (d.fixturesVersion != f.version) p += "fixtures version ${f.version} != dataset's ${d.fixturesVersion}"
        val ids = d.cases.map { it.id }
        ids.groupBy { it }.filter { it.value.size > 1 }.keys.forEach { p += "duplicate id $it" }
        if (requireQuota) {
            if (d.cases.size != 150) p += "expected 150 cases, found ${d.cases.size}"
            BenchCategories.QUOTA.forEach { (c, n) -> val have = d.cases.count { it.category == c }; if (have != n) p += "category $c: expected $n, found $have" }
            val dev = d.cases.count { it.split == "DEV" }; val res = d.cases.count { it.split == "RESERVED" }
            if (dev != 100 || res != 50) p += "split must be 100 DEV / 50 RESERVED, found $dev/$res"
        }
        val articleIds = f.articles.map { it.id }.toSet(); val memIds = f.memory.map { it.id }.toSet(); val docIds = f.documents.map { it.id }.toSet()
        val passages = f.articles.flatMap { a -> a.sections.map { s -> a.id + "#" + s.name.ifEmpty { "lead" } } }.toSet()
        for (c in d.cases) {
            fun bad(m: String) { p += "${c.id}: $m" }
            if (c.category !in BenchCategories.QUOTA) bad("unknown category ${c.category}")
            if (c.criterion !in BenchCategories.CRITERIA) bad("unknown criterion ${c.criterion}")
            if (c.split != "DEV" && c.split != "RESERVED") bad("bad split ${c.split}")
            if (c.turns.isEmpty() || c.turns.any { it.isBlank() }) bad("empty turn")
            if (c.source.isBlank() || c.sourceVersion.isBlank()) bad("source/version missing")
            if (c.risk !in setOf("LOW", "MEDIUM", "HIGH")) bad("bad risk")
            c.context.memory.filter { it !in memIds }.forEach { bad("unknown memory fixture $it") }
            c.context.documents.filter { it !in docIds }.forEach { bad("unknown document fixture $it") }
            c.context.articles.filter { it !in articleIds }.forEach { bad("unknown article fixture $it") }
            (c.supportingEvidence + c.expectedCitations).filter { it.startsWith("doc:").not() && it.startsWith("mem:").not() && it !in passages }.forEach { bad("unknown passage $it") }
            when (c.criterion) {
                "KNOWLEDGE_ANSWER" -> { if (c.requiredClaims.isEmpty()) bad("no required claim"); if (c.expectedAnswer.isNullOrBlank()) bad("no expected answer"); if (c.expectedCitations.isEmpty()) bad("no expected citation") }
                "ABSTAIN" -> { if (c.noSourceBehavior != "ABSTAIN" && c.noSourceBehavior != "QUALIFY") bad("ABSTAIN case needs noSourceBehavior"); if (c.forbiddenClaims.isEmpty()) bad("no forbidden claim") }
                "CONFLICT" -> if (c.requiredClaims.size < 2) bad("a conflict case needs both values as required claims")
                "TOOL_NUMERIC" -> { if (c.numeric == null) bad("numeric gold missing"); if (c.expectedActions.isEmpty()) bad("expected action missing") }
                "REASONING_EXACT" -> if (c.requiredClaims.isEmpty() && c.numeric == null) bad("no gold")
                "CONVERSATION_CONTINUITY" -> if (c.turns.size < 2) bad("a continuity case needs >= 2 turns")
            }
            if (!c.requiresLlm && !c.requiresAndroidTools && c.criterion != "TOOL_NUMERIC" && c.control?.faithful.isNullOrBlank()) bad("model-free case without a faithful control script")
            if (c.criterion == "KNOWLEDGE_ANSWER" && c.control?.hallucinated != null) {
                val faithful = c.control.faithful.orEmpty(); val bad = c.control.hallucinated
                if (faithful == bad) bad("hallucinated candidate equals the faithful one")
            }
        }
        return p
    }

    fun requireValid(d: BenchDatasetFile, f: BenchFixtures, requireQuota: Boolean = true) { val p = validate(d, f, requireQuota); if (p.isNotEmpty()) throw Problems(p) }

    /**
     * A RESERVED set supplied later by the user or an independent evaluator. It is validated like the bundled one (without the 150 quota) and every case is
     * stamped blind (observed=false) only here; the original text's fingerprint is returned so a report can say which sealed set was used. The bundled
     * RESERVED split is NOT blind: its author read it.
     */
    class ImportedReserved(val cases: List<BenchCase>, val fingerprint: String)

    fun importReserved(casesJson: String, fixtures: BenchFixtures): ImportedReserved {
        val d = parseCases(casesJson)
        val sealed = d.copy(cases = d.cases.map { it.copy(split = "RESERVED", observed = false) })
        requireValid(sealed, fixtures.copy(version = d.fixturesVersion), requireQuota = false)
        return ImportedReserved(sealed.cases, fingerprint(casesJson))
    }
}
