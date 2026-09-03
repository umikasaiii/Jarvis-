package com.simone.jarvismobile.health

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.simone.jarvismobile.core.health.HealthDailySeries
import com.simone.jarvismobile.core.health.HeartRateSample
import com.simone.jarvismobile.core.health.SleepSessionSpan
import com.simone.jarvismobile.core.health.SleepStageSpan
import com.simone.jarvismobile.data.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass

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
 * TomTom/Valhalla) — i nomi/le firme esatte qui sotto (`ReadRecordsRequest`,
 * `RestingHeartRateRecord`, `PermissionController`) sono scritti da
 * conoscenza di training, non verificati contro un compilatore reale qui.
 * La prima vera verifica è la compilazione Kotlin di CI, come già successo
 * (e corretto) una volta per `HeartRateRecord.BPM_AVG` — poi abbandonato in
 * favore della lettura grezza, vedi [fetchDailySeries].
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
     * Le uniche due permissions richieste da questa app. **Cambiata la prima
     * (§ richiesta esplicita dell'utente: "vorrei che per bpm si riporti la
     * media dei battiti a riposo")**: da `HeartRateRecord` (il flusso
     * continuo di campioni, usato per calcolarci sopra una media
     * dell'intera giornata comprese le fasi attive) a
     * `RestingHeartRateRecord` — un tipo di record Health Connect distinto,
     * pensato apposta per la sola frequenza a riposo (lo stesso concetto
     * che Honor Health mostra come sua cifra principale). **Permesso
     * diverso, non lo stesso**: Health Connect tratta i due tipi di record
     * come permessi separati, quindi un utente che aveva già concesso
     * l'accesso alla frequenza cardiaca "normale" deve concedere anche
     * questo nuovo permesso specifico prima che i BPM tornino a comparire —
     * non un bug, un requisito reale della piattaforma.
     */
    val permissions: Set<String> = setOf(
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
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
        }.onFailure { e -> Log.w(TAG, "has_permissions_check_failed ${e.javaClass.simpleName}") }.getOrDefault(false)
    }

    /**
     * Diagnostica testuale, mai usata dal percorso normale — solo per il
     * caso segnalato dall'utente: Health Connect mostra entrambi i
     * permessi concessi (screenshot delle sue Impostazioni), ma
     * `hasPermissions()` resta `false` anche dopo un riavvio completo
     * dell'app (quindi non un semplice ritardo di propagazione, già
     * escluso). `hasPermissions()` avvolge tutto in `runCatching { }
     * .getOrDefault(false)`: un'eccezione reale (es. di tipo/versione
     * sulla nuova `RestingHeartRateRecord`) sarebbe indistinguibile da un
     * permesso davvero mancante — questa funzione non nasconde né l'una
     * né l'altra, per dare al prossimo screenshot un dato concreto invece
     * di un'altra ipotesi.
     */
    suspend fun permissionsDiagnostic(): String {
        val c = client ?: return "client Health Connect nullo"
        return runCatching {
            val granted = c.permissionController.getGrantedPermissions()
            val missing = permissions - granted
            // Etichette brevi invece della stringa Android completa (§ bug
            // reale: "android.permission.health.READ_..." è troppo lunga
            // per lo spazio della card e restava tagliata a metà nello
            // screenshot dell'utente — impossibile capire quale dei due
            // mancasse davvero).
            if (missing.isEmpty()) {
                "concessi entrambi"
            } else {
                val labels = missing.map { permission ->
                    when (permission) {
                        HealthPermission.getReadPermission(RestingHeartRateRecord::class) -> "frequenza a riposo"
                        HealthPermission.getReadPermission(SleepSessionRecord::class) -> "sonno"
                        else -> permission
                    }
                }
                "manca: ${labels.joinToString()}"
            }
        }.getOrElse { "eccezione ${it::class.simpleName}: ${it.message}" }
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

    /**
     * [daily]: oldest first, 8 entries — oggi compreso, fino a 7 giorni prima
     * (§ vedi [fetchDailySeries] — [averages] esclude oggi). [updatedAtMs] è
     * il momento della lettura Health Connect riuscita più recente dietro
     * questo snapshot — mai il momento in cui lo schermo l'ha semplicemente
     * riletto dalla cache, così l'utente può distinguere un dato di oggi da
     * uno vecchio senza dover chiedere (§ bug reale segnalato: nessun
     * segnale di freschezza esisteva prima, un fallimento silenzioso di
     * `refresh()` lasciava la card con numeri vecchi senza alcuna traccia).
     */
    data class HealthSnapshot(
        val daily: List<DailyHealthReading>,
        val averages: WeeklyHealthAverages,
        val updatedAtMs: Long,
    )

    /**
     * Diagnostica non invasiva per distinguere le cinque cause possibili di
     * "i dati non si aggiornano" richieste esplicitamente dall'utente:
     * (1) Health Connect non contiene il dato → [restingHeartRateRecordCount]/
     * [sleepSessionRecordCount] a zero nella finestra mostrata da
     * [queriedRangeStart]/[queriedRangeEnd] (confrontabile a mano con quanto
     * mostra l'app Health Connect stessa per lo stesso intervallo);
     * (2) JARVIS non lo legge → un'eccezione reale finisce in [lastErrorType]
     * invece di sparire dentro un `runCatching` silenzioso;
     * (3) JARVIS lo scarta nell'aggregazione → conteggi grezzi qui sopra
     * diversi dal numero di giorni con un valore non-null nel dialog di
     * dettaglio rivelano un bug di bucketing;
     * (4) JARVIS lo salva ma usa una cache vecchia → [refreshAttemptedAtMs]
     * (ogni tentativo, riuscito o no) contro [refreshSucceededAtMs] (solo
     * l'ultimo davvero scritto) separa "non ho più provato" da "ho provato
     * ma è fallito";
     * (5) arriva al repository ma la UI non si aggiorna → non osservabile da
     * qui (serve confrontare questo pannello con quanto mostra la card) ma
     * questo pannello dà il dato di riferimento per farlo. Mai un valore
     * bpm/sonno qui dentro — solo conteggi e istanti, per restare nei limiti
     * di "senza esporre dati personali" richiesti esplicitamente.
     */
    data class DiagnosticSnapshot(
        val queriedRangeStart: Instant,
        val queriedRangeEnd: Instant,
        val restingHeartRateRecordCount: Int,
        val sleepSessionRecordCount: Int,
        val lastHeartRateSampleAt: Instant?,
        val lastSleepSessionEndAt: Instant?,
        val refreshAttemptedAtMs: Long,
        val refreshSucceededAtMs: Long?,
        val lastErrorType: String?,
    )

    private val _diagnostic = MutableStateFlow<DiagnosticSnapshot?>(null)
    /** Popolata a ogni [refresh] reale (mai a un semplice rilettura di cache) — vedi [DiagnosticSnapshot]. */
    val diagnostic: StateFlow<DiagnosticSnapshot?> = _diagnostic.asStateFlow()

    /**
     * Legge tutte le pagine di [type] nella finestra [range] (§ `readRecords`
     * pagina i risultati, `pageSize` di default 1000 — con campionamento
     * continuo del battito questo si esaurisce facilmente su 7 giorni,
     * seguito qui fino in fondo invece di fermarsi silenziosamente alla
     * prima pagina). `guard` limita comunque le iterazioni per non entrare
     * mai in un loop infinito su un `pageToken` che non si esaurisse mai.
     */
    private suspend fun <T : Record> readAllRecords(
        c: HealthConnectClient,
        type: KClass<T>,
        range: TimeRangeFilter,
    ): List<T> {
        val all = mutableListOf<T>()
        var pageToken: String? = null
        var guard = 0
        do {
            val response = c.readRecords(ReadRecordsRequest(type, timeRangeFilter = range, pageToken = pageToken))
            all += response.records
            pageToken = response.pageToken?.takeIf { it.isNotBlank() }
            guard++
        } while (pageToken != null && guard < 50)
        return all
    }

    /**
     * Storia della finestra temporale, per chi tocca questo file dopo un
     * ennesimo report "manca ieri"/"manca il sonno della notte scorsa":
     * `end` è `Instant.now()` (non la mezzanotte di oggi), così una sessione
     * di sonno già conclusa stamattina o una lettura BPM di poco fa non
     * restano invisibili fino a domani — [computeAverages] esclude comunque
     * esplicitamente la data odierna dalla media (la giornata è ancora in
     * corso), ma non dall'elenco/sparkline. Il bucketing usa `readRecords`
     * grezzi con `ZoneId.systemDefault()` calcolato da JARVIS stesso, non
     * `aggregateGroupByPeriod` (il bucket-per-giorno della libreria, il cui
     * fuso interno non è mai stato documentabile in questo ambiente e ha
     * già prodotto uno spostamento di un giorno osservato su dispositivo).
     * Il sonno è attribuito al giorno del **risveglio** (`endTime`), la
     * stessa convenzione di Honor Health. Ogni singolo caso di questa storia
     * (mezzanotte attraversata, notte appena conclusa, fasi di veglia
     * escluse dalla durata, cambio DST) è ora pinnato da un test JVM reale
     * in `core/health/HealthDailySeriesTest.kt` — se un futuro cambiamento
     * rompe uno di questi casi, fallisce lì, non a un dodicesimo screenshot
     * dell'utente.
     */
    private val awakeStageTypes = setOf(
        SleepSessionRecord.STAGE_TYPE_AWAKE,
        SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED,
    )

    /**
     * La sola parte Android di questa funzione è il fetch stesso — ogni
     * conversione di fuso/data/aggregazione è delegata a
     * [HealthDailySeries] (`:core`, JVM puro), coperta da una suite di test
     * eseguibile per davvero in questo repository (§ nessuna infrastruttura
     * Robolectric/instrumented esiste qui, quindi qualunque logica rimasta
     * dietro un tipo Android può solo essere riletta a occhio, mai provata
     * da un test in esecuzione) — incluso il caso specifico segnalato
     * dall'utente (sonno che attraversa la mezzanotte, notte appena
     * trascorsa, un giorno senza dato). [DiagnosticSnapshot] è scritta qui,
     * subito dopo il fetch grezzo e prima di qualunque bucketing, così un
     * conteggio zero o un'eccezione reale sono visibili anche se il resto
     * della funzione fallisse in modo inatteso.
     */
    private suspend fun fetchDailySeries(): List<DailyHealthReading> {
        val c = client ?: return emptyList()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val range = HealthDailySeries.queryRange(today, zone, Instant.now())
        val hcRange = TimeRangeFilter.between(range.start, range.endInclusive)
        return runCatching {
            val restingHeartRateRecords = readAllRecords(c, RestingHeartRateRecord::class, hcRange)
            val sleepRecords = readAllRecords(c, SleepSessionRecord::class, hcRange)

            _diagnostic.value = DiagnosticSnapshot(
                queriedRangeStart = range.start,
                queriedRangeEnd = range.endInclusive,
                restingHeartRateRecordCount = restingHeartRateRecords.size,
                sleepSessionRecordCount = sleepRecords.size,
                lastHeartRateSampleAt = restingHeartRateRecords.maxOfOrNull { it.time },
                lastSleepSessionEndAt = sleepRecords.maxOfOrNull { it.endTime },
                refreshAttemptedAtMs = System.currentTimeMillis(),
                refreshSucceededAtMs = _diagnostic.value?.refreshSucceededAtMs,
                lastErrorType = null,
            )
            Log.d(
                TAG,
                "fetch_daily_series hr_records=${restingHeartRateRecords.size} sleep_records=${sleepRecords.size}",
            )

            val heartRateSamples = restingHeartRateRecords.map { HeartRateSample(it.time, it.beatsPerMinute) }
            val sleepSessions = sleepRecords.map { record ->
                SleepSessionSpan(
                    startTime = record.startTime,
                    endTime = record.endTime,
                    stages = record.stages.map { stage ->
                        SleepStageSpan(awake = stage.stage in awakeStageTypes, start = stage.startTime, end = stage.endTime)
                    },
                )
            }
            HealthDailySeries.dailySeries(heartRateSamples, sleepSessions, zone, today)
                .map { DailyHealthReading(it.date, it.heartRateBpm, it.sleepHours) }
        }.onFailure { e ->
            Log.w(TAG, "fetch_daily_series_failed ${e.javaClass.simpleName}")
            _diagnostic.value = _diagnostic.value?.copy(
                refreshAttemptedAtMs = System.currentTimeMillis(),
                lastErrorType = e.javaClass.simpleName,
            ) ?: DiagnosticSnapshot(
                queriedRangeStart = range.start,
                queriedRangeEnd = range.endInclusive,
                restingHeartRateRecordCount = 0,
                sleepSessionRecordCount = 0,
                lastHeartRateSampleAt = null,
                lastSleepSessionEndAt = null,
                refreshAttemptedAtMs = System.currentTimeMillis(),
                refreshSucceededAtMs = null,
                lastErrorType = e.javaClass.simpleName,
            )
        }.getOrDefault(emptyList())
    }

    private fun computeAverages(daily: List<DailyHealthReading>, today: LocalDate): WeeklyHealthAverages {
        val coreDaily = daily.map { com.simone.jarvismobile.core.health.DailyHealthReading(it.date, it.heartRateBpm, it.sleepHours) }
        val averages = HealthDailySeries.computeAverages(coreDaily, today)
        return WeeklyHealthAverages(averages.avgHeartRateBpm, averages.avgSleepPerNight)
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
        if (daily.isEmpty()) {
            Log.w(TAG, "refresh_empty_daily_series")
            return null
        }
        val nowMs = System.currentTimeMillis()
        val snapshot = HealthSnapshot(daily, computeAverages(daily, LocalDate.now()), updatedAtMs = nowMs)
        _diagnostic.value = _diagnostic.value?.copy(refreshSucceededAtMs = nowMs)
        runCatching { settings.setHealthDailyCache(snapshot.toCacheJson(nowMs)) }
            .onFailure { e -> Log.w(TAG, "health_cache_write_failed ${e.javaClass.simpleName}") }
        return snapshot
    }

    /** Ultimo snapshot salvato — istantaneo, nessuna lettura da Health Connect (§ stesso pattern di [com.simone.jarvismobile.weather.WeatherManager.cachedOutlook]). */
    suspend fun cachedSnapshot(): HealthSnapshot? = healthSnapshotFromCacheJson(settings.healthDailyCache.first())

    private companion object {
        const val TAG = "HealthConnectManager"
    }
}
