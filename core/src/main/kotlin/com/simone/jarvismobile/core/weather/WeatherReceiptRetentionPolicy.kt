package com.simone.jarvismobile.core.weather

/**
 * § JARVIS Implementation Master Plan — PROACTIVITY RELIABILITY CLOSURE WORK
 * PACKAGE D.1 §3. The pure, deterministic arithmetic behind
 * [com.simone.jarvismobile.weather.receipt.ForecastDecisionReceiptRepository.prune] —
 * extracted so the two retention rules (90 days AND max 4096 rows, §28) are
 * genuinely testable in this JVM sandbox rather than only reviewable inside
 * a Room-dependent, Android-only class.
 *
 * Deliberately narrow: this object only computes WHAT should be deleted
 * (a cutoff timestamp, an excess-row count) — it never touches storage
 * itself. [com.simone.jarvismobile.weather.receipt.ForecastDecisionReceiptRepository]
 * remains the only writer/deleter, so a retention failure here can never
 * corrupt the live DB (§3 — "retention failure must never corrupt the live
 * DB"): a pure function cannot corrupt anything, only the I/O around it can,
 * and that I/O stays exactly where it already was.
 *
 * INVARIANT (§3 — "no deletion of data still required for an in-flight
 * decision"): both rules only ever target the OLDEST rows (age-based, or
 * count-based ordered oldest-first) — a receipt just written by an
 * in-flight evaluation is, by construction, the newest row in the table,
 * so it can never be selected by either rule as long as [retentionDays]/
 * [maxRows] stay sane (>0). This is a property of the two rules themselves,
 * not an assumption bolted on afterwards — see
 * [WeatherReceiptRetentionPolicyTest]'s `newestRowNeverEligibleForDeletion`.
 */
object WeatherReceiptRetentionPolicy {
    const val DEFAULT_RETENTION_DAYS: Long = 90L
    const val DEFAULT_MAX_ROWS: Int = 4096

    /** Rows with `evaluatedAtUtcMs < cutoffMs(...)` are age-eligible for deletion. Never negative-guarded here — the caller (real epoch millis) is always non-negative in practice; a caller passing an absurd [retentionDays] simply gets an absurd (but well-defined) cutoff, never a crash. */
    fun cutoffMs(nowMs: Long, retentionDays: Long = DEFAULT_RETENTION_DAYS): Long =
        nowMs - retentionDays.coerceAtLeast(0L) * MS_PER_DAY

    /** How many of the OLDEST rows exceed [maxRows] and are therefore count-eligible for deletion — 0 when [currentRowCount] is already within bounds, never negative. */
    fun excessRowCount(currentRowCount: Int, maxRows: Int = DEFAULT_MAX_ROWS): Int =
        (currentRowCount - maxRows).coerceAtLeast(0)

    private const val MS_PER_DAY: Long = 24L * 60L * 60L * 1000L
}
