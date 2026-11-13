package com.simone.jarvismobile.core.quality

/**
 * HOTFIX 5 — generate → clean → validate → at most ONE retry → choose → present. Pure orchestration around a
 * caller-supplied [generate] (the real call goes through `LlmRouter`/`InferenceSlot`, so it stays cancellable and
 * subject to the Hotfix 3/4 invariants; cancellation and timeouts propagate untouched).
 *
 * The retry is bounded (exactly one), motivated (EMPTY / INCOMPLETE / ECHO_OF_EARLIER_ANSWER only — degenerate output
 * is already retried by the slot, so it is never retried twice) and runs on a FRESH conversation. When nothing usable
 * is produced the result text is null and the caller shows an honest message instead of a fabricated answer.
 */
object ValidatedGeneration {

    data class Result(
        val text: String?,
        val assessment: AnswerQuality.Assessment,
        val retried: Boolean,
        val firstFlags: Set<AnswerQuality.Flag>,
        val rawChars: Int,
        val cleanChars: Int,
        /** Raw text of the last attempt, for the temporary in-memory debug probe ONLY (never persisted). */
        val lastRaw: String? = null,
    )

    suspend fun run(
        question: String,
        earlierAnswers: List<String>,
        earlierQuestions: List<String>,
        evidence: String?,
        provenance: AnswerQuality.Provenance,
        clean: (String) -> String,
        /** [firstFlags] is empty on the first call and carries the first attempt's defects on the retry. */
        generate: suspend (retry: Boolean, firstFlags: Set<AnswerQuality.Flag>) -> String?,
    ): Result {
        val raw1 = generate(false, emptySet())
        val c1 = raw1?.let(clean)?.trim().orEmpty()
        val a1 = AnswerQuality.evaluate(question, c1, earlierAnswers, earlierQuestions, evidence)
        if (!a1.retryable) {
            return Result(AnswerQuality.present(c1, provenance, a1), a1, false, a1.flags, raw1?.length ?: 0, c1.length, raw1)
        }

        val raw2 = generate(true, a1.flags)
        val c2 = raw2?.let(clean)?.trim().orEmpty()
        val a2 = AnswerQuality.evaluate(question, c2, earlierAnswers, earlierQuestions, evidence)

        // Prefer the attempt with the lesser defect. ECHO and EMPTY are never shown; INCOMPLETE is shown only when
        // nothing better exists (a cut-off answer is still information) and then the longer one wins.
        val usable1 = c1.isNotEmpty() && AnswerQuality.Flag.ECHO_OF_EARLIER_ANSWER !in a1.flags
        val usable2 = c2.isNotEmpty() && AnswerQuality.Flag.ECHO_OF_EARLIER_ANSWER !in a2.flags
        val (text, chosen) = when {
            !a2.retryable -> c2 to a2
            usable1 && usable2 -> if (c2.length >= c1.length) c2 to a2 else c1 to a1
            usable2 -> c2 to a2
            usable1 -> c1 to a1
            else -> "" to a2
        }
        val shown = if (text.isEmpty()) null else AnswerQuality.present(text, provenance, chosen)
        return Result(shown, chosen, true, a1.flags, (raw1?.length ?: 0) + (raw2?.length ?: 0), text.length, raw2)
    }
}
