package com.simone.jarvismobile.core.semantic.embedding

import com.simone.jarvismobile.core.semantic.ReferenceMode
import com.simone.jarvismobile.core.semantic.SemanticIntent
import com.simone.jarvismobile.core.semantic.SemanticOperation
import com.simone.jarvismobile.core.tools.ToolFamily
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One labeled example sentence the prototype classifier learns from — § FASE
 * 2A.11 §4/§12: "usa frasi semanticamente complete come esempi/prototipi...
 * NON un secondo keyword matcher". [domains] is multi-label by design (§5):
 * a single sentence like "Considerando come ho dormito e gli impegni di
 * domani..." carries BOTH HEALTH and AGENDA at once, never forced to pick one.
 */
data class SemanticPrototype(
    val text: String,
    val intent: SemanticIntent,
    val domains: Set<ToolFamily>,
    val operation: SemanticOperation = SemanticOperation.UNKNOWN,
    val referenceMode: ReferenceMode = ReferenceMode.NONE,
    /** "train"/"validation"/"test" — enforced by [PrototypeCorpus.assertNoSplitLeakage]. */
    val split: String = "train",
)

/** The whole labeled dataset this project ships — see `core/src/main/resources/semantic/prototypes.json`. */
data class PrototypeCorpus(val prototypes: List<SemanticPrototype>) {

    /** Only the prototypes actually used to build runtime centroids — never validation/test. */
    fun trainingPrototypes(): List<SemanticPrototype> = prototypes.filter { it.split == "train" }

    fun bySplit(split: String): List<SemanticPrototype> = prototypes.filter { it.split == split }

    /**
     * § FASE 2A.11 §12 — "split train/validation/test deve impedire leakage
     * di parafrasi quasi uguali". A cheap, honest proxy for near-duplicate
     * leakage: no two prototypes in DIFFERENT splits may share the exact same
     * normalized text (a real embedding-similarity leakage check needs the
     * real model and lives in `tools/semantic_classifier/dataset.py`
     * instead — this is the part provable without it).
     */
    fun assertNoExactDuplicateAcrossSplits() {
        val bySplit = prototypes.groupBy { it.split }
        val splits = bySplit.keys.toList()
        for (i in splits.indices) {
            for (j in i + 1 until splits.size) {
                val a = bySplit.getValue(splits[i]).map { normalize(it.text) }.toSet()
                val b = bySplit.getValue(splits[j]).map { normalize(it.text) }.toSet()
                val overlap = a intersect b
                check(overlap.isEmpty()) {
                    "exact-duplicate text leaks between splits '${splits[i]}' and '${splits[j]}': $overlap"
                }
            }
        }
    }

    private fun normalize(text: String): String = text.trim().lowercase()
}

// --- JSON codec (kotlinx.serialization DTOs, mapped to/from the domain types above) ---

@Serializable
private data class PrototypeDto(
    val text: String,
    val intent: String,
    val domains: List<String>,
    val operation: String = "UNKNOWN",
    @SerialName("referenceMode") val referenceMode: String = "NONE",
    val split: String = "train",
)

@Serializable
private data class CorpusDto(val prototypes: List<PrototypeDto>)

/**
 * § FASE 2A.11 §2/§12 — the corpus lives in a structured JSON asset, never
 * scattered across Kotlin `if`/`when` branches. This is the single parser
 * both `:core` tests and the real Android classifier use (loaded from a
 * classpath resource on both sides — see
 * `app/engine/semantic/EmbeddingSemanticClassifier`'s own doc comment for why
 * that is safe on Android without a second copy of the file).
 */
object PrototypeCorpusCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(source: String): PrototypeCorpus {
        val dto = json.decodeFromString(CorpusDto.serializer(), source)
        val prototypes = dto.prototypes.map { p ->
            SemanticPrototype(
                text = p.text,
                intent = SemanticIntent.valueOf(p.intent),
                domains = p.domains.map { ToolFamily.valueOf(it) }.toSet(),
                operation = SemanticOperation.valueOf(p.operation),
                referenceMode = ReferenceMode.valueOf(p.referenceMode),
                split = p.split,
            )
        }
        return PrototypeCorpus(prototypes)
    }
}
