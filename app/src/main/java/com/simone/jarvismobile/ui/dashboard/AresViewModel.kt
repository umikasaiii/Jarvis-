package com.simone.jarvismobile.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simone.jarvismobile.health.HealthConnectManager
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

    val healthAvailable: Boolean get() = health.isAvailable
    val healthPermissions: Set<String> get() = health.permissions
    fun healthPermissionContract() = health.requestPermissionsContract()

    init {
        refreshWeather()
        refreshHealth()
    }

    fun refreshWeather() {
        viewModelScope.launch { _outlook.value = weather.fetchWeeklyOutlook() }
    }

    /** Called on screen resume and right after the permission dialog closes. */
    fun refreshHealth() {
        viewModelScope.launch {
            val granted = health.hasPermissions()
            _healthGranted.value = granted
            _healthAverages.value = if (granted) health.weeklyAverages() else null
        }
    }
}
