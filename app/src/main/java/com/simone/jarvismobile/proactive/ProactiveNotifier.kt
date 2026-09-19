package com.simone.jarvismobile.proactive

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.simone.jarvismobile.background.JarvisNotifications
import com.simone.jarvismobile.core.proactive.ProactiveKind
import com.simone.jarvismobile.core.proactive.ProactiveSuggestion
import com.simone.jarvismobile.ui.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * § PROACTIVITY RELIABILITY CLOSURE WORK PACKAGE A (§12) — the typed,
 * internal dispatch outcome. `Unit`-returning `show()` could never
 * distinguish "the Android call never happened" from "it happened and
 * threw" from "it genuinely posted" — the exact ambiguity that let
 * [ProactiveDeliveryDispatcher] durably record a false POSTED. Never claim
 * POSTED == the user actually saw it / heads-up shown / sound heard — this
 * only reports what the Android API call itself returned.
 */
sealed interface ProactiveDispatchOutcome {
    /** The Android `notify()` call completed without throwing. Still not a guarantee of visible delivery — see the type doc above. */
    data object Posted : ProactiveDispatchOutcome

    /** Proven no-effect: rejected BEFORE any Android call — POST_NOTIFICATIONS missing, notifications globally disabled, or the channel is blocked/disabled. Always safe to retry once the condition may have changed. */
    data object BlockedPermission : ProactiveDispatchOutcome

    /** The Android call was made and threw — genuinely unknown whether anything was posted. Never a blind retry. */
    data class ApiThrew(val exceptionClass: String) : ProactiveDispatchOutcome
}

/**
 * Posts a proactive suggestion as one discreet "Suggerimenti" notification. One
 * id per kind, so muting or a repeat replaces rather than stacks.
 *
 * **"Non avvisarmi più di questo" rimossa dal briefing, richiesto esplicitamente
 * dall'utente**: un digest (mattutino/serale) è contenuto che l'utente ha
 * esplicitamente scelto di ricevere (§ commento su [isDigest] qui sotto, già
 * per questo esente dalle ore silenziose) — un tap distratto su quel pulsante
 * lo avrebbe silenziato per sempre senza passare da Impostazioni. Il pulsante
 * resta solo sui veri suggerimenti facoltativi (es. `BATTERY_BEFORE_ALARM`),
 * dove è ancora la scorciatoia giusta; disattivare il briefing resta possibile
 * da Impostazioni › Proattività › «Tipi di intervento», il controllo reale e
 * completo (non solo un mute silenzioso), come richiesto esplicitamente.
 */
