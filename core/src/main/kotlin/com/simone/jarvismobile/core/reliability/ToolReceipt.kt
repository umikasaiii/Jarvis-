package com.simone.jarvismobile.core.reliability

import com.simone.jarvismobile.core.knowledge.KnowledgeText
import com.simone.jarvismobile.core.knowledge.SupportJudge

/** What a tool actually reported. A claim of success needs [DONE]; nothing else can authorise one. */
enum class ReceiptStatus { DONE, FAILED, CANCELLED, NEEDS_CONFIRMATION, INFO }

/**
 * RELIABILITY — a tool's result kept as a STRUCTURED receipt (tool, status, the tool's own sentence), not flattened into free text.
 * The model may restate it; it may not turn it into something it does not say (a different property, a success the tool did not
 * report). When a restatement cannot be shown to follow from the receipts, the receipts themselves are rendered, verbatim.
 */
data class ToolReceipt(val tool: String, val status: ReceiptStatus, val text: String, val request: String = "")

object ToolReceipts {
    const val TITLE = "Risultati del sistema"

    private val PARTICIPLES = "impostat\\w+|acces[oiae]|spent[oiae]|avviat\\w+|creat\\w+|aggiunt\\w+|salvat\\w+|cancellat\\w+|eliminat\\w+|inviat\\w+|chiamat\\w+|apert\\w+|chius\\w+|attivat\\w+|disattivat\\w+|programmat\\w+|fissat\\w+|segnat\\w+|annullat\\w+|modificat\\w+|spostat\\w+|completat\\w+|eseguit\\w+|regolat\\w+|abbassat\\w+|alzat\\w+|riprodott\\w+|messo|mess[ai]|fatt[oiae]"
    /** "ho impostato…", "è stato acceso", "l'ho spento": a claim that something was DONE (or, negated, that it could not be). */
    private val DONE_CLAIM = Regex("\\b(?:ho|hai|abbiamo|l ho|la ho|te l ho|e stato|e stata|sono stati|sono state|gia)\\s+(?:gia\\s+|appena\\s+|correttamente\\s+)?(?:$PARTICIPLES)\\b|^(?:fatto|eseguito|completato|impostato|acceso|spento|salvato|annullato)\\b")
    private val FAIL_CLAIM = Regex("\\bnon (?:sono riuscit[oa]|ho potuto|e stato possibile|ci sono riuscit[oa]|posso)\\b")

    fun toPackage(receipts: List<ToolReceipt>, maxChars: Int): EvidencePackage = EvidencePackage.of(
        receipts.mapIndexed { i, r -> EvidencePackage.Companion.Raw(TITLE, r.tool + ": " + label(r.status), r.text.replace('\n', ' ').trim(), "t$i", EvidenceKind.TOOL) },
        maxChars, totalChars = maxChars,
    )

    fun label(s: ReceiptStatus) = when (s) {
        ReceiptStatus.DONE -> "eseguito"; ReceiptStatus.FAILED -> "non riuscito"; ReceiptStatus.CANCELLED -> "annullato"
        ReceiptStatus.NEEDS_CONFIRMATION -> "in attesa di conferma"; ReceiptStatus.INFO -> "informazione"
    }

    /** The receipts as the user may read them when no restatement is provable: the tools' own sentences, in order. */
    fun render(receipts: List<ToolReceipt>): String = receipts.joinToString("\n") { it.text.trim() }

    sealed interface ActionClaim {
        object None : ActionClaim
        /** The clause says an action was carried out. */
        object Done : ActionClaim
        /** The clause says an action could not be carried out. */
        object Failed : ActionClaim
    }

    fun classify(clause: String): ActionClaim {
        val f = KnowledgeText.fold(clause).replace(Regex("[’']"), " ").replace(Regex("\\s+"), " ").trim()
        return when {
            FAIL_CLAIM.containsMatchIn(f) -> ActionClaim.Failed
            DONE_CLAIM.containsMatchIn(f) -> ActionClaim.Done
            else -> ActionClaim.None
        }
    }

    /**
     * Whether the receipts support [claim]. A success claim needs a DONE receipt that is about the same thing (shared content
     * stem with the tool name or its sentence); a bare "Fatto." needs at least one DONE receipt. A failure claim needs a
     * FAILED/CANCELLED receipt. A receipt with another outcome CONTRADICTS the claim, naming the real outcome.
     */
    /** Verbs that mean the same thing; a claim and a receipt must agree on the FAMILY (acceso ≠ spento, creato ≠ cancellato). */
    private val FAMILIES = listOf(
        listOf("impostat", "fissat", "programmat", "regolat"),
        listOf("acces", "attivat", "avviat"),
        listOf("spent", "disattivat"),
        listOf("creat", "aggiunt", "salvat", "segnat", "mess"),
        listOf("cancellat", "eliminat", "annullat"),
        listOf("inviat"), listOf("chiamat"), listOf("apert"), listOf("chius"), listOf("modificat", "spostat"), listOf("riprodott"),
        listOf("abbassat"), listOf("alzat"),
    )

    private fun families(text: String): Set<Int> {
        val f = KnowledgeText.fold(text)
        return FAMILIES.indices.filter { i -> FAMILIES[i].any { f.contains(it) } }.toSet()
    }

    fun check(clause: String, claim: ActionClaim, receipts: List<ToolReceipt>): Pair<ClaimStatus, String> {
        val stems = ClaimVerifier.contentStems(clause).toSet()
        fun about(r: ToolReceipt) = SupportJudge.stems(r.tool.replace('_', ' ') + " " + r.text).any { it in stems }
        val related = receipts.filter { about(it) }.ifEmpty { if (stems.isEmpty()) receipts else emptyList() }
        if (related.isEmpty()) return ClaimStatus.UNVERIFIED to "azione non presente nei risultati degli strumenti"
        return when (claim) {
            ActionClaim.Done -> {
                val claimFamilies = families(clause)
                val done = related.filter { it.status == ReceiptStatus.DONE }
                // the receipt must report the SAME kind of action: "acceso" is not supported by "Torcia spenta"
                val agreeing = done.filter { r -> val rf = families(r.text); claimFamilies.isEmpty() || rf.isEmpty() || rf.any { it in claimFamilies } }
                when {
                    agreeing.isNotEmpty() -> ClaimStatus.EXPLICITLY_SUPPORTED to ""
                    done.isNotEmpty() -> ClaimStatus.CONTRADICTED to "il risultato riporta un'azione diversa: " + done.first().text.take(80)
                    else -> ClaimStatus.CONTRADICTED to "esito reale: " + label(related.first().status)
                }
            }
            ActionClaim.Failed -> when {
                related.any { it.status == ReceiptStatus.FAILED || it.status == ReceiptStatus.CANCELLED } -> ClaimStatus.EXPLICITLY_SUPPORTED to ""
                else -> ClaimStatus.CONTRADICTED to "esito reale: " + label(related.first().status)
            }
            ActionClaim.None -> ClaimStatus.UNKNOWN to ""
        }
    }
}
