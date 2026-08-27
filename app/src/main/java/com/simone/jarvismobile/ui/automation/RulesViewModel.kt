package com.simone.jarvismobile.ui.automation

import android.annotation.SuppressLint
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simone.jarvismobile.automation.rule.AutomationExecutor
import com.simone.jarvismobile.automation.rule.AutomationPlaceEntity
import com.simone.jarvismobile.automation.rule.ExecutionLogRepository
import com.simone.jarvismobile.automation.rule.ExecutionRecord
import com.simone.jarvismobile.automation.rule.PlaceGeofenceSource
import com.simone.jarvismobile.automation.rule.PlaceRepository
import com.simone.jarvismobile.automation.rule.ParkingLocationEntity
import com.simone.jarvismobile.automation.rule.ParkingRepository
import com.simone.jarvismobile.automation.rule.RuleRepository
import com.simone.jarvismobile.automation.rule.RuleScheduler
import com.simone.jarvismobile.context.ContextEngine
import com.simone.jarvismobile.core.navigation.Geo
import com.simone.jarvismobile.core.navigation.LatLng
import com.simone.jarvismobile.core.automation.rule.ActionRegistry
import com.simone.jarvismobile.core.automation.rule.ActionSpec
import com.simone.jarvismobile.core.automation.rule.AutomationRule
import com.simone.jarvismobile.core.automation.rule.Condition
import com.simone.jarvismobile.core.automation.rule.RuleSchedule
import com.simone.jarvismobile.core.automation.rule.TriggerEvent
import com.simone.jarvismobile.core.automation.rule.TriggerRegistry
import com.simone.jarvismobile.core.automation.rule.TriggerSpec
import com.simone.jarvismobile.core.mode.JarvisModes
import com.simone.jarvismobile.data.SettingsRepository
import com.simone.jarvismobile.mode.JarvisModeManager
import com.simone.jarvismobile.navigation.NavigationLocationProvider
import com.simone.jarvismobile.weather.WeatherScheduler
import java.time.DayOfWeek
import java.time.LocalDateTime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject

/**
 * The generic engine's own screen: create a rule and see it fire (phases 5-6).
 *
 * Deliberately separate from the old-engine [AutomationsViewModel]: this drives
 * the Room-backed [RuleRepository] and the exact-alarm / geofence schedulers, and
 * it only offers trigger kinds that have a live delivery path today — clock and
 * place — so nothing can be armed that could never fire.
 */