@Singleton
class ProactiveNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * § PROACTIVITY RELIABILITY CLOSURE WORK PACKAGE A (§12) — the single
     * internal dispatch operation. Replaces the old public `Unit`-returning
     * `show()`: the only two real production call sites are
     * [ProactiveDeliveryDispatcher] (every occurrence-backed kind —
     * MORNING_DIGEST/EVENING_DIGEST/WEATHER_ALERT/BATTERY_BEFORE_ALARM) and
     * `ProactiveManager.simulateWeatherAlert()`'s debug preview path (its
     * own distinct tag/id range, never production occurrence ownership).
     *
     * [silent] (§ JARVIS Implementation Master Plan MICRO-PATCH 14.2.2) —
     * `true` for a post-delivery CONTENT REFRESH only (never a real new
     * delivery): `setOnlyAlertOnce` alone does NOT guarantee silence — it
     * only suppresses re-alerting while the notification with this id is
     * STILL present in the shade; once the user has dismissed/opened it,
     * Android treats the next `notify()` on the same id as a brand-new
     * alert (sound/vibration/heads-up), which is the exact real-device
     * root cause of the extra 08:14/09:00 "briefings" a prior patch fixed.
     * `setSilent(true)` unconditionally suppresses alerting for THIS post
     * regardless of dismissal state — the real guarantee a refresh needs.
     * Every genuine new delivery keeps `silent = false` (default), unchanged.
     *
     * [tag] (§17) — a stable, non-null feature namespace
     * (`jarvis.proactive.morning`/`.evening`/`.weather`, or the caller's
     * own kind-derived default). Notification ids stay explicit
     * ([notificationId], never derived from enum ordinal drift) — the tag
     * exists so a legacy untagged notification for the same [ProactiveKind]
     * never visually stacks with a new tagged one; callers with a legacy
     * delivered occurrence should suppress re-posting under the new tag at
     * the occurrence layer, not rely on tag collision here.
     */
    fun dispatch(suggestion: ProactiveSuggestion, silent: Boolean = false, tag: String? = null): ProactiveDispatchOutcome {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return ProactiveDispatchOutcome.BlockedPermission
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return ProactiveDispatchOutcome.BlockedPermission
        }
        val open = PendingIntent.getActivity(
            context,
            suggestion.kind.ordinal,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val mute = PendingIntent.getBroadcast(
            context,
            1000 + suggestion.kind.ordinal,
            Intent(context, ProactiveActionReceiver::class.java)
                .setAction(ProactiveActionReceiver.ACTION_MUTE)
                .putExtra(ProactiveActionReceiver.EXTRA_KIND, suggestion.kind.name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // A digest (morning/evening) is content the user actually asked to be
        // told, not an optional nudge — it belongs on the same normal, audible
        // channel as agenda reminders and automation results, not the deliberately
        // muted "Suggerimenti" channel that BATTERY_BEFORE_ALARM and future tip-like
        // kinds stay on. § JARVIS Implementation Master Plan PASSAGGIO 14.2 —
        // WEATHER_ALERT joins them: a rain/storm warning is hazard-relevant
        // content the user would want to actually notice, not a discretionary tip.
        val isDigest = suggestion.kind == ProactiveKind.MORNING_DIGEST ||
            suggestion.kind == ProactiveKind.EVENING_DIGEST ||
            suggestion.kind == ProactiveKind.WEATHER_ALERT
        val builder = JarvisNotifications.styled(
            context = context,
            channelId = if (isDigest) JarvisNotifications.CHANNEL_REMINDERS else JarvisNotifications.CHANNEL_SUGGESTIONS,
            title = "JARVIS",
            text = suggestion.message,
            contentIntent = open,
            expandableText = suggestion.message,
        )
            .setPriority(if (isDigest) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            // `setOnlyAlertOnce` alone was historically not enough to keep a
            // legitimate re-post silent once the user had already dismissed
            // the original — see [dispatch]'s own doc comment for the full
            // history and why [silent]/`setSilent` is the real guarantee.
            .setOnlyAlertOnce(true)
            .setSilent(silent)
        if (!isDigest) builder.addAction(0, "Non avvisarmi più di questo", mute)
        val notification = builder.build()
        val effectiveTag = tag ?: tagFor(suggestion.kind)
        return runCatching {
            NotificationManagerCompat.from(context).notify(effectiveTag, notificationId(suggestion.kind), notification)
            ProactiveDispatchOutcome.Posted
        }.getOrElse { e -> ProactiveDispatchOutcome.ApiThrew(e.javaClass.simpleName) }
    }

    companion object {
        private const val NOTIFICATION_BASE = 7_200
        fun notificationId(kind: ProactiveKind) = NOTIFICATION_BASE + kind.ordinal

        /** § §17 — stable non-null feature tags for proactive production output. Ids stay explicit ([notificationId]); tags exist only to namespace the feature, never as a second id scheme. */
        const val TAG_MORNING = "jarvis.proactive.morning"
        const val TAG_EVENING = "jarvis.proactive.evening"
        const val TAG_WEATHER = "jarvis.proactive.weather"
        const val TAG_SUGGESTION = "jarvis.proactive.suggestion"

        /** § §18 — debug/diagnostics isolation: weather simulation previews use a distinct tag+id range, never production occurrence ownership. */
        const val TAG_DEBUG = "jarvis.proactive.debug"
        private const val DEBUG_NOTIFICATION_BASE = 7_300
        fun debugNotificationId(kind: ProactiveKind) = DEBUG_NOTIFICATION_BASE + kind.ordinal

        fun tagFor(kind: ProactiveKind): String = when (kind) {
            ProactiveKind.MORNING_DIGEST -> TAG_MORNING
            ProactiveKind.EVENING_DIGEST -> TAG_EVENING
            ProactiveKind.WEATHER_ALERT -> TAG_WEATHER
            ProactiveKind.BATTERY_BEFORE_ALARM -> TAG_SUGGESTION
        }
    }
}
