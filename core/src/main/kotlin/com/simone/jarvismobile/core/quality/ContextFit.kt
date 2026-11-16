package com.simone.jarvismobile.core.quality

import com.simone.jarvismobile.core.reliability.EvidenceItem
import com.simone.jarvismobile.core.reliability.EvidenceKind
import com.simone.jarvismobile.core.reliability.EvidencePackage

/**
 * M1 — fits the evidence of a turn into what is left of the context window AFTER everything else that is really sent. Every figure is the
 * [TokenEstimator] estimate (LiteRT-LM exposes no tokenizer we could verify): it is an estimate with a margin, never a measured count.
 *
 * Whole items are dropped, never cut mid-sentence, least useful first: personal memory not tied to the question, then the later-ranked
 * knowledge/document passages; tool receipts are never dropped (they are what the answer must report). What is dropped is recorded as omitted
 * so the prompt says so and the answer cannot claim to have read it.
 */
object ContextFit {
    data class Fit(val pkg: EvidencePackage, val estTokens: Int, val droppedIds: List<String>, val fits: Boolean)

    fun itemTokens(i: EvidenceItem) = TokenEstimator.estimate(i.text) + TokenEstimator.estimate(i.title) + 8

    /** [fixedTokens]: system + history + question + framing + any revision note reserve; [ceiling]: prompt tokens available in total. */
    fun fit(pkg: EvidencePackage, fixedTokens: Int, ceiling: Int): Fit {
        var current = pkg
        val dropped = ArrayList<String>()
        fun total() = fixedTokens + current.items.sumOf(::itemTokens)
        // drop order: memory (oldest last), then knowledge/documents from the end, tool receipts never
        val order = current.items.filter { it.kind == EvidenceKind.MEMORY }.reversed() +
            current.items.filter { it.kind == EvidenceKind.KNOWLEDGE || it.kind == EvidenceKind.DOCUMENT }.reversed().dropLast(1)
        for (victim in order) {
            if (total() <= ceiling) break
            current = current.without(victim.id, "budget del contesto")
            dropped += victim.id
        }
        // if even the single remaining knowledge/document item does not fit, drop it too rather than overflow the window
        if (total() > ceiling) {
            current.items.filter { it.kind == EvidenceKind.KNOWLEDGE || it.kind == EvidenceKind.DOCUMENT }.forEach {
                if (total() > ceiling) { current = current.without(it.id, "budget del contesto"); dropped += it.id }
            }
        }
        return Fit(current, total(), dropped, total() <= ceiling)
    }
}