@HiltViewModel
class RulesViewModel @Inject constructor(
    private val rules: RuleRepository,
    private val places: PlaceRepository,
    private val geofences: PlaceGeofenceSource,
    private val scheduler: RuleScheduler,
    private val navLocation: NavigationLocationProvider,
    private val executor: AutomationExecutor,
    private val contextEngine: ContextEngine,
    private val parking: ParkingRepository,
    private val log: ExecutionLogRepository,
    private val modes: JarvisModeManager,
    private val settings: SettingsRepository,
    private val weatherScheduler: WeatherScheduler,
) : ViewModel() {

    val currentMode: StateFlow<String> = modes.mode

    val weatherEnabled: StateFlow<Boolean> =
        settings.weatherEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Empty means "no place chosen yet" — [com.simone.jarvismobile.weather.WeatherManager] falls back to GPS. */
    val weatherPlaceId: StateFlow<String> =
        settings.weatherPlaceId.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /** Opt-in toggle for the weather feature (§ the deliberate online exception). */
    fun setWeatherEnabled(value: Boolean) = viewModelScope.launch {
        settings.setWeatherEnabled(value)
        runCatching { weatherScheduler.sync() }
    }

    /** The saved place (among [savedPlaces]) to check the forecast for; "" = last-known GPS fix. */
    fun setWeatherPlaceId(id: String) = viewModelScope.launch {
        settings.setWeatherPlaceId(id)
    }

    fun hasDndAccess(): Boolean = modes.hasDndAccess()

    /** Manual mode switch from the UI. */
    fun switchMode(id: String) = viewModelScope.launch {
        val ok = modes.setMode(id)
        val label = JarvisModes.profile(id)?.label ?: id
        _message.value = if (ok) "Modalità: $label." else "Modalità sconosciuta."
    }

    /** Recent firings and non-firings, for the diagnostics card (§21-§22). */
    val recentExecutions: StateFlow<List<ExecutionRecord>> =
        log.observeRecent(40).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val savedRules: StateFlow<List<AutomationRule>> =
        rules.observeRules().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val savedPlaces: StateFlow<List<AutomationPlaceEntity>> =
        places.observePlaces().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val savedParking: StateFlow<ParkingLocationEntity?> =
        parking.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _message = kotlinx.coroutines.flow.MutableStateFlow("")
    val message: StateFlow<String> = _message

    fun hasForegroundLocation(): Boolean = geofences.hasForegroundLocation()
    fun hasBackgroundLocation(): Boolean = geofences.hasBackgroundLocation()

    /** Re-arm after the location permission changes from the OS dialog. */
    fun onLocationPermissionChanged() = viewModelScope.launch {
        runCatching { places.reload() }
    }

    // --- places ----------------------------------------------------------

    /**
     * Captures the phone's current GNSS position once and saves it as [name].
     * Reads location a single time here; from then on the OS watches the fence.
     */
    @SuppressLint("MissingPermission")
    fun captureCurrentPlace(name: String, radiusMeters: Float) {
        val clean = name.trim()
        if (clean.isEmpty()) {
            _message.value = "Dai un nome al luogo, per esempio «casa»."
            return
        }
        if (!navLocation.hasPermission()) {
            _message.value = "Serve il permesso di posizione per salvare un luogo."
            return
        }
        if (!navLocation.gpsEnabled()) {
            _message.value = "Attiva il GPS per salvare la posizione attuale."
            return
        }
        viewModelScope.launch {
            val fix = withTimeoutOrNull(POSITION_TIMEOUT_MS) {
                runCatching { navLocation.fixes(intervalMs = 1_000L).first() }.getOrNull()
            }
            if (fix == null) {
                _message.value = "Posizione non disponibile ora. Riprova all'aperto."
                return@launch
            }
            val saved = places.saveNamedPlace(
                name = clean,
                latitude = fix.location.lat,
                longitude = fix.location.lon,
                radiusMeters = radiusMeters,
            )
            _message.value = if (saved != null) {
                "Luogo salvato: ${saved.displayName} (raggio ${saved.radiusMeters.toInt()} m)."
            } else {
                "Non sono riuscito a salvare il luogo."
            }
        }
    }

    fun deletePlace(place: AutomationPlaceEntity) = viewModelScope.launch {
        places.delete(place.id)
        _message.value = "Luogo eliminato: ${place.displayName}."
    }

    private suspend fun oneShotFix(): com.simone.jarvismobile.core.navigation.GpsFix? =
        withTimeoutOrNull(POSITION_TIMEOUT_MS) {
            runCatching { navLocation.fixes(intervalMs = 1_000L).first() }.getOrNull()
        }

    // --- parking (phase 8, GMS-free) -------------------------------------

    @SuppressLint("MissingPermission")
    fun saveParkingHere() {
        if (!navLocation.hasPermission()) {
            _message.value = "Serve il permesso di posizione."
            return
        }
        if (!navLocation.gpsEnabled()) {
            _message.value = "Attiva il GPS per salvare il parcheggio."
            return
        }
        viewModelScope.launch {
            val fix = oneShotFix()
            if (fix == null) {
                _message.value = "Posizione non disponibile ora. Riprova all'aperto."
                return@launch
            }
            parking.save(
                latitude = fix.location.lat,
                longitude = fix.location.lon,
                accuracyMeters = fix.accuracyMeters.takeIf { it < Float.MAX_VALUE },
                label = "manuale",
            )
            _message.value = "Parcheggio salvato qui."
        }
    }

    @SuppressLint("MissingPermission")
    fun distanceToParking() {
        val p = savedParking.value ?: run {
            _message.value = "Nessun parcheggio salvato."
            return
        }
        if (!navLocation.hasPermission()) {
            _message.value = "Serve il permesso di posizione."
            return
        }
        viewModelScope.launch {
            val fix = oneShotFix() ?: run {
                _message.value = "Posizione non disponibile ora."
                return@launch
            }
            val meters = Geo.distanceMeters(
                LatLng(fix.location.lat, fix.location.lon),
                LatLng(p.latitude, p.longitude),
            )
            _message.value = "Sei a circa ${meters.toInt()} m dal parcheggio."
        }
    }

    fun clearParking() = viewModelScope.launch {
        parking.clear()
        _message.value = "Parcheggio cancellato."
    }

    // --- rules -----------------------------------------------------------

    /**
     * Creates a rule from the structured choices in the builder. Only clock and
     * place triggers are offered, and both have a live source, so a saved rule
     * can always actually fire.
     */
    fun createRule(draft: RuleDraft) = viewModelScope.launch {
        val trigger = draft.triggerSpec()
        if (trigger == null) {
            _message.value = draft.triggerError ?: "Completa il quando."
            return@launch
        }
        val action = draft.actionSpecOrNull()
        if (action == null) {
            _message.value = draft.actionError ?: "Completa il cosa fare."
            return@launch
        }
        val rule = AutomationRule(
            id = UUID.randomUUID().toString().take(8),
            name = draft.ruleName(),
            triggers = listOf(trigger),
            condition = draft.conditionOrNull(),
            actions = listOf(action),
            cooldownSeconds = draft.cooldownSecondsOrDefault(),
        )
        val errors = rules.save(rule)
        if (errors.isNotEmpty()) {
            _message.value = "Non valida: ${errors.first()}"
            return@launch
        }
        // Clock rules need the alarm booked; place rules ride the place's geofence.
        runCatching { scheduler.sync() }
        _message.value = "Regola creata."
    }

    fun deleteRule(rule: AutomationRule) = viewModelScope.launch {
        rules.delete(rule.id)
        runCatching { scheduler.sync() }
        _message.value = "Regola eliminata."
    }

    fun toggleRule(rule: AutomationRule) = viewModelScope.launch {
        rules.setEnabled(rule.id, !rule.enabled)
        runCatching { scheduler.sync() }
    }

    /**
     * Dry-runs a rule now and reports the gate's verdict — the offline way to
     * check "scatterebbe? e se no, perché?" without waiting for its real trigger.
     * Nothing happens for real: [AutomationExecutor.onTrigger] is called with
     * dryRun = true, so no notification, no speech, no side effect.
     */
    fun testRule(rule: AutomationRule) = viewModelScope.launch {
        val trigger = rule.triggers.firstOrNull() ?: run {
            _message.value = "La regola non ha un trigger."
            return@launch
        }
        val now = LocalDateTime.now()
        val dedup = if (trigger.type.startsWith("PLACE_")) trigger.params["placeId"].orEmpty() else ""
        val event = TriggerEvent(type = trigger.type, at = now, dedupKey = dedup)
        val evalContext = contextEngine.evaluationContext(now = now)
        val reports = runCatching { executor.onTrigger(event, evalContext, dryRun = true) }
            .getOrElse { emptyList() }
        val report = reports.firstOrNull { it.rule.id == rule.id }
        _message.value = when {
            report == null -> "Prova: nessuna valutazione (trigger non corrispondente)."
            report.decision.fired -> "Prova: scatterebbe adesso. ✓"
            else -> "Prova: non scatterebbe ora — ${report.reason}"
        }
    }

    fun clearMessage() { _message.value = "" }

    private companion object {
        const val POSITION_TIMEOUT_MS = 12_000L
    }
}

