package com.simone.jarvismobile.tools

import com.simone.jarvismobile.core.knowledge.Evidence
import com.simone.jarvismobile.core.tools.SensitivityLevel
import com.simone.jarvismobile.core.tools.Tool
import com.simone.jarvismobile.core.tools.ToolPolicy
import com.simone.jarvismobile.core.tools.ToolResult
import com.simone.jarvismobile.knowledge.KnowledgeRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Looks something up in the offline library and reports what it found, quoting
 * the source.
 *
 * Deterministic on purpose: the passages come from the files, so a citation can
 * never be invented. When nothing in the library is relevant the tool says
 * exactly that — an honest "non lo so" is the right answer, and it is what
 * prevents the confident nonsense the model produced before ("€80", a doctor's
 * appointment that never existed).
 */
class SearchKnowledgeTool(private val knowledge: KnowledgeRepository) : Tool {
    override val name = "search_knowledge"
    override val description = "Cerca una risposta nelle guide e nei documenti offline importati."
    override val policy = ToolPolicy.READ_ONLY
    override val sensitivity = SensitivityLevel.PUBLIC
    override val requiresNetwork = false
    override val timeoutMs = 5_000L

    override fun validate(arguments: JsonObject): String? {
        val q = runCatching { arguments["query"]?.jsonPrimitive?.content }.getOrNull()
        if (q.isNullOrBlank()) return "manca il campo 'query'"
        return null
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val query = runCatching { arguments["query"]?.jsonPrimitive?.content }.getOrNull()
            ?: return ToolResult.Failure("missing_query")

        if (!knowledge.isConfigured()) {
            return ok(
                "spoken" to "Non ho ancora una libreria offline. " +
                    "Aggiungi una cartella di guide in Impostazioni › Conoscenza.",
            )
        }
        knowledge.ensureBuilt()

        return when (val evidence = knowledge.find(query)) {
            is Evidence.NoEvidence -> ok(
                "found" to "false",
                "spoken" to "Nelle mie guide offline non trovo niente su questo. " +
                    "Preferisco dirtelo piuttosto che inventare una risposta.",
            )

            is Evidence.Grounded -> {
                val first = evidence.passages.first()
                val sources = evidence.passages.joinToString("; ") { it.chunk.citation }
                ok(
                    "found" to "true",
                    "passages" to evidence.passages.joinToString("\n\n") {
                        "[${it.chunk.citation}] ${it.chunk.text}"
                    },
                    "sources" to sources,
                    "spoken" to "${first.chunk.text.take(600)} (fonte: ${first.chunk.citation})",
                )
            }
        }
    }

    private fun ok(vararg pairs: Pair<String, String>): ToolResult =
        ToolResult.Success(JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) }))
}
