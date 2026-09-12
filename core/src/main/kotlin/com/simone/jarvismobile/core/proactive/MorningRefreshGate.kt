package com.simone.jarvismobile.core.proactive

/**
 * § JARVIS Implementation Master Plan — MICRO-PATCH 14.2.2. The pure
 * decision at the heart of the real device root cause fix.
 *
 * ROOT CAUSE (evidence, not assumption — see `ProactiveManager.kt`'s own
 * doc comments): `refreshMorningDigestNotification()` (called only by
 * `MorningRefreshWorker`, scheduled +10min/+60min after a real morning
 * digest DELIVERY) used to compose and post a notification directly,
 * WITHOUT ever checking — only ASSUMING — that today's morning occurrence
 * was genuinely `DELIVERED`. This is the one code path in the whole app
 * that could produce Morning-Briefing-shaped notification content with
 * `occurrence claim = NO` (never called [com.simone.jarvismobile.core.proactive.ProactiveOccurrenceReconciler]/
 * the durable claim store at all). Combined with a plain `NotificationManagerCompat.notify()`
 * call (which alerts again whenever the user has already dismissed the
 * original notification — `setOnlyAlertOnce` alone does not prevent this,
 * it only suppresses re-alerting while the notification is STILL present),
 * this is what produced the extra, differently-worded 08:14/09:00
 * deliveries on real device: the +10min worker recomposed with freshly
 * refreshed weather data (now known, hence the emoji) and re-notified as a
 * brand-new alert once the 08:00 notification had already been seen/
 * dismissed; the +60min worker did the same again at 09:00.
 *
 * This gate is the fix's verification half: [shouldRefresh] must be
 * consulted BEFORE any refresh-path notification side effect, and must
 * return `true` ONLY when the occurrence is genuinely
 * [ProactiveOccurrenceState.DELIVERED] — any other state means no real
 * delivery has happened yet (never claimed, still in flight, or failed),
 * so a "refresh" has nothing legitimate to refresh and must be a pure
 * no-op rather than risk composing/posting content for an occurrence
 * nobody actually delivered. The second half of the fix — guaranteeing
 * that even a legitimate refresh can never itself become a second alert —
 * is `ProactiveNotifier.show(..., silent = true)` (`app/`, Android-only,
 * not expressible here).
 */
object MorningRefreshGate {
    fun shouldRefresh(currentState: ProactiveOccurrenceState?): Boolean =
        currentState == ProactiveOccurrenceState.DELIVERED
}
