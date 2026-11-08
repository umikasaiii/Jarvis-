package com.simone.jarvismobile.document

import com.simone.jarvismobile.archive.PersonalArchiveSearch
import com.simone.jarvismobile.core.document.AnswerMode
import com.simone.jarvismobile.core.document.BlockKind
import com.simone.jarvismobile.core.document.DocRef
import com.simone.jarvismobile.core.document.DocResolution
import com.simone.jarvismobile.core.document.DocumentQueryIntent
import com.simone.jarvismobile.core.document.DocumentRecord
import com.simone.jarvismobile.core.document.DocumentResolver
import com.simone.jarvismobile.core.document.EvidenceBlock
import com.simone.jarvismobile.core.document.EvidenceStrength
import com.simone.jarvismobile.core.document.GroundedPrompt
import com.simone.jarvismobile.core.document.SearchScope
import com.simone.jarvismobile.memory.MemoryIndex
import javax.inject.Inject
import javax.inject.Singleton

/** What the chat should do with a document question. */
sealed interface DocumentQueryOutcome {
    /** A fixed reply (clarifying question, "nothing found", page list). No model call, nothing invented. */
    data class Reply(val text: String) : DocumentQueryOutcome

    /** A grounded prompt for the local model plus the exact blocks it contains (for the source footer). */
    data class Prompt(val prompt: String, val blocks: List<EvidenceBlock>) : DocumentQueryOutcome
}

/**
 * The chat-side orchestration of document questions (BETA 4). It does NOT retrieve by itself:
 * documents go through [DocumentImportManager]'s existing hybrid index, notes through
 * [PersonalArchiveSearch], memory through [MemoryIndex] — it only decides which to ask, resolves
 * which document the user means, and decides between "answer", "ask which" and "not found".
 * Offline only; read-only (it never modifies a document).
 */
