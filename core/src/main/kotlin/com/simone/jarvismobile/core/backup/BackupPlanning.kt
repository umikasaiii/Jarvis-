package com.simone.jarvismobile.core.backup

import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.IsoFields

/** What an incremental run must do: copy the changed files, reuse the rest. */
data class IncrementalPlan(
    val toCopy: List<String>,
    val unchanged: List<String>,
    val removed: List<String>,
)

/**
 * Incremental backup diff. Given the previous manifest's file hashes and the
 * current file hashes, only files whose hash changed (or are new) are copied;
 * unchanged files are referenced from the previous backup, and files no longer
 * present are marked removed. Pure and deterministic (spec: copy only modified
 * files).
 */
object Incremental {
    fun plan(previous: Map<String, String>, current: Map<String, String>): IncrementalPlan {
        val toCopy = ArrayList<String>()
        val unchanged = ArrayList<String>()
        for ((path, hash) in current) {
            if (previous[path] == hash) unchanged += path else toCopy += path
        }
        val removed = previous.keys.filter { it !in current }
        return IncrementalPlan(toCopy.sorted(), unchanged.sorted(), removed.sorted())
    }
}

/**
 * Grandfather-father-son retention. Keeps the latest backup of each of the most
 * recent N days, weeks and months (spec default 7/4/6). A single backup can
 * satisfy several tiers; the kept set is their union, so nothing is double-counted
 * and the newest backups are always retained.
 *
 * Buckets use UTC calendar dates so the result is deterministic and unit-testable.
 */
object Retention {

    fun keep(backups: List<BackupRef>, policy: RetentionPolicy): Set<String> {
        if (backups.isEmpty()) return emptySet()
        val newestFirst = backups.sortedByDescending { it.createdAt }
        val keep = LinkedHashSet<String>()
        keep += latestPerBucket(newestFirst, policy.daily) { dayKey(it.createdAt) }
        keep += latestPerBucket(newestFirst, policy.weekly) { weekKey(it.createdAt) }
        keep += latestPerBucket(newestFirst, policy.monthly) { monthKey(it.createdAt) }
        return keep
    }

    /** Ids to delete: everything not kept. */
    fun prune(backups: List<BackupRef>, policy: RetentionPolicy): List<String> {
        val kept = keep(backups, policy)
        return backups.map { it.id }.filter { it !in kept }
    }

    /**
     * The latest backup of each of the most recent [count] distinct buckets.
     * [newestFirst] must already be sorted by createdAt descending, so the first
     * time a bucket key is seen is that bucket's latest backup.
     */
    private inline fun latestPerBucket(
        newestFirst: List<BackupRef>,
        count: Int,
        keyOf: (BackupRef) -> String,
    ): List<String> {
        if (count <= 0) return emptyList()
        val latestByBucket = LinkedHashMap<String, String>() // bucket -> backup id
        for (b in newestFirst) {
            val k = keyOf(b)
            if (k !in latestByBucket) latestByBucket[k] = b.id
        }
        return latestByBucket.values.take(count).toList()
    }

    private fun date(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
    private fun dayKey(ms: Long) = date(ms).toString() // yyyy-MM-dd
    private fun monthKey(ms: Long) = date(ms).let { "%04d-%02d".format(it.year, it.monthValue) }
    private fun weekKey(ms: Long) = date(ms).let {
        val week = it.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        val year = it.get(IsoFields.WEEK_BASED_YEAR)
        "%04d-W%02d".format(year, week)
    }
}
