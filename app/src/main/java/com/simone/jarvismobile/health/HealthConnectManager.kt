package com.simone.jarvismobile.health

import android.content.Context
import android.content.Intent
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Weekly-average heart rate and sleep duration for the tema Ares "Sistema"
 * block (§ richiesta esplicita dell'utente, risposta a domanda di chiarimento:
 * "Integra Health Connect"). Read-only, opt-in via the standard Health
 * Connect permission dialog — JARVIS never writes anything here and never
 * asks for any metric beyond these two.
 *
 * Health Connect is a local, on-device data broker (Android 14+ built-in;
 * older versions need the separate Health Connect app, declared in
 * `<queries>`) — the data itself still has to come from *something else*
 * writing it there (a wearable's own app, Google Fit, Samsung Health, …).
 * With nothing writing heart rate/sleep, the averages below are legitimately
 * absent, not a bug — same "unknown stays unknown, never a guess" discipline
 * [com.simone.jarvismobile.weather.WeatherManager] already follows.
 *
 * Onestà: `androidx.health.connect:connect-client` is an alpha library and
 * this environment cannot resolve Maven Central or reach the API docs (same
 * network limit already documented for TomTom/Valhalla) — the exact method
 * names below (`AggregateRequest`, `AggregationResult` indexing,
 * `PermissionController`) are written from training knowledge of the public
 * API, not verified against a live compiler here. First real verification is
 * CI's Gradle dependency resolution + Kotlin compile.
 */
@Singleton
class HealthConnectManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val client: HealthConnectClient? by lazy {
        runCatching {
            if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) {
                HealthConnectClient.getOrCreate(context)
            } else {
                null
            }
        }.getOrNull()
    }

    /** False when Health Connect itself isn't installed/available on this device. */
    val isAvailable: Boolean get() = client != null

    /**
     * Human-readable SDK status for the Diagnostica-style surface shown next
     * to "Concedi accesso" (§ bug reale: il tocco è ricettivo ma non apre
     * nulla anche dopo aver stabilizzato il contract di
     * rememberLauncherForActivityResult — nessun log di dispositivo
     * disponibile in questo ambiente per capire se il problema è a monte
     * dell'SDK stesso, quindi il prossimo screenshot dell'utente deve poter
     * mostrare un dato concreto invece di un altro tentativo alla cieca).
     */
    fun sdkStatusLabel(): String = runCatching {
        when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> "disponibile"
            HealthConnectClient.SDK_UNAVAILABLE -> "non supportato su questo dispositivo"
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "provider da installare o aggiornare"
            else -> "stato sconosciuto"
        }
    }.getOrElse { "errore nel controllo SDK (${it.message})" }

    /** The only two permissions this app ever requests from Health Connect. */
    val permissions: Set<String> = setOf(
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
    )

    /**
     * Opens Health Connect's own settings screen directly (§ richiesta esplicita
     * dell'utente: "permetti a jarvis di aprire la pagina [...] in cui richiede
     * accesso [...] ed io posso metterlo manualmente") — un percorso diverso
     * dal dialogo di consenso in-app (`PermissionController.createRequestPermissionResultContract()`,
     * rimosso perché mai risultato in un consenso reale su questo
     * dispositivo di test, vedi
     * l'indagine in CLAUDE.md: JARVIS non compare affatto nell'elenco
     * "Autorizzazioni app" di Health Connect, causa mai trovata). Da qui
     * l'utente naviga da solo fino alla pagina dei permessi per app e
     * concede/nega manualmente — nessun deep-link app-specifico usato perché
     * nessuna forma verificata esiste per questo (stesso limite di rete già
     * documentato: nessun accesso alla documentazione live in questo
     * ambiente). Usata la stringa azione grezza `"androidx.health.ACTION_HEALTH_CONNECT_SETTINGS"`
     * invece del nome della costante Kotlin esposta dalla libreria: la
     * stringa stessa è la parte stabile del contratto pubblico (documentata
     * da tempo per questo scopo esatto), mentre la classe/costante Kotlin
     * che la incapsula è potenzialmente cambiata fra le versioni alpha di
     * `androidx.health.connect:connect-client` — riferirla per nome avrebbe
     * rischiato un "unresolved reference" mai verificabile qui, la stessa
     * categoria di errore già presa una volta con `HeartRateRecord.BPM_AVG`
     * (Long, non Double). La Activity di Health Connect stessa gestisce
     * l'azione indipendentemente da come l'app chiamante la referenzia.
     */
    fun settingsIntent(): Intent = Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS")

    suspend fun hasPermissions(): Boolean {
        val c = client ?: return false
        return runCatching {
            c.permissionController.getGrantedPermissions().containsAll(permissions)
        }.getOrDefault(false)
    }

    data class WeeklyHealthAverages(
        // BPM_AVG aggregates to a Long (§ fix CI: "Argument type mismatch:
        // actual type is 'Long?', but 'Double?' was expected" — the library's
        // real signature, not the Double this was first guessed as).
        val avgHeartRateBpm: Long?,
        val avgSleepPerNight: Duration?,
    )

    /** Null when unavailable, ungranted, or the read itself fails — never a guess. */
    suspend fun weeklyAverages(): WeeklyHealthAverages? {
        val c = client ?: return null
        if (!hasPermissions()) return null
        val end = Instant.now()
        val start = end.minus(7, ChronoUnit.DAYS)
        val range = TimeRangeFilter.between(start, end)
        return runCatching {
            val heartRate = c.aggregate(AggregateRequest(setOf(HeartRateRecord.BPM_AVG), range))
            val sleep = c.aggregate(AggregateRequest(setOf(SleepSessionRecord.SLEEP_DURATION_TOTAL), range))
            val avgBpm = heartRate[HeartRateRecord.BPM_AVG]
            val totalSleep = sleep[SleepSessionRecord.SLEEP_DURATION_TOTAL]
            WeeklyHealthAverages(
                avgHeartRateBpm = avgBpm,
                avgSleepPerNight = totalSleep?.dividedBy(7),
            )
        }.getOrNull()
    }
}
