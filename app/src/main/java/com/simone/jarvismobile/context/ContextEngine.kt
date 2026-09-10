package com.simone.jarvismobile.context

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.simone.jarvismobile.core.automation.rule.EvaluationContext
import com.simone.jarvismobile.core.context.ContextState
import com.simone.jarvismobile.core.context.ContextTransition
import com.simone.jarvismobile.core.context.PlaceFusion
import com.simone.jarvismobile.core.context.PlaceSignal
import com.simone.jarvismobile.core.tools.ToolOutcomeStatus
import com.simone.jarvismobile.core.weather.WeatherCategory
import com.simone.jarvismobile.core.weather.WeatherFreshnessPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps one observable answer to "what is going on?" (§1).
 *
 * Sources push facts in; the engine normalises them, fuses the location ones and
 * publishes [state]. It deliberately **pulls** nothing on a timer: everything
 * here is either an Android callback or a value read on demand, so the engine
 * costs nothing while the phone is idle (§24).
 *
 * The engine never decides what to *do* — it only says what is true, and how
 * sure it is. Deciding belongs to the automation gate, which is pure and
 * testable precisely because this class exists to keep Android out of it.
 */
@Singleton
class ContextEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val _state = MutableStateFlow(ContextState())
    val state = _state.asStateFlow()

    /** Only meaningful changes, never every sensor tick. */
    private val _transitions = MutableSharedFlow<ContextTransition>(extraBufferCapacity = 16)
    val transitions = _transitions.asSharedFlow()

    private val placeFusion = PlaceFusion()

    /** The day the first-unlock flag refers to, so it resets at midnight. */
    @Volatile private var unlockDay: LocalDate? = null

    // --- Facts pushed in by sources -------------------------------------

    /** A location observation. Returns the transition it caused, if any. */
    fun onPlaceSignal(signal: PlaceSignal): ContextTransition? {
        val transition = placeFusion.observe(signal)
        _state.update {
            it.copy(
                placeId = placeFusion.place,
                placeConfidence = placeFusion.confidence(signal.at),
                updatedAt = signal.at,
            )
        }
        transition?.let { publish(it) }
        return transition
    }

    /** Lets a dwell mature without new signals; called from periodic maintenance. */
    fun tickPlace(now: LocalDateTime = LocalDateTime.now()): ContextTransition? {
        val transition = placeFusion.tick(now)
        if (transition != null) {
            _state.update {
                it.copy(
                    placeId = placeFusion.place,
                    placeConfidence = placeFusion.confidence(now),
                    updatedAt = now,
                )
            }
            publish(transition)
        }
        return transition
    }

    fun onActivity(activity: String?, confidence: Float, now: LocalDateTime = LocalDateTime.now()) {
        _state.update {
            it.copy(activity = activity, activityConfidence = confidence, updatedAt = now)
        }
    }

    fun onBluetooth(connected: Set<String>, now: LocalDateTime = LocalDateTime.now()) {
        _state.update {
            it.copy(connectedBluetooth = connected, bluetoothKnown = true, updatedAt = now)
        }
    }

    fun onMode(mode: String?, now: LocalDateTime = LocalDateTime.now()) {
        _state.update { it.copy(jarvisMode = mode, updatedAt = now) }
    }

    fun onInteractive(interactive: Boolean, now: LocalDateTime = LocalDateTime.now()) {
        _state.update { it.copy(interactive = interactive, updatedAt = now) }
    }

    /**
     * Records a screen unlock and answers whether it was the first one today.
     *
     * The flag is keyed to a date rather than being cleared by a scheduled job:
     * a job can be missed when the phone is off, and "first unlock of the day"
     * silently never firing again is exactly the kind of quiet breakage that is
     * impossible to notice.
     */
    fun onUnlock(now: LocalDateTime = LocalDateTime.now()): Boolean {
        val today = now.toLocalDate()
        val first = unlockDay != today
        unlockDay = today
        _state.update { it.copy(firstUnlockDone = true, interactive = true, updatedAt = now) }
        return first
    }

    fun onDriving(driving: Boolean, now: LocalDateTime = LocalDateTime.now()) {
        _state.update { it.copy(driving = driving, updatedAt = now) }
    }

    /**
     * Records the latest weather refresh (§ opt-in, phase "meteo"). All values
     * are null when the fetch itself failed or returned nothing usable — the
     * refresher's job is to say what it actually knows, never to guess.
     */
    fun onWeather(
        rainToday: Boolean?,
        rainTomorrow: Boolean?,
        todayWeather: WeatherCategory? = null,
        now: LocalDateTime = LocalDateTime.now(),
        // § JARVIS Implementation Master Plan PASSAGGIO 14.2 — structured
        // facts for tomorrow (category + expected accumulation), additive
        // and defaulted so every pre-existing caller is unaffected. Kept
        // separate from [rainTomorrow]'s pre-collapsed boolean: the evening
        // rain/storm alert policy needs the hazard TIER, which a plain
        // boolean cannot express.
        tomorrowWeather: WeatherCategory? = null,
        tomorrowMillimeters: Double? = null,
    ) {
        _state.update {
            it.copy(
                rainToday = rainToday,
                rainTomorrow = rainTomorrow,
                todayWeather = todayWeather,
                tomorrowWeather = tomorrowWeather,
                tomorrowMillimeters = tomorrowMillimeters,
                weatherUpdatedAt = now,
                updatedAt = now,
            )
        }
    }

    // --- Facts read on demand -------------------------------------------

    /**
     * Refreshes the cheap device facts. Reading them when they are needed avoids
     * keeping receivers alive for values Android will hand over instantly.
     */
    fun refreshDeviceState(now: LocalDateTime = LocalDateTime.now()) {
        val battery = readBattery()
        val network = readNetwork()
        _state.update {
            it.copy(
                batteryPercent = battery?.first,
                charging = battery?.second,
                networkAvailable = network,
                updatedAt = now,
            )
        }
    }

    /**
     * The snapshot the automation gate evaluates conditions against.
     *
     * Unknown facts stay null on purpose: the gate treats them as "cannot tell"
     * and refuses to fire, which is the whole reason a rule cannot go off on a
     * guess when a sensor is unavailable.
     */
    fun evaluationContext(
        now: LocalDateTime = LocalDateTime.now(),
        lastExecutionAt: LocalDateTime? = null,
        hasCalendarEvent: Boolean? = null,
    ): EvaluationContext {
        refreshDeviceState(now)
        val s = _state.value
        // A forecast older than the refresh window is worth less than admitting
        // we don't know — the periodic refresher should have replaced it by now,
        // so a stale value usually means the refresher itself is failing.
        val weatherFresh = !WeatherFreshnessPolicy.isStale(s.weatherUpdatedAt, now)
        return EvaluationContext(
            now = now,
            placeId = s.placeId,
            activity = s.activity,
            batteryPercent = s.batteryPercent,
            charging = s.charging,
            networkAvailable = s.networkAvailable,
            connectedBluetooth = s.connectedBluetooth,
            bluetoothKnown = s.bluetoothKnown,
            jarvisMode = s.jarvisMode,
            hasCalendarEvent = hasCalendarEvent,
            lastExecutionAt = lastExecutionAt,
            rainToday = if (weatherFresh) s.rainToday else null,
            rainTomorrow = if (weatherFresh) s.rainTomorrow else null,
        )
    }

    /**
     * Today's dominant weather category, for the morning-greeting emoji — kept
     * separate from [evaluationContext] since the rule/condition engine never
     * needs the category, only the plain rain booleans already in
     * [EvaluationContext]. Same staleness gate as the rest of the weather
     * facts: a forecast older than the refresh window reads as unknown here
     * too, rather than a frozen icon.
     */
    fun todayWeather(now: LocalDateTime = LocalDateTime.now()): WeatherCategory? {
        val s = _state.value
        return if (WeatherFreshnessPolicy.isStale(s.weatherUpdatedAt, now)) null else s.todayWeather
    }

    /**
     * § JARVIS Implementation Master Plan — PASSAGGIO 14.2. Tomorrow's
     * structured forecast facts for the evening rain/storm alert policy —
     * same staleness discipline as [todayWeather] for the VALUES themselves
     * (null when stale, never a frozen forecast), but also reporting WHY as
     * a [ToolOutcomeStatus] a diagnostic can show: [ToolOutcomeStatus.DATA_UNAVAILABLE]
     * when weather has never been pushed at all, [ToolOutcomeStatus.STALE]
     * when it has but the window has lapsed, [ToolOutcomeStatus.SUCCESS_DATA]
     * otherwise (which does NOT itself guarantee [category] is non-null — a
     * successful, fresh push can still genuinely lack a category if the
     * provider omitted it; that distinction belongs to the policy
     * consumer, not this accessor).
     */
    fun tomorrowForecastFacts(now: LocalDateTime = LocalDateTime.now()): TomorrowForecastFacts {
        val s = _state.value
        val stale = WeatherFreshnessPolicy.isStale(s.weatherUpdatedAt, now)
        val status = when {
            s.weatherUpdatedAt == null -> ToolOutcomeStatus.DATA_UNAVAILABLE
            stale -> ToolOutcomeStatus.STALE
            else -> ToolOutcomeStatus.SUCCESS_DATA
        }
        return TomorrowForecastFacts(
            category = if (stale) null else s.tomorrowWeather,
            millimeters = if (stale) null else s.tomorrowMillimeters,
            dataStatus = status,
        )
    }

    /** Privacy-safe line for the diagnostics screen. */
    fun describe(): String = _state.value.describe()

    // --- Android reads ---------------------------------------------------

    /** Battery level and charging state, or null when unreadable. */
    private fun readBattery(): Pair<Int, Boolean>? = runCatching {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return@runCatching null
        val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (level !in 0..100) return@runCatching null
        level to manager.isCharging
    }.getOrNull()

    /**
     * Whether a validated internet connection exists. Null — not false — when it
     * cannot be determined, so "quando c'è rete" does not fire on ignorance.
     */
    private fun readNetwork(): Boolean? = runCatching {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@runCatching null
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
            ?: return@runCatching false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrNull()

    private fun publish(transition: ContextTransition) {
        Log.i(TAG, "context ${transition.from ?: "?"}->${transition.to ?: "?"} ${transition.reason}")
        _transitions.tryEmit(transition)
    }

    private companion object {
        const val TAG = "JarvisContext"
    }
}

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 14.2. See
 * [ContextEngine.tomorrowForecastFacts]. [dataStatus] is one of
 * [ToolOutcomeStatus.SUCCESS_DATA]/[ToolOutcomeStatus.STALE]/[ToolOutcomeStatus.DATA_UNAVAILABLE]
 * only — [ToolOutcomeStatus.SOURCE_FAILURE] is NOT decidable from stored
 * state alone (a failed fetch still leaves the previous values untouched
 * here); the caller combines this with the weather source's own fetch
 * diagnostic (`WeatherManager.rainFetchDiagnostic`) for that distinction.
 */