/** The builder's current choices, turned into a [TriggerSpec] + condition on save. */
data class RuleDraft(
    val kind: TriggerKind,
    val time: String = "",
    val placeId: String? = null,
    val actionType: String = ActionRegistry.SHOW_NOTIFICATION,
    val message: String = "",
    val mode: String = "",
    // Optional "SE" filters, combined with AND. Empty = no filter.
    val days: Set<DayOfWeek> = emptySet(),
    val onlyCharging: Boolean = false,
    val onlyIfRainTomorrow: Boolean = false,
    val timeFrom: String = "",
    val timeTo: String = "",
) {
    var triggerError: String? = null
        private set
    var actionError: String? = null
        private set

    /** The action from the chosen type: a message action, or a mode switch. */
    fun actionSpecOrNull(): ActionSpec? = when (actionType) {
        ActionRegistry.SET_JARVIS_MODE -> {
            if (mode.isBlank()) { actionError = "Scegli una modalità."; null }
            else ActionSpec(ActionRegistry.SET_JARVIS_MODE, mapOf("mode" to mode))
        }
        else -> {
            val body = message.trim()
            if (body.isEmpty()) { actionError = "Scrivi cosa deve dire o notificare."; null }
            else ActionSpec(actionType, mapOf("message" to body))
        }
    }

    fun ruleName(): String = when (actionType) {
        ActionRegistry.SET_JARVIS_MODE -> "modalità ${mode.ifBlank { "?" }}"
        else -> message.take(60).ifBlank { "regola" }
    }

    fun triggerSpec(): TriggerSpec? = when (kind) {
        TriggerKind.DAILY_TIME -> {
            val t = RuleSchedule.parseTime(time)
            if (t == null) { triggerError = "Ora non valida (usa HH:mm, es. 08:00)."; null }
            else TriggerSpec(TriggerRegistry.RECURRING_TIME, mapOf("time" to "%02d:%02d".format(t.hour, t.minute)))
        }
        TriggerKind.PLACE_ARRIVE -> placeId?.let { TriggerSpec(TriggerRegistry.PLACE_ENTER, mapOf("placeId" to it)) }
            ?: run { triggerError = "Scegli un luogo."; null }
        TriggerKind.PLACE_LEAVE -> placeId?.let { TriggerSpec(TriggerRegistry.PLACE_EXIT, mapOf("placeId" to it)) }
            ?: run { triggerError = "Scegli un luogo."; null }
        TriggerKind.CHARGING -> TriggerSpec(TriggerRegistry.DEVICE_CHARGING)
        TriggerKind.UNPLUGGED -> TriggerSpec(TriggerRegistry.DEVICE_UNPLUGGED)
        TriggerKind.FIRST_UNLOCK -> TriggerSpec(TriggerRegistry.FIRST_UNLOCK_OF_DAY)
    }

    /**
     * "Al primo sblocco" has no calendar-day primitive in the generic gate (its
     * cooldown is a fixed gap, not a midnight reset) — a minimum gap of most of a
     * day is the honest approximation, same idea as the old engine's per-day
     * SharedPreferences key but expressed the way this engine already understands
     * "don't fire again too soon". Combine with "Dalle/Alle" below for a real
     * "non prima delle 7" restriction; that condition already exists generically.
     */
    fun cooldownSecondsOrDefault(): Long = if (kind == TriggerKind.FIRST_UNLOCK) FIRST_UNLOCK_MIN_GAP_SECONDS else 0L

    /** The AND of the enabled filters, or null when the user set none. */
    fun conditionOrNull(): Condition? {
        val parts = mutableListOf<Condition>()
        if (days.isNotEmpty()) parts += Condition.DayOfWeekIn(days)
        if (onlyCharging) parts += Condition.IsCharging
        if (onlyIfRainTomorrow) parts += Condition.RainTomorrow
        val from = RuleSchedule.parseTime(timeFrom)
        val to = RuleSchedule.parseTime(timeTo)
        if (from != null && to != null) parts += Condition.TimeRange(from, to)
        return when {
            parts.isEmpty() -> null
            parts.size == 1 -> parts.first()
            else -> Condition.All(parts)
        }
    }

    private companion object {
        const val FIRST_UNLOCK_MIN_GAP_SECONDS = 20 * 3600L
    }
}

/** The trigger kinds the builder offers — only the ones with a live source. */
enum class TriggerKind(val label: String) {
    DAILY_TIME("Ogni giorno a un'ora"),
    PLACE_ARRIVE("Quando arrivo a un luogo"),
    PLACE_LEAVE("Quando esco da un luogo"),
    CHARGING("Quando metto in carica"),
    UNPLUGGED("Quando tolgo dalla carica"),
    FIRST_UNLOCK("Al primo sblocco del giorno"),
}
