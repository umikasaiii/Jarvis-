package com.simone.jarvismobile.health

import android.content.Context
import android.content.Intent
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.simone.jarvismobile.data.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong

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
 * Onestà: `androidx.health.connect:connect-client` è una libreria alpha e
 * questo ambiente non può risolvere Maven Central né raggiungere la
 * documentazione live (stesso limite di rete già documentato per
 * TomTom/Valhalla) — i nomi/le firme esatte qui sotto (`AggregateGroupByPeriodRequest`,
 * l'indicizzazione di `AggregationResult`, `PermissionController`) sono
 * scritti da conoscenza di training, non verificati contro un compilatore
 * reale qui. La prima vera verifica è la compilazione Kotlin di CI, come
 * già successo (e corretto) una volta per `HeartRateRecord.BPM_AVG`.
 */
@Singleton
class HealthConnectManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
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
     * dispositivo di test, vedi l'indagine in CLAUDE.md). Usata la stringa
     * azione grezza `"androidx.health.ACTION_HEALTH_CONNECT_SETTINGS"` invece
     * del nome della costante Kotlin esposta dalla libreria: la stringa
     * stessa è la parte stabile del contratto pubblico, mentre la
     * classe/costante Kotlin che la incapsula è potenzialmente cambiata fra
     * le versioni alpha.
     */
    fun settingsIntent(): Intent = Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS")

    suspend fun hasPermissions(): Boolean {
        val c = client ?: return false
        return runCatching {
            c.permissionController.getGrantedPermissions().containsAll(permissions)
        }.getOrDefault(false)
    }

    /** One calendar day's readings — `date` is always real, either value can be legitimately absent (never a guess). */
    data class DailyHealthReading(
        val date: LocalDate,
        val heartRateBpm: Long?,
        val sleepHours: Double?,
    )

    data class WeeklyHealthAverages(
        val avgHeartRateBpm: Long?,
        val avgSleepPerNight: Duration?,
    )

    /** [daily]: oldest first, 7 entries — yesterday back to 7 days before (§ vedi [fetchDailySeries]). */
    data class HealthSnapshot(
        val daily: List<DailyHealthReading>,
        val averages: WeeklyHealthAverages,
    )

    /**
     * `aggregateGroupByPeriod` è il metodo pubblico di
     * `androidx.health.connect:connect-client` per bucket giornalieri (un
     * `AggregationResult` per ciascun `Period.ofDays(1)` nell'intervallo) —
     * distinto dal singolo intervallo che questo file usava prima
     * (`aggregate(AggregateRequest(...))`), rimosso: [computeAverages] ora
     * deriva le medie dagli stessi 7 bucket giornalieri invece di un secondo
     * calcolo separato, per costruzione mai divergente da essi.
     *
     * **Finestra "da ieri a 7 giorni prima" (§ richiesta esplicita
     * dell'utente: "la media la deve calcolare da ieri a 7 giorni prima, non
     * da lunedì a domenica fisso")**: `end` è la mezzanotte di oggi (quindi
     * la fine di ieri), non `Instant.now()` — la giornata di oggi è ancora in
     * corso e mescolarne i dati parziali (specie il sonno di stanotte, non
     * ancora accaduto) avrebbe sporcato la media. Il range resta comunque
     * sempre una finestra scorrevole di 7 giorni, mai un lunedì-domenica di
     * calendario fisso.
     *
     * **Bug reale trovato e corretto, segnalato dall'utente ("i dati bpm e
     * sonno penso proprio che siano errati")**: la prima stesura leggeva
     * `heartRateGroups.getOrNull(i)` per posizione nella lista, assumendo che
     * `aggregateGroupByPeriod` restituisca sempre esattamente 7 bucket, uno
     * per ciascun giorno richiesto, nello stesso ordine — un'assunzione mai
     * verificata contro un dispositivo reale in questo ambiente. Se invece
     * la libreria omette i bucket senza alcun dato (comportamento plausibile
     * e comune per API di aggregazione a bucket), la posizione `i`
     * nell'elenco restituito non corrisponde più al giorno `i` richiesto —
     * con solo 2-3 notti sincronizzate su un dispositivo reale, questo
     * avrebbe assegnato dati di un giorno a un altro, spiegando sia valori
     * sbagliati sia "giorni mancati" nel posto sbagliato. Corretto usando la
     * data reale di ogni bucket (`startTime`, un `LocalDateTime` per
     * `AggregationResultGroupedByPeriod`) come chiave invece della posizione
     * — corretto per costruzione indipendentemente dal fatto che i bucket
     * vuoti vengano omessi o meno.
     */
    private suspend fun fetchDailySeries(): List<DailyHealthReading> {
        val c = client ?: return emptyList()
        val today = LocalDate.now()
        val end = today.atStartOfDay(ZoneId.systemDefault()).toInstant()
        val start = end.minus(7, ChronoUnit.DAYS)
        val range = TimeRangeFilter.between(start, end)
        return runCatching {
            val heartRateByDate = c.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(setOf(HeartRateRecord.BPM_AVG), range, Period.ofDays(1)),
            ).associate { it.startTime.toLocalDate() to it.result[HeartRateRecord.BPM_AVG] }
            val sleepByDate = c.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(setOf(SleepSessionRecord.SLEEP_DURATION_TOTAL), range, Period.ofDays(1)),
            ).associate { it.startTime.toLocalDate() to it.result[SleepSessionRecord.SLEEP_DURATION_TOTAL] }
            (7 downTo 1).map { daysAgo ->
                val date = today.minusDays(daysAgo.toLong())
                DailyHealthReading(
                    date = date,
                    heartRateBpm = heartRateByDate[date],
                    sleepHours = sleepByDate[date]?.toMinutes()?.div(60.0),
                )
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Media dei soli giorni con un dato reale, non della finestra intera
     * (§ bug reale trovato: il calcolo precedente divideva il sonno totale
     * per 7 fisso — con solo 2 notti effettivamente sincronizzate su Health
     * Connect, il risultato era ~2h invece della vera media per notte
     * dormita, coerente con l'1h55m segnalato dall'utente come sbagliato).
     * Un giorno assente ora viene semplicemente escluso dalla media invece
     * di contare come uno zero implicito.
     */
    private fun computeAverages(daily: List<DailyHealthReading>): WeeklyHealthAverages {
        val bpmValues = daily.mapNotNull { it.heartRateBpm }
        val sleepValues = daily.mapNotNull { it.sleepHours }
        return WeeklyHealthAverages(
            avgHeartRateBpm = if (bpmValues.isNotEmpty()) bpmValues.average().roundToLong() else null,
            avgSleepPerNight = if (sleepValues.isNotEmpty()) Duration.ofMinutes((sleepValues.average() * 60.0).roundToLong()) else null,
        )
    }

    /**
     * Live read from Health Connect, then persisted (§ richiesta esplicita:
     * "questi risultati devono aggiornarsi ogni mattina poco dopo il
     * briefing mattutino") — chiamato sia da [com.simone.jarvismobile.proactive.ProactiveManager]
     * nello stesso punto in cui già aggiorna il meteo ogni mattina, sia da
     * `AresViewModel` per un refresh immediato quando l'utente apre la
     * schermata o concede l'accesso. Null quando i permessi mancano o la
     * lettura fallisce — la cache esistente resta quella vecchia, mai
     * cancellata da un fallimento.
     */
    suspend fun refresh(): HealthSnapshot? {
        if (!hasPermissions()) return null
        val daily = fetchDailySeries()
        if (daily.isEmpty()) return null
        val snapshot = HealthSnapshot(daily, computeAverages(daily))
        runCatching { settings.setHealthDailyCache(snapshot.toCacheJson(System.currentTimeMillis())) }
        return snapshot
    }

    /** Ultimo snapshot salvato — istantaneo, nessuna lettura da Health Connect (§ stesso pattern di [com.simone.jarvismobile.weather.WeatherManager.cachedOutlook]). */
    suspend fun cachedSnapshot(): HealthSnapshot? = healthSnapshotFromCacheJson(settings.healthDailyCache.first())
}
