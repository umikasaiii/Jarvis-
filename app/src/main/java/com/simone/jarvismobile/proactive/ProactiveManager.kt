package com.simone.jarvismobile.proactive

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.simone.jarvismobile.agenda.AgendaRepository
import com.simone.jarvismobile.audio.SessionCoordinator
import com.simone.jarvismobile.context.ContextEngine
import com.simone.jarvismobile.core.proactive.OccurrenceClaimOutcome
import com.simone.jarvismobile.core.proactive.ProactiveComposer
import com.simone.jarvismobile.core.proactive.ProactiveDecision
import com.simone.jarvismobile.core.proactive.ProactiveGovernor
import com.simone.jarvismobile.core.proactive.ProactiveKind
import com.simone.jarvismobile.core.proactive.ProactiveOccurrenceKey
import com.simone.jarvismobile.core.proactive.ProactiveSettings
import com.simone.jarvismobile.core.proactive.ProactiveSnapshot
import com.simone.jarvismobile.core.proactive.ProactiveSuggestion
import com.simone.jarvismobile.core.tools.ToolOutcomeStatus
import com.simone.jarvismobile.core.weather.WeatherAlertEvaluation
import com.simone.jarvismobile.core.weather.WeatherAlertPolicy
import com.simone.jarvismobile.core.weather.WeatherCategory
import com.simone.jarvismobile.core.weather.WeatherHazard
import com.simone.jarvismobile.data.SettingsRepository
import com.simone.jarvismobile.health.HealthConnectManager
import com.simone.jarvismobile.weather.WeatherManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Android side of proactivity: it gathers the real signals, asks the pure
 * [ProactiveGovernor] whether to say anything, and — if so — posts one discreet
 * "Suggerimenti" notification. It never decides on its own what is allowed; the
 * governor and the user's saved settings do. Called periodically by a worker.
 */
