package com.simone.jarvismobile.bench

import com.simone.jarvismobile.core.bench.BenchReport
import com.simone.jarvismobile.core.bench.CaseScore
import com.simone.jarvismobile.core.bench.RunIdentity

/** Voluntary export: the user sees the exact report first; nothing is written or sent without [confirmed]. The UI for it is PENDING (the interface is tested). */
object BenchExport {
    class Bundle(val markdown: String, val json: String, val csv: String, val svg: String)

    fun preview(id: RunIdentity, scores: List<CaseScore>, totalCases: Int) = BenchReport.markdown(id, scores, totalCases)

    fun exportForUser(confirmed: Boolean, id: RunIdentity, scores: List<CaseScore>, totalCases: Int): Bundle? =
        if (!confirmed) null else Bundle(BenchReport.markdown(id, scores, totalCases), BenchReport.json(id, scores, totalCases), BenchReport.csv(scores), BenchReport.svgChart(id, scores))
}
