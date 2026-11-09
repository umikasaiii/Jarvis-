package com.simone.jarvismobile.reminders

import android.content.Context

/**
 * BETA 7 — a tiny persistent record of what happened to reminder alarms, so "did it ring?" has an answer and a
 * reminder missed while the phone was off can be delivered late exactly once.
 *
 * Holds only alert keys (entry id + alert key, no text) and outcome names with timestamps — never reminder content.
 * `POSTED` means the platform call returned; it is not proof that the user saw the notification.
 */
class ReminderDeliveryLedger(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun delivered(): Set<String> = prefs.getStringSet(KEY_DELIVERED, emptySet()).orEmpty()

    @Synchronized
    fun record(alertKey: String, outcome: ReminderPostOutcome, atMs: Long = System.currentTimeMillis()) {
        val delivered = delivered().toMutableSet()
        // A blocked post is NOT delivered: it may be retried as a missed reminder once the block is lifted.
        if (outcome == ReminderPostOutcome.POSTED) delivered += alertKey
        val log = (prefs.getString(KEY_LOG, "").orEmpty().lines().filter { it.isNotBlank() } + "$atMs|${outcome.name}")
            .takeLast(MAX_LOG)
        prefs.edit().putStringSet(KEY_DELIVERED, delivered.toList().takeLast(MAX_DELIVERED).toSet()).putString(KEY_LOG, log.joinToString("\n")).apply()
    }

    /** Drops keys that no longer correspond to any known alert, keeping the set bounded. */
    @Synchronized
    fun retainOnly(known: Set<String>) {
        val kept = delivered().intersect(known)
        if (kept.size != delivered().size) prefs.edit().putStringSet(KEY_DELIVERED, kept).apply()
    }

    /** Most recent outcomes, newest last: `epochMs|OUTCOME`. */
    fun recentLog(): List<String> = prefs.getString(KEY_LOG, "").orEmpty().lines().filter { it.isNotBlank() }

    private companion object {
        const val PREFS = "jarvis_reminder_ledger"
        const val KEY_DELIVERED = "delivered_keys"
        const val KEY_LOG = "outcome_log"
        const val MAX_LOG = 30
        const val MAX_DELIVERED = 300
    }
}