@Singleton
class DocumentQueryService @Inject constructor(
    private val documents: DocumentImportManager,
    private val notes: PersonalArchiveSearch,
    private val memory: MemoryIndex,
) {
    suspend fun prepare(intent: DocumentQueryIntent, question: String): DocumentQueryOutcome = when (intent) {
        is DocumentQueryIntent.Search -> search(intent, question)
        is DocumentQueryIntent.Summarize -> summarize(intent, question)
        is DocumentQueryIntent.Compare -> compare(intent, question)
        is DocumentQueryIntent.PageLookup -> pageLookup(intent)
    }

    // --- search -------------------------------------------------------------
    private suspend fun search(i: DocumentQueryIntent.Search, question: String): DocumentQueryOutcome {
        if (i.scope != SearchScope.NOTES) {
            val ready = documents.readyDocuments()
            if (ready.isEmpty() && i.scope == SearchScope.DOCUMENTS) return DocumentQueryOutcome.Reply(NO_DOCUMENTS)
        }
        var only: Set<String>? = null
        val docRef = i.doc // local copy: i.doc comes from another module, so it cannot be smart-cast
        if (docRef != null) {
            when (val r = resolve(docRef)) {
                is Resolved.Reply -> return DocumentQueryOutcome.Reply(r.text)
                is Resolved.One -> {
                    if (i.topic.isBlank()) return DocumentQueryOutcome.Reply("Cosa vuoi sapere dal documento «${r.record.displayName}»?")
                    only = setOf(r.record.id)
                }
            }
        }
        if (i.topic.isBlank()) {
            return DocumentQueryOutcome.Reply(
                if (i.scope == SearchScope.NOTES) "Su quale argomento devo cercare nei tuoi appunti?" else "Su quale argomento devo cercare nei tuoi documenti?",
            )
        }
        val blocks = ArrayList<EvidenceBlock>()
        if (i.scope != SearchScope.NOTES) blocks += documents.evidenceBlocks(i.topic, only, DOC_BLOCKS)
        if (i.scope != SearchScope.DOCUMENTS && docRef == null) blocks += noteAndMemoryBlocks(i.topic)
        if (!EvidenceStrength.sufficient(i.topic, blocks)) {
            val where = when (i.scope) {
                SearchScope.DOCUMENTS -> "nei documenti importati"
                SearchScope.NOTES -> "nei tuoi appunti e nella memoria"
                SearchScope.ALL -> "nei documenti, negli appunti e nella memoria"
            }
            return DocumentQueryOutcome.Reply("Non ho trovato informazioni su «${i.topic}» $where. Non voglio tirare a indovinare.")
        }
        return DocumentQueryOutcome.Prompt(GroundedPrompt.build(question, blocks, AnswerMode.ANSWER), blocks)
    }

    // --- summary -------------------------------------------------------------
    private suspend fun summarize(i: DocumentQueryIntent.Summarize, question: String): DocumentQueryOutcome {
        val record = when (val r = resolve(i.doc)) {
            is Resolved.Reply -> return DocumentQueryOutcome.Reply(r.text)
            is Resolved.One -> r.record
        }
        val (blocks, sample) = documents.sampleBlocks(record.id, SUMMARY_TOKENS)
            ?: return DocumentQueryOutcome.Reply("«${record.displayName}» è pronto ma non ha passaggi indicizzati (l'indicizzazione automatica potrebbe essere disattivata).")
        val note = if (sample.complete) "" else
            "Attenzione: i brani coprono solo una parte del documento (${sample.chunks.size} passaggi su ${sample.total}). Dillo all'inizio del riassunto."
        return DocumentQueryOutcome.Prompt(
            GroundedPrompt.build("Riassumi il documento «${record.displayName}».", blocks, AnswerMode.SUMMARY, note),
            blocks,
        )
    }

    // --- compare -------------------------------------------------------------
    private suspend fun compare(i: DocumentQueryIntent.Compare, question: String): DocumentQueryOutcome {
        val a = when (val r = resolve(i.a)) { is Resolved.Reply -> return DocumentQueryOutcome.Reply(r.text); is Resolved.One -> r.record }
        val b = when (val r = resolve(i.b)) { is Resolved.Reply -> return DocumentQueryOutcome.Reply(r.text); is Resolved.One -> r.record }
        if (a.id == b.id) return DocumentQueryOutcome.Reply("I due nomi indicano lo stesso documento («${a.displayName}»). Dimmi due documenti diversi.")
        val blocks = ArrayList<EvidenceBlock>()
        for (doc in listOf(a, b)) {
            val part = if (i.topic.isNotBlank()) documents.evidenceBlocks(i.topic, setOf(doc.id), COMPARE_BLOCKS)
            else documents.sampleBlocks(doc.id, COMPARE_TOKENS)?.first.orEmpty()
            if (part.isEmpty()) return DocumentQueryOutcome.Reply("Non ho trovato passaggi utili in «${doc.displayName}» per il confronto.")
            blocks += part
        }
        return DocumentQueryOutcome.Prompt(
            GroundedPrompt.build("Confronta «${a.displayName}» e «${b.displayName}».", blocks, AnswerMode.COMPARE),
            blocks,
        )
    }

    // --- page lookup (answered without the model: pure metadata) -----------------
    private suspend fun pageLookup(i: DocumentQueryIntent.PageLookup): DocumentQueryOutcome {
        var only: Set<String>? = null
        val docRef = i.doc
        if (docRef != null) {
            when (val r = resolve(docRef)) {
                is Resolved.Reply -> return DocumentQueryOutcome.Reply(r.text)
                is Resolved.One -> only = setOf(r.record.id)
            }
        } else if (documents.readyDocuments().isEmpty()) {
            return DocumentQueryOutcome.Reply(NO_DOCUMENTS)
        }
        val blocks = documents.evidenceBlocks(i.topic, only, 5)
        if (!EvidenceStrength.sufficient(i.topic, blocks)) {
            return DocumentQueryOutcome.Reply("Non trovo «${i.topic}» nei documenti importati.")
        }
        val paged = blocks.filter { it.page != null }.distinctBy { it.citation }
        if (paged.isEmpty()) {
            val where = blocks.map { it.citation }.distinct().joinToString("; ")
            return DocumentQueryOutcome.Reply("Ne trovo traccia in: $where. Questi documenti non hanno numeri di pagina (solo i PDF li hanno).")
        }
        return DocumentQueryOutcome.Reply("Ne trovo traccia qui:\n" + paged.joinToString("\n") { "• ${it.citation}" })
    }

    // --- helpers --------------------------------------------------------------
    private sealed interface Resolved {
        data class One(val record: DocumentRecord) : Resolved
        data class Reply(val text: String) : Resolved
    }

    private suspend fun resolve(ref: DocRef): Resolved = when (val r = DocumentResolver.resolve(ref, documents.readyDocuments())) {
        is DocResolution.Found -> Resolved.One(r.record)
        is DocResolution.Ambiguous -> Resolved.Reply(
            "Quale documento intendi? Ne trovo più di uno: " + r.candidates.take(5).joinToString(", ") { "«${it.displayName}»" } +
                ". Ripeti il comando col nome completo.",
        )
        DocResolution.NotFound -> Resolved.Reply("Non trovo un documento pronto con quel nome. Controlla l'elenco in Archivio › Documenti.")
        DocResolution.NoDocuments -> Resolved.Reply(NO_DOCUMENTS)
    }

    private suspend fun noteAndMemoryBlocks(topic: String): List<EvidenceBlock> {
        val out = ArrayList<EvidenceBlock>()
        runCatching { notes.search(topic, 3) }.getOrDefault(emptyList()).forEach {
            if (it.snippet.isNotBlank()) out += EvidenceBlock(BlockKind.NOTE, "Appunto: ${it.label}", it.snippet, groupLabel = it.label)
        }
        runCatching { memory.retrieveSmart(topic, 2) }.getOrDefault(emptyList()).forEach {
            out += EvidenceBlock(BlockKind.MEMORY, "Memoria: ${it.chunk.title.ifBlank { "ricordo" }}", it.chunk.text, groupLabel = "Memoria")
        }
        return out
    }

    private companion object {
        const val NO_DOCUMENTS = "Non hai ancora documenti pronti. Importali da Archivio › Documenti e aspetta che risultino «pronti»."
        const val DOC_BLOCKS = 6
        const val COMPARE_BLOCKS = 3
        const val SUMMARY_TOKENS = 1_400
        const val COMPARE_TOKENS = 700
    }
}
