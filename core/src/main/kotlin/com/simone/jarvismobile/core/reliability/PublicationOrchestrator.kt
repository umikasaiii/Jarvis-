package com.simone.jarvismobile.core.reliability

/**
 * RELIABILITY — the sequence every evidence-based answer goes through, shared by the app and by the tests so the tested path IS the
 * used path: policy → (if the candidate was not fully verified and the request's budget allows) ONE revision that names the
 * rejection → policy again → keep the better publication → drop the native conversation whenever the published text is not the
 * model's own candidate. The budget is the [AttemptLedger] of the request; an exhausted ledger means no revision is even tried.
 *
 * [regenerate] performs ONE native generation (counted by the slot at the engine boundary) given the revision note; [invalidate]
 * discards the persistent conversation so a rejected draft can never be continued by the next turn.
 */
class PublicationOrchestrator(
    private val regenerate: suspend (note: String, cause: String) -> String?,
    private val invalidate: suspend () -> Unit,
) {
    data class Outcome(val publication: Publication, val revised: Boolean)

    suspend fun finish(question: String, candidate: String, pkg: EvidencePackage, kind: PublicationKind, ledger: AttemptLedger): Outcome {
        var pub = PublicationPolicy.decide(question, candidate, pkg, kind)
        var revised = false
        if (pub.mode != PublicationMode.VERIFIED && !ledger.exhausted) {
            val note = PublicationPolicy.revisionNote(pub)
            if (note != null) {
                revised = true
                invalidate() // the rejected draft must not be continued by the revision
                val raw = regenerate(note, "revision:" + pub.mode.name.lowercase())
                val cand = raw?.trim()?.takeIf { it.isNotBlank() }
                if (cand != null) {
                    val second = PublicationPolicy.decide(question, cand, pkg, kind)
                    if (rank(second.mode) > rank(pub.mode)) pub = second
                }
            }
        }
        if (pub.mode != PublicationMode.VERIFIED || revised) invalidate()
        return Outcome(pub, revised)
    }

    private fun rank(m: PublicationMode) = when (m) {
        PublicationMode.VERIFIED -> 3; PublicationMode.ALLOWED_PARTS -> 2; PublicationMode.FAITHFUL_EXTRACT -> 1; PublicationMode.ABSTAIN -> 0
    }
}