data class TomorrowForecastFacts(
    val category: WeatherCategory?,
    val millimeters: Double?,
    val dataStatus: ToolOutcomeStatus,
)

/**
 * A source of context facts (§2).
 *
 * Sources are started and stopped by the engine's owner, not by themselves, so
 * nothing keeps a receiver or a subscription alive after the feature that needed
 * it is switched off. A source that cannot run (missing permission, absent
 * hardware) must simply not start, and say so — never pretend to be listening.
 */
interface ContextSource {
    val name: String

    /** True when this source can actually deliver on this device right now. */
    fun isAvailable(): Boolean

    fun start()
    fun stop()
}

/**
 * Power and charging, from the sticky battery broadcast. Needs no permission and
 * costs nothing while nothing changes, which is why it is a receiver rather than
 * a poll.
 */
@Singleton
class PowerContextSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: ContextEngine,
) : ContextSource {

    override val name = "power"

    private var receiver: BroadcastReceiver? = null

    override fun isAvailable(): Boolean = true

    override fun start() {
        if (receiver != null) return
        val handler = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                engine.refreshDeviceState()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
        }
        // ContextCompat with an explicit export flag, matching
        // AutomationEventService. These are protected system broadcasts so the
        // flag is not strictly required, but being explicit keeps the app off
        // the path where a stricter ROM or a future SDK refuses the plain call.
        runCatching {
            ContextCompat.registerReceiver(
                context, handler, filter, ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
            .onSuccess { receiver = handler }
            .onFailure { Log.w(TAG, "power_source_failed ${it.javaClass.simpleName}") }
        engine.refreshDeviceState()
    }

    override fun stop() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    private companion object {
        const val TAG = "JarvisContext"
    }
}