@Singleton
class ProactiveManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val agenda: AgendaRepository,
    private val store: ProactiveStore,
    private val occurrenceStore: ProactiveOccurrenceStore,
    private val notifier: ProactiveNotifier,
    private val coordinator: SessionCoordinator,
    private val contextEngine: ContextEngine,
    private val weather: WeatherManager,
    private val health: HealthConnectManager,
    private val morningTriggerScheduler: MorningTriggerScheduler,
) {
    /**
     * Called periodically by the worker as a coarse fallback (see
     * [evaluateOnUnlock]) — the only path that can ever deliver anything for
     * whoever hasn't enabled "Automazioni in background", since there is then
     * no real unlock event to react to.
     */
    suspend fun evaluate(now: LocalDateTime = LocalDateTime.now()) =
        run(now, isRealUnlock = false, triggerSource = "PERIODIC_FALLBACK")

    /**
     * Called at the real first-unlock-of-the-day event (§ "primo sblocco utile
     * della giornata"). Distinct from [evaluate] only in *when* it runs — both
     * go through the same [candidatesFor]/[MORNING_EARLIEST_HOUR] floor and the
     * same governor per-day dedup, so calling this promptly at the real unlock
     * (rather than waiting for the next coarse tick) is what makes the morning
     * digest feel immediate instead of arriving up to an hour late.
     */
    suspend fun evaluateOnUnlock(now: LocalDateTime = LocalDateTime.now(), triggerSource: String = "FIRST_UNLOCK") =
        run(now, isRealUnlock = true, triggerSource = triggerSource)

    /**
     * "Il briefing non è proprio arrivato" (non solo in ritardo, § segnalazioni
     * precedenti già corrette in questa stessa classe) — dopo tre round di fix
     * reali su tempistica/ore-silenziose/dedup senza un modo per l'utente di
     * vedere cosa succede davvero a un dato tentativo, questo registra l'esito
     * di **ogni** chiamata a [run] — inclusi gli early-return prima ancora di
     * costruire i candidati — così un futuro "non arriva" mostra un dato reale
     * (mai chiamato / disabilitato / nessun candidato all'ora X / ore silenziose /
     * budget esaurito / consegnato) invece di un'altra ipotesi. Mai il testo del
     * messaggio consegnato, solo il tipo e l'esito (§ "non loggare dati personali").
     *
     * [triggerSource] (§ FASE 2A.8 RELEASE GATE F — Multi-Signal Morning
     * Coordinator): which signal caused this call — `"HUAWEI_SLEEP"` (not
     * implemented, see [com.simone.jarvismobile.proactive.MorningTriggerScheduler]'s
     * own honesty note), `"NEXT_ALARM"`, `"CONFIGURED_TIME"`, `"FIRST_UNLOCK"`,
     * `"MANUAL"`, or `"PERIODIC_FALLBACK"`. Purely diagnostic — every source
     * converges on this SAME method and the SAME governor per-day dedup key
     * (`MORNING_DIGEST:<date>`), so no source can ever double-deliver.
     */
    data class RunDiagnostic(
        val ranAtMs: Long,
        val isRealUnlock: Boolean,
        val triggerSource: String,
        val enabled: Boolean,
        val automationServiceEnabled: Boolean,
        val hour: Int,
        val candidateCount: Int,
        val outcome: String,
    )

    private val _lastRun = MutableStateFlow<RunDiagnostic?>(null)
    val lastRun: StateFlow<RunDiagnostic?> = _lastRun.asStateFlow()

    /**
     * § JARVIS Implementation Master Plan — PASSAGGIO 14.2. Bounded evidence
     * for the evening rain/storm alert evaluation — "the next real missed
     * alert must be explainable rather than guessed", per the spec. Never
     * the briefing/notification text itself, only enum-shaped facts:
     * [dataStatus] is a [com.simone.jarvismobile.core.tools.ToolOutcomeStatus]
     * name (SUCCESS_DATA/STALE/SOURCE_FAILURE/DATA_UNAVAILABLE), [hazard] a
     * [WeatherHazard] name or null when the data status prevented a
     * decision, [governorOutcome] "delivered" or "suppressed:<reason>" —
     * null until the governor has actually run this evaluation.
     */
    data class WeatherAlertDiagnostic(
        val evaluatedAtMs: Long,
        val targetLocalDate: LocalDate,
        val policyVersion: Int,
        val dataStatus: String,
        val hazard: String?,
        val candidateCreated: Boolean,
        val occurrenceClaimed: Boolean,
        val governorOutcome: String?,
        val deliveryAttempted: Boolean,
        val delivered: Boolean,
    )

    private val _weatherAlertDiagnostic = MutableStateFlow<WeatherAlertDiagnostic?>(null)
    val weatherAlertDiagnostic: StateFlow<WeatherAlertDiagnostic?> = _weatherAlertDiagnostic.asStateFlow()

    private suspend fun run(now: LocalDateTime, isRealUnlock: Boolean, triggerSource: String) {
        val config = readSettings()
        val automationEnabled = settings.automationServiceEnabled.first()
        if (!config.enabled) {
            recordRun(now, isRealUnlock, triggerSource, config.enabled, automationEnabled, candidateCount = 0, outcome = "proactivity_disabled")
            return
        }
        val today = now.toLocalDate()
        val nowMs = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        // § JARVIS Implementation Master Plan PASSAGGIO 14.1 §E/§F/§G — the ONE
        // atomic claim gate for the morning-digest logical occurrence. Every
        // trigger source (NEXT_ALARM/CONFIGURED_TIME/FIRST_UNLOCK/PERIODIC_FALLBACK)
        // reaches this same method; only the caller that wins the durable claim
        // may ever compose/speak/deliver today's morning digest — every other
        // caller sees AlreadyOwned and must not include it as a candidate at all
        // (§G: a suppressed duplicate performs no side effect whatsoever).
        val offerMorning = isRealUnlock || !automationEnabled
        val morningEligible = now.hour >= MORNING_EARLIEST_HOUR && offerMorning
        val morningKey = if (morningEligible) ProactiveOccurrenceKey.morningDigest(today) else null
        val morningClaim = morningKey?.let { key ->
            runCatching {
                occurrenceStore.claim(key, kind = "MORNING_DIGEST", logicalDate = today, triggerSource = triggerSource, now = nowMs)
            }.getOrNull()
        }
        val morningOwnedThisRun = morningClaim is OccurrenceClaimOutcome.Claimed || morningClaim is OccurrenceClaimOutcome.TakeoverAllowed
        if (morningKey != null && !morningOwnedThisRun) {
            Log.i(TAG, "proactive_morning_claim_denied source=$triggerSource claim=$morningClaim")
        }

        val snap = snapshot(today, now)
        val candidates = candidatesFor(now, snap, today, includeMorning = morningOwnedThisRun).toMutableList()

        // § JARVIS Implementation Master Plan PASSAGGIO 14.2 — evening
        // rain/storm alert, evaluated in the SAME evening window
        // eveningDigest/batteryBeforeAlarm already use below (no second
        // scheduler): [snapshot] above has already forced a fresh
        // `weather.refresh()`, so this reads facts as current as this run
        // can make them.
        var weatherAlertKey: String? = null
        if (now.hour in EVENING_FROM..EVENING_TO) {
            val evalResult = evaluateWeatherAlert(now, today, triggerSource, nowMs)
            if (evalResult != null) {
                candidates += evalResult.suggestion
                weatherAlertKey = evalResult.occurrenceKey
            }
        }

        if (candidates.isEmpty()) {
            if (morningKey != null && morningOwnedThisRun) {
                runCatching { occurrenceStore.markFailedRetryable(morningKey, "no_candidate_this_hour") }
            }
            recordRun(now, isRealUnlock, triggerSource, config.enabled, automationEnabled, candidateCount = 0, outcome = "no_candidate_this_hour")
            return
        }
        val state = store.load().rolledTo(today)
        when (val decision = ProactiveGovernor.decide(candidates, config, state, now)) {
            is ProactiveDecision.Deliver -> {
                val deliveredKind = decision.suggestion.kind
                if (deliveredKind == ProactiveKind.MORNING_DIGEST && morningKey != null) {
                    runCatching { occurrenceStore.markDeliveryAttempt(morningKey) }
                }
                if (deliveredKind == ProactiveKind.WEATHER_ALERT && weatherAlertKey != null) {
                    runCatching { occurrenceStore.markDeliveryAttempt(weatherAlertKey) }
                }
                notifier.show(decision.suggestion)
                store.save(decision.newState)
                // Spoken too, same opt-in path a new-engine SPEAK action uses — an
                // adaptive briefing that only JARVIS reads silently isn't a briefing.
                runCatching { coordinator.speakBackgroundResponse(decision.suggestion.message) }
                Log.i(TAG, "proactive_deliver ${decision.suggestion.kind} source=$triggerSource")
                recordRun(now, isRealUnlock, triggerSource, config.enabled, automationEnabled, candidates.size, "delivered:${decision.suggestion.kind}")
                if (deliveredKind == ProactiveKind.MORNING_DIGEST && morningKey != null) {
                    runCatching { occurrenceStore.markDelivered(morningKey) }
                    // § FASE 2A.8 RELEASE GATE G — only for a REAL morning-digest
                    // delivery, never for the evening digest or a battery tip.
                    runCatching { morningTriggerScheduler.schedulePostBriefingRefreshes() }
                } else if (morningKey != null && morningOwnedThisRun) {
                    // The governor picked a different candidate this run (budget/
                    // priority) — release the claim so it isn't wasted (§L: never
                    // permanently blocks a later legitimate attempt).
                    runCatching { occurrenceStore.markFailedRetryable(morningKey, "governor_selected_other_candidate") }
                }
                if (deliveredKind == ProactiveKind.WEATHER_ALERT && weatherAlertKey != null) {
                    runCatching { occurrenceStore.markDelivered(weatherAlertKey) }
                    updateWeatherAlertOutcome("delivered", deliveryAttempted = true, delivered = true)
                } else if (weatherAlertKey != null) {
                    runCatching { occurrenceStore.markFailedRetryable(weatherAlertKey, "governor_selected_other_candidate") }
                    updateWeatherAlertOutcome("suppressed:governor_selected_other_candidate", deliveryAttempted = false, delivered = false)
                }
            }
            is ProactiveDecision.Skip -> {
                Log.i(TAG, "proactive_skip ${decision.reason} source=$triggerSource")
                recordRun(now, isRealUnlock, triggerSource, config.enabled, automationEnabled, candidates.size, "skip:${decision.reason}")
                if (morningKey != null && morningOwnedThisRun) {
                    runCatching { occurrenceStore.markFailedRetryable(morningKey, "skip:${decision.reason}") }
                }
                if (weatherAlertKey != null) {
                    runCatching { occurrenceStore.markFailedRetryable(weatherAlertKey, "skip:${decision.reason}") }
                    updateWeatherAlertOutcome("suppressed:skip:${decision.reason}", deliveryAttempted = false, delivered = false)
                }
            }
        }
    }

    private fun updateWeatherAlertOutcome(governorOutcome: String, deliveryAttempted: Boolean, delivered: Boolean) {
        _weatherAlertDiagnostic.value = _weatherAlertDiagnostic.value?.copy(
            governorOutcome = governorOutcome,
            deliveryAttempted = deliveryAttempted,
            delivered = delivered,
        )
    }

    private data class WeatherAlertEvalResult(val suggestion: ProactiveSuggestion, val occurrenceKey: String)

    /**
     * § JARVIS Implementation Master Plan — PASSAGGIO 14.2. Structured data
     * only, reusing the same [com.simone.jarvismobile.core.tools.ToolOutcomeStatus]
     * vocabulary the rest of this app's tool evidence already uses — never
     * a keyword/regex search over generated text, never an LLM judgement.
     * Always publishes a [WeatherAlertDiagnostic] before returning, even on
     * every early-out branch, so a future missed alert is explainable from
     * the diagnostic alone. Returns non-null ONLY when a real, atomically
     * claimed candidate should be added to this run's suggestion list —
     * every other outcome (data unavailable/stale/source failure, no
     * hazard, NO_ALERT, or the occurrence already owned by an earlier
     * evaluation today) is a pure no-op: no candidate, no claim held, no
     * notification.
     */
    private suspend fun evaluateWeatherAlert(
        now: LocalDateTime,
        today: LocalDate,
        triggerSource: String,
        nowMs: Long,
    ): WeatherAlertEvalResult? {
        val targetDate = WeatherAlertPolicy.targetDateFor(today)
        val weatherEnabled = settings.weatherEnabled.first()
        val rainDiag = weather.rainFetchDiagnostic.value
        val facts = contextEngine.tomorrowForecastFacts(now)
        // Priority: disabled > never-attempted-this-session > a real fetch
        // failure > whatever ContextEngine's own stored-state freshness
        // gate says (SUCCESS_DATA/STALE/DATA_UNAVAILABLE) — a failed fetch
        // is never confused with "fetched fine, nothing forecast" (§
        // WeatherManager.RainFetchDiagnostic's own doc comment).
        val dataStatus = when {
            !weatherEnabled -> ToolOutcomeStatus.DATA_UNAVAILABLE
            rainDiag == null -> ToolOutcomeStatus.DATA_UNAVAILABLE
            rainDiag.lastErrorType != null -> ToolOutcomeStatus.SOURCE_FAILURE
            else -> facts.dataStatus
        }

        fun publish(hazard: WeatherHazard?, candidateCreated: Boolean, occurrenceClaimed: Boolean) {
            _weatherAlertDiagnostic.value = WeatherAlertDiagnostic(
                evaluatedAtMs = System.currentTimeMillis(),
                targetLocalDate = targetDate,
                policyVersion = WeatherAlertPolicy.POLICY_VERSION,
                dataStatus = dataStatus.name,
                hazard = hazard?.name,
                candidateCreated = candidateCreated,
                occurrenceClaimed = occurrenceClaimed,
                governorOutcome = null,
                deliveryAttempted = false,
                delivered = false,
            )
        }

        // Never invent "tomorrow it will rain" (or "it will not") from bad
        // data — represent the failure honestly and let the next scheduled
        // evaluation (same evening window, up to hourly) retry.
        if (dataStatus != ToolOutcomeStatus.SUCCESS_DATA) {
            publish(hazard = null, candidateCreated = false, occurrenceClaimed = false)
            return null
        }

        val evaluation = WeatherAlertPolicy.evaluate(facts.category, facts.millimeters)
        val hazard = (evaluation as? WeatherAlertEvaluation.Decided)?.hazard
        if (hazard == null || hazard == WeatherHazard.NO_ALERT) {
            publish(hazard = hazard, candidateCreated = false, occurrenceClaimed = false)
            return null
        }

        val occurrenceKey = ProactiveOccurrenceKey.weatherAlert(targetDate)
        val claim = runCatching {
            occurrenceStore.claim(occurrenceKey, kind = "WEATHER_ALERT", logicalDate = targetDate, triggerSource = triggerSource, now = nowMs)
        }.getOrNull()
        val owned = claim is OccurrenceClaimOutcome.Claimed || claim is OccurrenceClaimOutcome.TakeoverAllowed
        if (!owned) {
            Log.i(TAG, "proactive_weather_alert_claim_denied source=$triggerSource claim=$claim")
            publish(hazard = hazard, candidateCreated = false, occurrenceClaimed = false)
            return null
        }

        publish(hazard = hazard, candidateCreated = true, occurrenceClaimed = true)
        return WeatherAlertEvalResult(ProactiveComposer.weatherAlert(hazard, targetDate), occurrenceKey)
    }

    /**
     * § JARVIS Implementation Master Plan — PASSAGGIO 14.2 — "safe debug/test
     * path... inject a NON-PRODUCTION deterministic tomorrow forecast and
     * verify the decision pipeline without altering production weather
     * data." Debug-only by convention (the caller —
     * [com.simone.jarvismobile.ui.diagnostics.DiagnosticsViewModel] — gates
     * this behind `BuildConfig.DEBUG`, the same convention already used for
     * the GPS simulator). [category]/[millimeters] are fixtures fed
     * straight into [WeatherAlertPolicy] — [WeatherManager]/[ContextEngine]'s
     * real cached forecast is never read or written here. Claims a
     * DELIBERATELY DISTINCT occurrence key (`WEATHER_ALERT_DEBUG:`, never
     * `WEATHER_ALERT:`) so a test run can never suppress — or be suppressed
     * by — the real evening evaluation running the same night.
     */
    suspend fun simulateWeatherAlert(
        category: WeatherCategory,
        millimeters: Double?,
        now: LocalDateTime = LocalDateTime.now(),
    ): String {
        val targetDate = WeatherAlertPolicy.targetDateFor(now.toLocalDate())
        val evaluation = WeatherAlertPolicy.evaluate(category, millimeters)
        val hazard = (evaluation as? WeatherAlertEvaluation.Decided)?.hazard
        if (hazard == null || hazard == WeatherHazard.NO_ALERT) {
            return "Nessun avviso da questo scenario (esito=$evaluation) — mai una consegna simulata per NO_ALERT/sconosciuto."
        }
        val occurrenceKey = "WEATHER_ALERT_DEBUG:$targetDate:${hazard.name}"
        val nowMs = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val claim = runCatching {
            occurrenceStore.claim(occurrenceKey, kind = "WEATHER_ALERT_DEBUG", logicalDate = targetDate, triggerSource = "DEBUG_SIMULATION", now = nowMs)
        }.getOrNull()
        val owned = claim is OccurrenceClaimOutcome.Claimed || claim is OccurrenceClaimOutcome.TakeoverAllowed
        if (!owned) {
            return "Questo esatto scenario è già stato simulato e consegnato oggi (claim=$claim) — nessuna nuova notifica."
        }
        val suggestion = ProactiveComposer.weatherAlert(hazard, targetDate)
        runCatching { occurrenceStore.markDeliveryAttempt(occurrenceKey) }
        notifier.show(suggestion)
        runCatching { occurrenceStore.markDelivered(occurrenceKey) }
        return "Notifica simulata inviata — hazard=$hazard targetDate=$targetDate messaggio=\"${suggestion.message}\""
    }

    private fun recordRun(
        now: LocalDateTime,
        isRealUnlock: Boolean,
        triggerSource: String,
        enabled: Boolean,
        automationEnabled: Boolean,
        candidateCount: Int,
        outcome: String,
    ) {
        _lastRun.value = RunDiagnostic(
            ranAtMs = System.currentTimeMillis(),
            isRealUnlock = isRealUnlock,
            triggerSource = triggerSource,
            enabled = enabled,
            automationServiceEnabled = automationEnabled,
            hour = now.hour,
            candidateCount = candidateCount,
            outcome = outcome,
        )
    }

    /**
     * The evening digest stays in its natural window, so a midday periodic run
     * stays quiet about it. The morning digest is different: it is offered any
     * time at or after [MORNING_EARLIEST_HOUR] — never earlier, so a late-night
     * unlock right after midnight is not mistaken for waking up.
     *
     * **Bug reale segnalato dall'utente, corretto**: "il briefing arriva o
     * prima dello sblocco o dopo" — con "Automazioni in background" attivo,
     * il tick periodico grezzo (fino a 1h, [evaluate]) e il vero sblocco
     * ([evaluateOnUnlock]) condividevano lo stesso dedup giornaliero del
     * governor senza che il periodico sapesse che esisteva un percorso
     * migliore: chiunque dei due scattasse per primo vinceva la corsa e
     * consumava il "turno" del giorno — se il tick periodico cadeva alle 8 e
     * lo sblocco reale avveniva solo alle 10, il briefing partiva alle 8
     * (prima del vero sblocco) e il vero sblocco non aveva più nulla da
     * offrire. Corretto: il tick periodico offre il digest mattutino solo
     * quando "Automazioni in background" è **spento** (in quel caso resta
     * l'unico percorso possibile, come documentato sopra su [evaluate]); il
     * vero sblocco lo offre sempre. Il dedup giornaliero del governor stesso
     * (`MORNING_DIGEST:<date>`) resta l'unico cancello "una volta al
     * giorno" fra i due percorsi.
     *
     * [includeMorning] (§ PASSAGGIO 14.1) — whether THIS caller actually won
     * the atomic occurrence claim for today's morning digest (computed once
     * in [run], before this method is even called) — never recomputed here,
     * so the hour/automation-service eligibility check and the claim
     * ownership check can never drift apart into two different answers.
     */
    private fun candidatesFor(
        now: LocalDateTime,
        snap: ProactiveSnapshot,
        today: LocalDate,
        includeMorning: Boolean,
    ): List<ProactiveSuggestion> {
        val out = ArrayList<ProactiveSuggestion>()
        val hour = now.hour
        if (includeMorning) out += ProactiveComposer.morningDigest(snap, today)
        if (hour in EVENING_FROM..EVENING_TO) {
            ProactiveComposer.batteryBeforeAlarm(snap, today)?.let { out += it }
            ProactiveComposer.eveningDigest(snap, today)?.let { out += it }
        }
        return out
    }

    private suspend fun readSettings(): ProactiveSettings {
        fun kinds(names: Set<String>): Set<ProactiveKind> =
            names.mapNotNull { runCatching { ProactiveKind.valueOf(it) }.getOrNull() }.toSet()
        return ProactiveSettings(
            enabled = settings.proactiveEnabled.first(),
            maxPerDay = settings.proactiveMaxPerDay.first(),
            quietStart = LocalTime.of(settings.proactiveQuietStart.first(), 0),
            quietEnd = LocalTime.of(settings.proactiveQuietEnd.first(), 0),
            disabledKinds = kinds(settings.proactiveDisabledKinds.first()),
            mutedKinds = kinds(settings.proactiveMutedKinds.first()),
        )
    }

    private suspend fun snapshot(today: LocalDate, now: LocalDateTime): ProactiveSnapshot {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level < 0 || scale <= 0) -1 else level * 100 / scale
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val entries = runCatching { agenda.reload() }.getOrDefault(agenda.entries.value)
        val appointments = entries
            .filter { !it.done && it.date == today && it.time != null }
            .sortedBy { it.time }
            .map { "${it.text} ${clock(it.time!!)}" }
        val tasks = entries
            .filter { !it.done && it.time == null && (it.date == today || it.starred) && !isBirthday(it.text) }
            .map { (if (it.starred) "⭐ " else "") + it.text }
        // No dedicated birthday feature exists (§ honesty ledger): this reads
        // agenda items the user already wrote for today whose text names a
        // birthday, so "il compleanno di Marco" on today's date surfaces on its
        // own line instead of blending into the task list.
        val birthdays = entries
            .filter { it.date == today && isBirthday(it.text) }
            .map { birthdayName(it.text) }

        // Forces a real refresh before reading, same reasoning already applied
        // to the rule engine's KIND_RULE firings (§ AlarmReceiver): the
        // periodic WeatherScheduler worker runs every 3h, but WorkManager can
        // push a periodic job back for hours across an overnight Doze —
        // reading only the cache at the very first unlock of the day meant
        // the morning briefing's weather emoji was routinely missing simply
        // because the last successful refresh predated the 6h staleness
        // window (§ segnalazione dell'utente: emoji del meteo assente dal
        // briefing mattutino). A no-op when weather is off (checked inside
        // refresh() itself), so this costs nothing for anyone not using it.
        runCatching { weather.refresh() }
        // Health Connect BPM/sonno (§ richiesta esplicita dell'utente:
        // "questi risultati devono aggiornarsi ogni mattina poco dopo il
        // briefing mattutino") — stesso punto e stesso motivo del refresh
        // meteo qui sopra: la prima cosa che succede vicino al vero primo
        // sblocco della giornata. No-op economico quando i permessi non
        // sono concessi (controllato dentro refresh() stesso).
        runCatching { health.refresh() }
        // Reuses ContextEngine's own staleness cutoff, so a refresher that has
        // stopped working reads as "unknown" here too, not as a frozen forecast.
        val rain = runCatching { contextEngine.evaluationContext(now = now) }.getOrNull()
        val weatherCategory = runCatching { contextEngine.todayWeather(now = now) }.getOrNull()

        return ProactiveSnapshot(
            batteryPercent = percent,
            charging = charging,
            nextAlarm = nextAlarmTime(),
            todayAppointments = appointments,
            todayTasks = tasks,
            birthdaysToday = birthdays,
            rainToday = rain?.rainToday,
            todayWeather = weatherCategory,
        )
    }

    /**
     * § FASE 2A.8 RELEASE GATE G — called only by [MorningRefreshWorker], AFTER
     * a morning digest was already really delivered today. Re-composes the
     * digest from freshly refreshed data and re-posts it under the SAME
     * notification id ([ProactiveNotifier.notificationId] is stable per
     * [com.simone.jarvismobile.core.proactive.ProactiveKind]) so it replaces
     * in place rather than stacking a second notification. Deliberately does
     * NOT go through [ProactiveGovernor.decide] again: that gate's per-day
     * dedup exists to prevent a SECOND independent decision to deliver
     * today's digest, which is correct for a new decision but wrong for
     * refreshing content already shown — and deliberately does NOT re-speak
     * it (a second spoken briefing minutes later would be intrusive, not
     * helpful).
     */
    suspend fun refreshMorningDigestNotification(now: LocalDateTime = LocalDateTime.now()) {
        val today = now.toLocalDate()
        val suggestion = ProactiveComposer.morningDigest(snapshot(today, now), today)
        notifier.show(suggestion)
    }

    private fun isBirthday(text: String): Boolean = text.contains("complean", ignoreCase = true)

    /** Best-effort name after "di"/"of", or the whole line if none is found. */
    private fun birthdayName(text: String): String {
        val afterDi = Regex("""complean\w*\s+di\s+(.+)""", RegexOption.IGNORE_CASE).find(text)
        return afterDi?.groupValues?.get(1)?.trim()?.trim('.', '!') ?: text.trim()
    }

    private fun nextAlarmTime(): LocalTime? = runCatching {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.nextAlarmClock?.triggerTime?.let {
            Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalTime()
        }
    }.getOrNull()

    private fun clock(t: LocalTime) = "%02d:%02d".format(t.hour, t.minute)

    private companion object {
        const val TAG = "JarvisProactive"
        // Real unlocks between midnight and this hour never count as "waking up"
        // (§ evaluateOnUnlock) — that is still the previous night, not morning.
        const val MORNING_EARLIEST_HOUR = 5
        const val EVENING_FROM = 19
        const val EVENING_TO = 21
    }
}
