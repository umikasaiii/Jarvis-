package com.simone.jarvismobile.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simone.jarvismobile.health.HealthConnectManager
import com.simone.jarvismobile.weather.HourlyForecast
import com.simone.jarvismobile.weather.WeatherManager
import com.simone.jarvismobile.weather.WeeklyOutlook
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State for the tema Ares Home layout only ([AresHomeScreen]) — kept separate
 * from [DashboardViewModel] (which every theme's layout shares) so Classico/
 * Rosso/Rouge never pay for a weather-outlook fetch or a Health Connect check
 * they never render (§ richiesta esplicita dell'utente: "cambiano solo quelli
 * di questo tema senza toccare gli altri").
 */
@HiltViewModel
class AresViewModel @Inject constructor(
    private val weather: WeatherManager,
    private val health: HealthConnectManager,
) : ViewModel() {

    private val _outlook = MutableStateFlow<WeeklyOutlook?>(null)
    val outlook: StateFlow<WeeklyOutlook?> = _outlook.asStateFlow()

    private val _healthGranted = MutableStateFlow(false)
    val healthGranted: StateFlow<Boolean> = _healthGranted.asStateFlow()

    private val _healthAverages = MutableStateFlow<HealthConnectManager.WeeklyHealthAverages?>(null)
    val healthAverages: StateFlow<HealthConnectManager.WeeklyHealthAverages?> = _healthAverages.asStateFlow()

    /**
     * Andamento giornaliero, con data, per la sparkline e per il dettaglio a
     * tocco (§ richiesta esplicita: "un piccolo grafico a linea che varia in
     * base all'andamento dei numeri" + "se clicco... vorrei visualizzare la
     * media di ogni singolo giorno... compreso di data").
     */
    private val _healthDaily = MutableStateFlow<List<HealthConnectManager.DailyHealthReading>>(emptyList())
    val healthDaily: StateFlow<List<HealthConnectManager.DailyHealthReading>> = _healthDaily.asStateFlow()

    /**
     * Quando i numeri mostrati sono stati letti davvero da Health Connect
     * l'ultima volta — mai il momento in cui lo schermo li ha solo riletti
     * dalla cache (§ bug reale segnalato: "oggi non si aggiorna biometria",
     * nessun segnale di freschezza esisteva prima per distinguere un dato
     * di oggi da uno vecchio senza chiederlo).
     */
    private val _healthUpdatedAtMs = MutableStateFlow<Long?>(null)
    val healthUpdatedAtMs: StateFlow<Long?> = _healthUpdatedAtMs.asStateFlow()

    /**
     * § FASE 2A.8 RELEASE GATE E — [healthUpdatedAtMs] only says WHEN JARVIS
     * last successfully asked Health Connect (`lastFetchAt`); it says nothing
     * about how old the REAL data behind that answer is (`latestHealthRecordAt`)
     * — a refresh can succeed while Honor Health simply hasn't written a
     * newer record yet, and reporting only the fetch time would misleadingly
     * read as "the data is current". Re-exposes [HealthConnectManager.diagnostic]
     * as-is (already the source of both instants) rather than duplicating it.
     */
    val healthDiagnostic: StateFlow<HealthConnectManager.DiagnosticSnapshot?> = health.diagnostic

    val healthAvailable: Boolean get() = health.isAvailable
    /** Apre le impostazioni di Health Connect (§ richiesta esplicita: "aprire la pagina... ed io posso metterlo manualmente"). */
    fun healthSettingsIntent() = health.settingsIntent()

    /**
     * Diagnostica dei permessi (§ bug reale segnalato dall'utente: Health
     * Connect mostra i permessi concessi nelle sue Impostazioni, ma
     * `hasPermissions()` resta `false` anche dopo un riavvio completo
     * dell'app) — popolata solo quando l'accesso risulta negato, per non
     * fare una chiamata in più quando tutto funziona già.
     */
    private val _healthPermissionsDiagnostic = MutableStateFlow<String?>(null)
    val healthPermissionsDiagnostic: StateFlow<String?> = _healthPermissionsDiagnostic.asStateFlow()

    private val _hourly = MutableStateFlow<HourlyForecast?>(null)
    val hourly: StateFlow<HourlyForecast?> = _hourly.asStateFlow()

    private val _hourlyLoading = MutableStateFlow(false)
    val hourlyLoading: StateFlow<Boolean> = _hourlyLoading.asStateFlow()

    init {
        // Show the last-known outlook instantly (§ "salvato temporaneamente
        // in locale"), then let refreshWeather()'s fresh fetch overwrite it
        // once it resolves — never leaves the card blank while waiting.
        viewModelScope.launch { _outlook.value = weather.cachedOutlook() }
        refreshWeather()
        refreshHealth()
    }

    fun refreshWeather() {
        viewModelScope.launch {
            weather.fetchWeeklyOutlook()?.let { _outlook.value = it }
        }
    }

    /** [dayIndex]: 0 = oggi, 1..3 = i tre giorni di [WeeklyOutlook.upcoming]. */
    fun loadHourlyForecast(dayIndex: Int) {
        viewModelScope.launch {
            _hourlyLoading.value = true
            _hourly.value = weather.fetchHourlyForecast(dayIndex)
            _hourlyLoading.value = false
        }
    }

    fun clearHourlyForecast() {
        _hourly.value = null
    }

    private fun applySnapshot(snapshot: HealthConnectManager.HealthSnapshot) {
        _healthAverages.value = snapshot.averages
        _healthDaily.value = snapshot.daily
        _healthUpdatedAtMs.value = snapshot.updatedAtMs
    }

    /**
     * Chiamato all'apertura schermo e a ogni ON_RESUME. Mostra prima la
     * cache (istantanea, § stesso pattern di [WeatherManager.cachedOutlook]),
     * poi un refresh live la sovrascrive se riesce — così l'utente vede
     * subito qualcosa invece di uno schermo vuoto mentre Health Connect
     * risponde, e i dati restano comunque aggiornati appena aperti (oltre al
     * refresh automatico mattutino in ProactiveManager).
     */
    fun refreshHealth() {
        viewModelScope.launch {
            val granted = health.hasPermissions()
            _healthGranted.value = granted
            if (!granted) {
                _healthAverages.value = null
                _healthDaily.value = emptyList()
                _healthUpdatedAtMs.value = null
                _healthPermissionsDiagnostic.value = health.permissionsDiagnostic()
                return@launch
            }
            _healthPermissionsDiagnostic.value = null
            health.cachedSnapshot()?.let { applySnapshot(it) }
            health.refresh()?.let { applySnapshot(it) }
        }
    }
}
