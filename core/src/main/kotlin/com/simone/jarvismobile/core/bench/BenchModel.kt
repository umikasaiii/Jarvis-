package com.simone.jarvismobile.core.bench

import kotlinx.serialization.Serializable

/**
 * M4 — the evaluation dataset. A case says WHAT is asked, WHAT counts as correct (gold: claims, numbers, evidence, citations, actions) and HOW it is
 * judged (criterion). The dataset is data: no case contains a real user's memory, document or name; every personal-looking item is a synthetic fixture.
 */
@Serializable
data class BenchCase(
    val id: String,
    val category: String,
    val tags: List<String> = emptyList(),
    /** The user turns, in order. A single-turn case has one; multi-turn cases keep their context deliberately. */
    val turns: List<String>,
    val context: BenchContext = BenchContext(),
    /** Requested mode: OFFLINE_STRICT (default) or EXPLICIT_ONLINE (only if a case needs an authorised integration). */
    val mode: String = "OFFLINE_STRICT",
    val source: String,
    val sourceVersion: String,
    val expectedAnswer: String? = null,
    /** Each entry must appear in the published answer (after normalisation; numbers compared as numbers). Any [acceptableVariants] counts as the same claim. */
    val requiredClaims: List<String> = emptyList(),
    val forbiddenClaims: List<String> = emptyList(),
    /** Fixture passage ids ("article#section") that must be among the retrieved/supporting evidence. */
    val supportingEvidence: List<String> = emptyList(),
    /** Passage ids the published citations may point to; a citation to any other passage (even a real one) is invalid. */
    val expectedCitations: List<String> = emptyList(),
    val expectedActions: List<ExpectedAction> = emptyList(),
    val forbiddenActions: List<String> = emptyList(),
    /** What the system must do when the sources do not contain the answer: ABSTAIN, QUALIFY, or NA when sources do contain it. */
    val noSourceBehavior: String = "NA",
    val acceptableVariants: List<String> = emptyList(),
    val risk: String = "LOW",
    /** KNOWLEDGE_ANSWER, ABSTAIN, CONFLICT, TOOL_NUMERIC, REASONING_EXACT, CONVERSATION_CONTINUITY, HUMAN_REVIEW. */
    val criterion: String,
    val numeric: NumericGold? = null,
    /** DEV (regression) or RESERVED (final evaluation). */
    val split: String,
    /** True when the author of the software has seen the case while developing. A case is blind only if this is false AND it came from [BenchDataset.importReserved]. */
    val observed: Boolean = true,
    /** The case can only be judged on a real model's generation (reasoning, conversation): not executable by the model-free pipeline control. */
    val requiresLlm: Boolean = false,
    val requiresAndroidTools: Boolean = false,
    val humanReview: Boolean = false,
    /** Scripted candidates for the model-free PIPELINE CONTROL (never gold, never a model result). */
    val control: ControlScript? = null,
)

@Serializable data class BenchContext(
    val memory: List<String> = emptyList(),
    val documents: List<String> = emptyList(),
    /** Articles the case is about (for the reader); retrieval always searches the whole fixture corpus. */
    val articles: List<String> = emptyList(),
    val priorState: String? = null,
)

@Serializable data class ExpectedAction(val tool: String, val args: Map<String, String> = emptyMap())

@Serializable data class NumericGold(val value: Double, val tolerance: Double, /** "abs" or "rel" */ val kind: String = "abs", val unit: String? = null)

/** Candidate answers used by the model-free control: `faithful` restates the gold; `hallucinated` states a forbidden value (used to prove the gate holds). */
@Serializable data class ControlScript(val faithful: String? = null, val hallucinated: String? = null)

@Serializable data class FixtureSection(val name: String = "", val text: String)
@Serializable data class FixtureArticle(val id: String, val title: String, val collection: String = "Fixture Enciclopedia A", val date: String = "2026-01-01", val sections: List<FixtureSection>)
@Serializable data class FixtureMemory(val id: String, val type: String, val text: String, val supersedes: String? = null, val kind: String = "fact")
@Serializable data class FixtureDocPage(val page: Int, val text: String)
@Serializable data class FixtureDocument(val id: String, val title: String, val pages: List<FixtureDocPage>)

@Serializable data class BenchFixtures(
    val version: String,
    val articles: List<FixtureArticle> = emptyList(),
    val memory: List<FixtureMemory> = emptyList(),
    val documents: List<FixtureDocument> = emptyList(),
) {
    fun passageId(article: FixtureArticle, sectionIndex: Int) = article.id + "#" + article.sections[sectionIndex].name.ifEmpty { "lead" }
    fun passageText(id: String): String? {
        val a = articles.firstOrNull { id.startsWith(it.id + "#") } ?: return null
        val sec = id.removePrefix(a.id + "#")
        return a.sections.firstOrNull { it.name.ifEmpty { "lead" } == sec }?.text
    }
}

@Serializable data class BenchDatasetFile(val version: String, val fixturesVersion: String, val cases: List<BenchCase>)

object BenchCategories {
    /** The required distribution of the 150-case set (key → number of cases). */
    val QUOTA: Map<String, Int> = linkedMapOf(
        "geografia" to 12, "scienze" to 12, "medicina" to 10, "informatica_cyber" to 12, "definizioni" to 10, "ragionamento" to 14,
        "calcoli_strumenti" to 12, "conversazione" to 12, "memoria" to 12, "documenti" to 14, "assenti" to 10, "contraddittorie" to 8,
        "ortografia" to 6, "indirette" to 6,
    )
    val CRITERIA = setOf("KNOWLEDGE_ANSWER", "ABSTAIN", "CONFLICT", "TOOL_NUMERIC", "REASONING_EXACT", "CONVERSATION_CONTINUITY", "HUMAN_REVIEW")
}
