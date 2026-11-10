package com.simone.jarvismobile.core.chat

/**
 * HOTFIX 3 — a queued answer waits (bounded) for OLDER active answers so the chat keeps arrival order. After a
 * process death or a force-stop an older row can stay `GENERATING`/`QUEUED` in Room although nothing is running
 * for it any more; counting it made every newer answer wait the whole ordering window before even starting,
 * which looks exactly like "the generation is stuck until the timeout". A row that has not been touched for
 * [STALE_AFTER_MS] is therefore treated as orphaned for ordering purposes (it is still re-run by WorkManager if
 * its work survived; it just no longer blocks newer messages).
 */
object QueueLiveness {
    const val STALE_AFTER_MS: Long = 5 * 60_000L

    fun staleBefore(nowMs: Long): Long = nowMs - STALE_AFTER_MS

    /** @return true when an ACTIVE row last updated at [updatedAtMs] should still hold back newer messages. */
    fun blocksNewerMessages(updatedAtMs: Long, nowMs: Long): Boolean = updatedAtMs >= staleBefore(nowMs)
}
