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
    fun show(suggestion: ProactiveSuggestion) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
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
        // kinds stay on.
        val isDigest = suggestion.kind == ProactiveKind.MORNING_DIGEST ||
            suggestion.kind == ProactiveKind.EVENING_DIGEST
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
            // § bug reale segnalato dall'utente: il briefing mattutino delle
            // 8:00 è stato seguito da un secondo avviso "Briefing" alle 9:00,
            // dopo aver collegato l'orologio alle 8:30 — causa reale, non
            // ipotizzata: `ProactiveManager.refreshMorningDigestNotification()`
            // (§ FASE 2A.8 §G, +10min/+60min post-briefing refresh) ripubblica
            // DELIBERATAMENTE la stessa notifica (stesso id, mai una seconda)
            // per aggiornarne il contenuto con dati Health nel frattempo
            // sincronizzati — ma senza `setOnlyAlertOnce`, `notify()` con lo
            // stesso id fa comunque suonare/vibrare/apparire di nuovo la
            // notifica su un canale `IMPORTANCE_HIGH` come CHANNEL_REMINDERS,
            // che è indistinguibile per l'utente da un secondo messaggio
            // vero. Il commento di `refreshMorningDigestNotification()`
            // dichiarava già l'intento ("si sostituisce sul posto... mai un
            // secondo messaggio") ma il costruttore della notifica non lo
            // garantiva. Con questo flag, un `notify()` sulla stessa notifica
            // ancora presente nella shade aggiorna il contenuto in silenzio;
            // se l'utente l'ha già chiusa nel frattempo, Android la tratta
            // comunque come nuova e avvisa di nuovo — comportamento corretto,
            // non un bug residuo.
            .setOnlyAlertOnce(true)
        if (!isDigest) builder.addAction(0, "Non avvisarmi più di questo", mute)
        val notification = builder.build()
        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId(suggestion.kind), notification)
        }
    }

    companion object {
        private const val NOTIFICATION_BASE = 7_200
        fun notificationId(kind: ProactiveKind) = NOTIFICATION_BASE + kind.ordinal
    }
}
