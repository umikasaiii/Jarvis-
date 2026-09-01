package com.simone.jarvismobile.health

import android.content.Context
import android.content.Intent
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.simone.jarvismobile.data.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong
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
        }.getOrDefault(false)
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
            if (missing.isEmpty()) "concessi tutti (${granted.size} totali)" else "mancano ${missing.size}: ${missing.joinToString()}"
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

    /** [daily]: oldest first, 7 entries — yesterday back to 7 days before (§ vedi [fetchDailySeries]). */
    data class HealthSnapshot(
        val daily: List<DailyHealthReading>,
        val averages: WeeklyHealthAverages,
    )

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
     * **Finestra "da ieri a 7 giorni prima" (§ richiesta esplicita
     * dell'utente: "la media la deve calcolare da ieri a 7 giorni prima, non
     * da lunedì a domenica fisso")**: `end` è la mezzanotte di oggi (quindi
     * la fine di ieri), non `Instant.now()` — la giornata di oggi è ancora in
     * corso e mescolarne i dati parziali (specie il sonno di stanotte, non
     * ancora accaduto) avrebbe sporcato la media. Il range resta comunque
     * sempre una finestra scorrevole di 7 giorni, mai un lunedì-domenica di
     * calendario fisso.
     *
     * **Riscritta da zero, secondo bug reale trovato dal confronto diretto
     * con Honor Health (screenshot dell'utente con le stesse date)**: la
     * versione precedente usava `aggregateGroupByPeriod` (bucket giornalieri
     * pronti dalla libreria) e correggeva un bug di allineamento per
     * posizione con la data reale del bucket (`startTime`) — ma un confronto
     * diretto con Honor Health ha mostrato un caso concreto ancora sbagliato:
     * una sessione di sonno interamente nel 30 agosto (00:18–10:21, nessun
     * attraversamento di mezzanotte) veniva mostrata sotto "29 agosto" —
     * uno spostamento di un giorno che nessuna assunzione sulla posizione
     * poteva più spiegare. Causa più probabile: la libreria alpha non
     * documenta quale fuso orario usi internamente per affettare i bucket
     * giornalieri di `aggregateGroupByPeriod` (nessuna documentazione
     * raggiungibile in questo ambiente per verificarlo), quindi qualunque
     * comportamento — inclusa una conversione in UTC invece del fuso del
     * dispositivo — resta un'ipotesi non verificabile da qui.
     *
     * Invece di continuare a rincorrere il comportamento interno di
     * un'API opaca, questa versione legge i record **grezzi**
     * (`readRecords`, l'API Health Connect più fondamentale e documentata)
     * e fa **lei stessa** ogni conversione di fuso orario con
     * `ZoneId.systemDefault()` — lo stesso fuso del dispositivo, mai UTC —
     * così la data di ogni lettura è sotto il nostro controllo diretto,
     * non quello di un bucket interno non ispezionabile:
     * - **BPM**: media aritmetica di **ogni lettura di frequenza cardiaca a
     *   riposo** (`RestingHeartRateRecord`, § richiesta esplicita
     *   dell'utente: "vorrei che per bpm si riporti la media dei battiti a
     *   riposo") loggata quel giorno locale — un tipo di record Health
     *   Connect distinto dal flusso continuo `HeartRateRecord` usato
     *   in precedenza, e concettualmente lo stesso dato che Honor Health
     *   mostra come sua cifra principale ("Frequenza cardiaca a riposo").
     * - **Sonno**: ogni sessione è attribuita alla data locale del suo
     *   **risveglio** (`endTime`), non dell'inizio — la stessa convenzione
     *   che usa Honor Health stesso (una sessione 28→29 agosto compare lì
     *   sotto "29 ago", non "28 ago"), quindi il confronto diretto fra le
     *   due app torna ad avere senso. Più sessioni nello stesso giorno
     *   (es. sonno notturno + un pisolino finito lo stesso giorno) sommano
     *   le rispettive durate.
     *
     * **Onestà, limite non risolto da questa correzione**: se Honor Health
     * mostra dati per una notte che qui resta "—", non è (più) un bug di
     * lettura — è che quella sessione non è mai stata sincronizzata da
     * Honor Health a Health Connect (due archivi distinti, § nota generale
     * di questo file), un gap che nessun codice lato JARVIS può colmare.
     * Stessa cosa se manca la frequenza a riposo: dipende da cosa Honor
     * Health ha scritto (o non scritto) in Health Connect quel giorno, non
     * da come JARVIS lo legge.
     */
    private suspend fun fetchDailySeries(): List<DailyHealthReading> {
        val c = client ?: return emptyList()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val end = today.atStartOfDay(zone).toInstant()
        val start = end.minus(7, ChronoUnit.DAYS)
        val range = TimeRangeFilter.between(start, end)
        return runCatching {
            val restingHeartRateRecords = readAllRecords(c, RestingHeartRateRecord::class, range)
            val sleepRecords = readAllRecords(c, SleepSessionRecord::class, range)

            val bpmByDate = mutableMapOf<LocalDate, MutableList<Long>>()
            restingHeartRateRecords.forEach { record ->
                val date = record.time.atZone(zone).toLocalDate()
                bpmByDate.getOrPut(date) { mutableListOf() }.add(record.beatsPerMinute)
            }
            val sleepByDate = mutableMapOf<LocalDate, MutableList<Duration>>()
            sleepRecords.forEach { record ->
                val date = record.endTime.atZone(zone).toLocalDate()
                sleepByDate.getOrPut(date) { mutableListOf() }.add(Duration.between(record.startTime, record.endTime))
            }

            (7 downTo 1).map { daysAgo ->
                val date = today.minusDays(daysAgo.toLong())
                DailyHealthReading(
                    date = date,
                    heartRateBpm = bpmByDate[date]?.let { it.average().roundToLong() },
                    sleepHours = sleepByDate[date]?.let { sessions -> sessions.sumOf { it.toMinutes() } / 60.0 },
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
