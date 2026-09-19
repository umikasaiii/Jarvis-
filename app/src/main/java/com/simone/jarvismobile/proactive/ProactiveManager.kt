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
import com.simone.jarvismobile.core.agenda.AgendaEntry
import com.simone.jarvismobile.core.agenda.AgendaQueryOutcome
import com.simone.jarvismobile.core.proactive.MorningWindowPolicy
import com.simone.jarvismobile.core.proactive.OccurrenceClaimOutcome
import com.simone.jarvismobile.core.proactive.PeriodicFallbackPolicy
import com.simone.jarvismobile.core.proactive.ProactiveComposer
import com.simone.jarvismobile.core.proactive.ProactiveDaySection
import com.simone.jarvismobile.core.proactive.ProactiveDecision
import com.simone.jarvismobile.core.proactive.ProactiveDigestSnapshot
import com.simone.jarvismobile.core.proactive.ProactiveGovernor
import com.simone.jarvismobile.core.proactive.ProactiveKind
import com.simone.jarvismobile.core.proactive.ProactiveOccurrenceKey
import com.simone.jarvismobile.core.proactive.ProactiveOccurrenceState
import com.simone.jarvismobile.core.proactive.ProactiveSettings
import com.simone.jarvismobile.core.proactive.ProactiveSuggestion
import com.simone.jarvismobile.core.proactive.ProactiveTriggerSource
import com.simone.jarvismobile.core.proactive.ProactiveWeatherFacts
import com.simone.jarvismobile.core.tools.ToolOutcomeStatus
import com.simone.jarvismobile.core.weather.ForecastEligibilityReason
import com.simone.jarvismobile.core.weather.ForecastFacts
import com.simone.jarvismobile.core.weather.HourlyPrecipitationEvidence
import com.simone.jarvismobile.core.weather.WeatherAlertDecisionV2
import com.simone.jarvismobile.core.weather.WeatherAlertFreshnessPolicyV2
import com.simone.jarvismobile.core.weather.WeatherAlertPolicyV2
import com.simone.jarvismobile.core.weather.WeatherCategory
import com.simone.jarvismobile.core.weather.WeatherFailureReason
import com.simone.jarvismobile.core.weather.WeatherHazard
import com.simone.jarvismobile.core.weather.WeatherRequestOutcome
import com.simone.jarvismobile.core.weather.WmoPrecipitationKind
import com.simone.jarvismobile.data.SettingsRepository
import com.simone.jarvismobile.health.HealthConnectManager
import com.simone.jarvismobile.weather.WeatherManager
import com.simone.jarvismobile.weather.receipt.ForecastDecisionReceiptRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
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
    private val dispatcher: ProactiveDeliveryDispatcher,
    private val coordinator: SessionCoordinator,
    private val contextEngine: ContextEngine,
    private val weather: WeatherManager,
    private val health: HealthConnectManager,
    private val morningTriggerScheduler: MorningTriggerScheduler,
    private val receiptRepository: ForecastDecisionReceiptRepository,
) {
    /**
     * Called periodically by the worker as a coarse fallback (see
     * [evaluateOnUnlock]) — the only path that can ever deliver anything for
     * whoever hasn't enabled "Automazioni in background", since there is then
     * no real unlock event to react to.
     */
    suspend fun evaluate(now: LocalDateTime = LocalDateTime.now()) =
        run(now, ProactiveTriggerSource.PERIODIC_FALLBACK)

    /**
     * Called at the real first-unlock-of-the-day event (§ "primo sblocco utile
     * della giornata"). Distinct from [evaluate] only in *when* it runs — both
     * go through the same [candidatesFor]/[MorningWindowPolicy] window and the
     * same governor per-day dedup, so calling this promptly at the real unlock
     * (rather than waiting for the next coarse tick) is what makes the morning
     * digest feel immediate instead of arriving up to an hour late.
     */
    suspend fun evaluateOnUnlock(now: LocalDateTime = LocalDateTime.now(), trigger: ProactiveTriggerSource = ProactiveTriggerSource.FIRST_UNLOCK) =
        run(now, trigger)

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
     * `triggerSource` (§ FASE 2A.8 RELEASE GATE F — Multi-Signal Morning
     * Coordinator, § WORK PACKAGE B §4 — typed): which signal caused this
     * call — [ProactiveTriggerSource.NEXT_ALARM], [ProactiveTriggerSource.CONFIGURED_TIME],
     * [ProactiveTriggerSource.FIRST_UNLOCK], [ProactiveTriggerSource.MANUAL_DEBUG],
     * or [ProactiveTriggerSource.PERIODIC_FALLBACK] (`"HUAWEI_SLEEP"` is not
     * implemented, see [com.simone.jarvismobile.proactive.MorningTriggerScheduler]'s
     * own honesty note). Purely diagnostic — every source converges on this
     * SAME method and the SAME governor per-day dedup key
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
        /**
         * § WORK PACKAGE D §22/§27 — the
         * [com.simone.jarvismobile.weather.receipt.ForecastDecisionReceiptEntity.receiptId]
         * this evaluation committed, if the write succeeded — null when the
         * evaluation never reached a real decision (disabled/no location) or
         * the receipt write itself failed (§22: a failed write never blocks
         * the DIAGNOSTIC, only ever blocks a DISPATCH).
         */
        val receiptId: String? = null,
    )

    private val _weatherAlertDiagnostic = MutableStateFlow<WeatherAlertDiagnostic?>(null)
    val weatherAlertDiagnostic: StateFlow<WeatherAlertDiagnostic?> = _weatherAlertDiagnostic.asStateFlow()

    /**
     * § JARVIS Implementation Master Plan — MICRO-PATCH 14.2.2 §9. One
     * process-lifetime id (not persisted, not a device identifier — just
     * distinguishes receipts across a process restart in the same session
     * of debugging), included in every [MorningDeliveryReceipt] so a
     * developer reading Diagnostics can tell whether two receipts came from
     * the same running process or from before/after a restart.
     */
    private val sessionDiagnosticId: String = UUID.randomUUID().toString().take(8)

    /**
     * § MICRO-PATCH 14.2.2 §9 — DEVICE DIAGNOSTIC RECEIPT. One entry per
     * attempted Morning Briefing delivery/refresh action, from EVERY
     * trigger source and EVERY code path capable of touching today's
     * occurrence (the real multi-path audit this micro-patch's mission
     * required) — bounded to [MAX_RECEIPTS] so this can never grow
     * unbounded across a long-running process. Deliberately carries NO
     * briefing/agenda/health/weather body text, only enum-shaped/opaque
     * facts (§14: "metadata only... no briefing body").
     */
    data class MorningDeliveryReceipt(
        val attemptedAtMs: Long,
        val logicalDate: LocalDate,
        val occurrenceKey: String,
        val triggerSource: String,
        val schedulerSource: String,
        val scheduledForMs: Long?,
        val claimOutcome: String,
        val stateBefore: String?,
        val stateAfter: String?,
        val composerId: String,
        val rendererId: String,
        val notificationTag: String?,
        val notificationId: Int?,
        val deliveryAttempted: Boolean,
        val deliveryResult: String,
        val retryReason: String?,
        val existingOwnerTrigger: String?,
        val sessionDiagnosticId: String,
    )

    private val _morningDeliveryReceipts = MutableStateFlow<List<MorningDeliveryReceipt>>(emptyList())
    val morningDeliveryReceipts: StateFlow<List<MorningDeliveryReceipt>> = _morningDeliveryReceipts.asStateFlow()

    private fun recordMorningReceipt(
        now: LocalDateTime,
        occurrenceKey: String,
        triggerSource: String,
        schedulerSource: String,
        scheduledForMs: Long?,
        claimOutcome: String,
        stateBefore: ProactiveOccurrenceState?,
        stateAfter: ProactiveOccurrenceState?,
        deliveryAttempted: Boolean,
        deliveryResult: String,
        retryReason: String? = null,
        existingOwnerTrigger: String? = null,
    ) {
        val receipt = MorningDeliveryReceipt(
            attemptedAtMs = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            logicalDate = now.toLocalDate(),
            occurrenceKey = occurrenceKey,
            triggerSource = triggerSource,
            schedulerSource = schedulerSource,
            scheduledForMs = scheduledForMs,
            claimOutcome = claimOutcome,
            stateBefore = stateBefore?.name,
            stateAfter = stateAfter?.name,
            composerId = MORNING_DIGEST_COMPOSER_ID,
            rendererId = MORNING_DIGEST_RENDERER_ID,
            notificationTag = null,
            notificationId = ProactiveNotifier.notificationId(ProactiveKind.MORNING_DIGEST),
            deliveryAttempted = deliveryAttempted,
            deliveryResult = deliveryResult,
            retryReason = retryReason,
            existingOwnerTrigger = existingOwnerTrigger,
            sessionDiagnosticId = sessionDiagnosticId,
        )
        _morningDeliveryReceipts.value = (_morningDeliveryReceipts.value + receipt).takeLast(MAX_RECEIPTS)
    }

    private suspend fun run(now: LocalDateTime, trigger: ProactiveTriggerSource) {
        // § WORK PACKAGE B §4 — a typed trigger source, never a bare
        // isRealUnlock=true hardcoded for every caller: only FIRST_UNLOCK is
        // ever a real ACTION_USER_PRESENT observation.
        val triggerSource = trigger.name
        val isRealUnlock = trigger == ProactiveTriggerSource.FIRST_UNLOCK
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
        // § WORK PACKAGE B §12 — a desired-ON automation-service setting is
        // never proof the runtime observer is actually alive (§11): the old
        // rule permanently suppressed the periodic fallback whenever the
        // setting was on, even if the real observer had silently died.
        // Before the configured fallback time, the periodic tick still never
        // sends early (a still-possibly-working FIRST_UNLOCK/NEXT_ALARM
        // shouldn't be preempted); once it has passed, periodic
        // reconciliation may recover a still-legitimately-available
        // occurrence regardless of the desired service preference — the
        // durable occurrence claim above/below is what actually decides
        // "still available", never this policy alone.
        val offerMorning = when (trigger) {
            ProactiveTriggerSource.PERIODIC_FALLBACK -> {
                val schedule = settings.morningScheduleSettings.first()
                PeriodicFallbackPolicy.shouldOfferMorningOnPeriodicTick(
                    automationServiceDesiredOn = automationEnabled,
                    nowMinuteOfDay = now.hour * 60 + now.minute,
                    configuredFallbackMinuteOfDay = schedule.hour * 60 + schedule.minute,
                )
            }
            else -> true
        }
        // § WORK PACKAGE B §13 — the explicit supported morning delivery
        // window (05:00 inclusive, 12:00 exclusive), not just a floor: a
        // delayed trigger from well past noon must never say "Buongiorno".
        val morningEligible = MorningWindowPolicy.isWithinWindow(now.hour) && offerMorning
        val morningKey = if (morningEligible) ProactiveOccurrenceKey.morningDigest(today) else null
        val morningClaim = morningKey?.let { key ->
            runCatching {
                occurrenceStore.claim(key, kind = "MORNING_DIGEST", logicalDate = today, triggerSource = triggerSource, now = nowMs)
            }.getOrNull()
        }
        val morningOwnedThisRun = morningClaim is OccurrenceClaimOutcome.Claimed || morningClaim is OccurrenceClaimOutcome.TakeoverAllowed
        if (morningKey != null && !morningOwnedThisRun) {
            Log.i(TAG, "proactive_morning_claim_denied source=$triggerSource claim=$morningClaim")
            // § MICRO-PATCH 14.2.2 §9 — an extra read purely for the device
            // diagnostic receipt (never on the winning/common path): who
            // actually owns today's occurrence, so a denied trigger's
            // receipt says WHY (already claimed by which source) instead of
            // just "denied".
            val owner = runCatching { occurrenceStore.peek(morningKey) }.getOrNull()
            recordMorningReceipt(
                now = now, occurrenceKey = morningKey, triggerSource = triggerSource,
                schedulerSource = schedulerSourceFor(triggerSource), scheduledForMs = null,
                claimOutcome = (morningClaim as? OccurrenceClaimOutcome.AlreadyOwned)?.let { "ALREADY_${it.state.name}" } ?: "DENIED",
                stateBefore = owner?.state, stateAfter = owner?.state,
                deliveryAttempted = false, deliveryResult = "SKIPPED",
                existingOwnerTrigger = owner?.owningTriggerSource,
            )
        }

        // § WORK PACKAGE A §15 — evening joins the SAME durable occurrence
        // authority morning already has. `EVENING_DIGEST:<deliveryDate>`,
        // keyed by TODAY (delivery date), never the agenda target date.
        val eveningEligible = now.hour in EVENING_FROM..EVENING_TO
        val eveningKey = if (eveningEligible) ProactiveOccurrenceKey.eveningDigest(today) else null
        val eveningClaim = eveningKey?.let { key ->
            runCatching {
                occurrenceStore.claim(key, kind = "EVENING_DIGEST", logicalDate = today, triggerSource = triggerSource, now = nowMs)
            }.getOrNull()
        }
        val eveningOwnedThisRun = eveningClaim is OccurrenceClaimOutcome.Claimed || eveningClaim is OccurrenceClaimOutcome.TakeoverAllowed
        if (eveningKey != null && !eveningOwnedThisRun) {
            Log.i(TAG, "proactive_evening_claim_denied source=$triggerSource claim=$eveningClaim")
        }

        // § §14/§15 — kicked off first, never awaited: the factual snapshot
        // below reads whatever is already cached, so a slow/offline
        // provider never blocks dispatch.
        kickOffBackgroundFreshness()
        val digest = buildDigestSnapshot(today, now)
        val candidates = candidatesFor(now, digest, includeMorning = morningOwnedThisRun, includeEvening = eveningOwnedThisRun).toMutableList()

        // § JARVIS Implementation Master Plan PASSAGGIO 14.2 — evening
        // rain/storm alert, evaluated in the SAME evening window
        // eveningDigest/batteryBeforeAlarm already use below (no second
        // scheduler). § WORK PACKAGE C §14/§15 — [kickOffBackgroundFreshness]
        // above fires the real refresh but is never awaited, so this reads
        // whatever is already cached; [evaluateWeatherAlert] already grades
        // its own freshness/failure honestly (never invents a forecast) and
        // simply retries on the next scheduled evaluation if the data isn't
        // fresh yet.
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
                runCatching { occurrenceStore.markFailedRetryable(morningKey, "no_candidate_this_hour", nowMs) }
            }
            if (eveningKey != null && eveningOwnedThisRun) {
                runCatching { occurrenceStore.markFailedRetryable(eveningKey, "no_candidate_this_hour", nowMs) }
            }
            recordRun(now, isRealUnlock, triggerSource, config.enabled, automationEnabled, candidateCount = 0, outcome = "no_candidate_this_hour")
            return
        }
        val state = store.load().rolledTo(today)
        when (val decision = ProactiveGovernor.decide(candidates, config, state, now)) {
            is ProactiveDecision.Deliver -> {
                val deliveredKind = decision.suggestion.kind
                // § WORK PACKAGE A §11 — the durable occurrence key backing
                // THIS delivered kind, if any (BATTERY_BEFORE_ALARM has none
                // — it keeps its existing SharedPreferences-based dedup,
                // explicitly out of scope here).
                val occurrenceKeyForDelivered = when (deliveredKind) {
                    ProactiveKind.MORNING_DIGEST -> morningKey
                    ProactiveKind.EVENING_DIGEST -> eveningKey
                    ProactiveKind.WEATHER_ALERT -> weatherAlertKey
                    ProactiveKind.BATTERY_BEFORE_ALARM -> null
                }
                store.save(decision.newState)

                if (occurrenceKeyForDelivered != null) {
                    // § §11/§12/§13 — the ONE dispatch owner: fenced
                    // markDeliveryAttempt -> notifier call iff fencing held ->
                    // fenced mark* of the typed outcome. Never a direct
                    // production notifier call for an occurrence-backed kind.
                    val result = dispatcher.dispatch(
                        occurrenceKey = occurrenceKeyForDelivered,
                        expectedClaimedAtMs = nowMs,
                        suggestion = decision.suggestion,
                        tag = ProactiveNotifier.tagFor(deliveredKind),
                    )
                    when (result) {
                        is ProactiveDeliveryResult.Posted -> {
                            runCatching { coordinator.speakBackgroundResponse(decision.suggestion.message) }
                            Log.i(TAG, "proactive_deliver $deliveredKind source=$triggerSource")
                            recordRun(now, isRealUnlock, triggerSource, config.enabled, automationEnabled, candidates.size, "delivered:$deliveredKind")
                            if (deliveredKind == ProactiveKind.MORNING_DIGEST && morningKey != null) {
                                // § FASE 2A.8 RELEASE GATE G — only for a REAL
                                // morning-digest delivery, never evening/battery.
                                runCatching { morningTriggerScheduler.schedulePostBriefingRefreshes() }
                                recordMorningReceipt(
                                    now = now, occurrenceKey = morningKey, triggerSource = triggerSource,
                                    schedulerSource = schedulerSourceFor(triggerSource), scheduledForMs = null,
                                    claimOutcome = morningClaim.claimLabel(), stateBefore = ProactiveOccurrenceState.CLAIMED,
                                    stateAfter = ProactiveOccurrenceState.DELIVERED,
                                    deliveryAttempted = true, deliveryResult = "DELIVERED",
                                )
                            }
                            if (deliveredKind == ProactiveKind.WEATHER_ALERT) {
                                updateWeatherAlertOutcome("delivered", deliveryAttempted = true, delivered = true)
                            }
                        }
                        is ProactiveDeliveryResult.BlockedPermission -> {
                            Log.i(TAG, "proactive_blocked_permission $deliveredKind source=$triggerSource")
                            recordRun(now, isRealUnlock, triggerSource, config.enabled, automationEnabled, candidates.size, "blocked_permission:$deliveredKind")
                            if (deliveredKind == ProactiveKind.MORNING_DIGEST && morningKey != null) {
                                recordMorningReceipt(
                                    now = now, occurrenceKey = morningKey, triggerSource = triggerSource,
                                    schedulerSource = schedulerSourceFor(triggerSource), scheduledForMs = null,
                                    claimOutcome = morningClaim.claimLabel(), stateBefore = ProactiveOccurrenceState.CLAIMED,
                                    stateAfter = ProactiveOccurrenceState.BLOCKED_PERMISSION,
                                    deliveryAttempted = true, deliveryResult = "BLOCKED_PERMISSION",
                                )
                            }
                            if (deliveredKind == ProactiveKind.WEATHER_ALERT) {
                                updateWeatherAlertOutcome("blocked_permission", deliveryAttempted = true, delivered = false)
                            }
                        }
                        is ProactiveDeliveryResult.UnknownEffect -> {
                            Log.w(TAG, "proactive_unknown_effect $deliveredKind source=$triggerSource detail=${result.detail}")
                            recordRun(now, isRealUnlock, triggerSource, config.enabled, automationEnabled, candidates.size, "unknown_effect:$deliveredKind")
                            if (deliveredKind == ProactiveKind.MORNING_DIGEST && morningKey != null) {
                                recordMorningReceipt(
                                    now = now, occurrenceKey = morningKey, triggerSource = triggerSource,
                                    schedulerSource = schedulerSourceFor(triggerSource), scheduledForMs = null,
                                    claimOutcome = morningClaim.claimLabel(), stateBefore = ProactiveOccurrenceState.CLAIMED,
                                    stateAfter = ProactiveOccurrenceState.UNKNOWN_EFFECT,
                                    deliveryAttempted = true, deliveryResult = "UNKNOWN_EFFECT:${result.detail}",
                                )
                            }
                            if (deliveredKind == ProactiveKind.WEATHER_ALERT) {
                                updateWeatherAlertOutcome("unknown_effect:${result.detail}", deliveryAttempted = true, delivered = false)
                            }
                        }
                        is ProactiveDeliveryResult.FencingLost -> {
                            // § §13 — a canonical DB transition failure before
                            // dispatch: NO Android call was made. Someone else
                            // (a takeover) already owns this occurrence.
                            Log.w(TAG, "proactive_fencing_lost $deliveredKind source=$triggerSource")
                            recordRun(now, isRealUnlock, triggerSource, config.enabled, automationEnabled, candidates.size, "fencing_lost:$deliveredKind")
                        }
                    }
                } else {
                    // BATTERY_BEFORE_ALARM — no durable occurrence backing
                    // (existing SharedPreferences-based dedup, out of scope).
                    notifier.dispatch(decision.suggestion)
                    runCatching { coordinator.speakBackgroundResponse(decision.suggestion.message) }
                    Log.i(TAG, "proactive_deliver $deliveredKind source=$triggerSource")
                    recordRun(now, isRealUnlock, triggerSource, config.enabled, automationEnabled, candidates.size, "delivered:$deliveredKind")
                }

                // § §L — release every occurrence-backed claim NOT delivered
                // this run so it never permanently blocks a later legitimate
                // trigger (the governor picked a different candidate).
                if (deliveredKind != ProactiveKind.MORNING_DIGEST && morningKey != null && morningOwnedThisRun) {
                    runCatching { occurrenceStore.markFailedRetryable(morningKey, "governor_selected_other_candidate", nowMs) }
                    recordMorningReceipt(
                        now = now, occurrenceKey = morningKey, triggerSource = triggerSource,
                        schedulerSource = schedulerSourceFor(triggerSource), scheduledForMs = null,
                        claimOutcome = morningClaim.claimLabel(), stateBefore = ProactiveOccurrenceState.CLAIMED,
                        stateAfter = ProactiveOccurrenceState.FAILED_RETRYABLE,
                        deliveryAttempted = false, deliveryResult = "SKIPPED",
                        retryReason = "governor_selected_other_candidate",
                    )
                }
                if (deliveredKind != ProactiveKind.EVENING_DIGEST && eveningKey != null && eveningOwnedThisRun) {
                    runCatching { occurrenceStore.markFailedRetryable(eveningKey, "governor_selected_other_candidate", nowMs) }
                }
                if (deliveredKind != ProactiveKind.WEATHER_ALERT && weatherAlertKey != null) {
                    runCatching { occurrenceStore.markFailedRetryable(weatherAlertKey, "governor_selected_other_candidate", nowMs) }
                    updateWeatherAlertOutcome("suppressed:governor_selected_other_candidate", deliveryAttempted = false, delivered = false)
                }
            }
            is ProactiveDecision.Skip -> {
                Log.i(TAG, "proactive_skip ${decision.reason} source=$triggerSource")
                recordRun(now, isRealUnlock, triggerSource, config.enabled, automationEnabled, candidates.size, "skip:${decision.reason}")
                if (morningKey != null && morningOwnedThisRun) {
                    runCatching { occurrenceStore.markFailedRetryable(morningKey, "skip:${decision.reason}", nowMs) }
                    recordMorningReceipt(
                        now = now, occurrenceKey = morningKey, triggerSource = triggerSource,
                        schedulerSource = schedulerSourceFor(triggerSource), scheduledForMs = null,
                        claimOutcome = morningClaim.claimLabel(), stateBefore = ProactiveOccurrenceState.CLAIMED,
                        stateAfter = ProactiveOccurrenceState.FAILED_RETRYABLE,
                        deliveryAttempted = false, deliveryResult = "SKIPPED",
                        retryReason = "skip:${decision.reason}",
                    )
                }
                if (eveningKey != null && eveningOwnedThisRun) {
                    runCatching { occurrenceStore.markFailedRetryable(eveningKey, "skip:${decision.reason}", nowMs) }
                }
                if (weatherAlertKey != null) {
                    runCatching { occurrenceStore.markFailedRetryable(weatherAlertKey, "skip:${decision.reason}", nowMs) }
                    updateWeatherAlertOutcome("suppressed:skip:${decision.reason}", deliveryAttempted = false, delivered = false)
                }
            }
        }
    }

    /**
     * § WORK PACKAGE D §27 — besides updating the in-memory diagnostic, also
     * appends the matching append-only outcome event to the receipt this
     * evaluation already committed (§22), where one is actually defined —
     * never a fabricated `USER_SEEN`, and never an event for a purely
     * internal governor "suppressed" outcome (not part of the
     * CLAIMED/PREFLIGHT_BLOCKED/DISPATCH_INTENT/POSTED/UNKNOWN_EFFECT
     * vocabulary those events are scoped to).
     */
    private suspend fun updateWeatherAlertOutcome(governorOutcome: String, deliveryAttempted: Boolean, delivered: Boolean) {
        val diagnostic = _weatherAlertDiagnostic.value ?: return
        _weatherAlertDiagnostic.value = diagnostic.copy(
            governorOutcome = governorOutcome,
            deliveryAttempted = deliveryAttempted,
            delivered = delivered,
        )
        val receiptId = diagnostic.receiptId ?: return
        val event = when {
            governorOutcome == "delivered" -> "POSTED"
            governorOutcome == "blocked_permission" -> "PREFLIGHT_BLOCKED"
            governorOutcome.startsWith("unknown_effect") -> "UNKNOWN_EFFECT"
            else -> return
        }
        receiptRepository.appendOutcomeEvent(receiptId, event)
    }

    private data class WeatherAlertEvalResult(val suggestion: ProactiveSuggestion, val occurrenceKey: String)

    /**
     * § JARVIS Implementation Master Plan — PROACTIVITY RELIABILITY CLOSURE
     * WORK PACKAGE D. The production weather-alert pipeline, rewritten
     * around the STRUCTURED DATA → VALIDATION → PURE VERSIONED POLICY →
     * RECEIPT → EXISTING OCCURRENCE/DISPATCH contract (§1): a dated,
     * explicitly-matched, raw-WMO-code-preserving
     * [com.simone.jarvismobile.core.weather.ForecastFacts] fetch
     * ([WeatherManager.fetchDatedTomorrowForecast], §6-§9), a real
     * freshness/date/location validation
     * ([com.simone.jarvismobile.core.weather.WeatherAlertFreshnessPolicyV2],
     * §13), a pure versioned hazard decision
     * ([com.simone.jarvismobile.core.weather.WeatherAlertPolicyV2], §14-§19
     * — CANDIDATE THRESHOLDS PENDING QUALIFICATION, never claimed
     * meteorologically validated), and a committed receipt
     * ([ForecastDecisionReceiptRepository], §22) BEFORE the existing
     * occurrence-claim/dispatch machinery (§2/§31, entirely unchanged) is
     * ever reached. §32/§34: this is the ONE production decision path —
     * [com.simone.jarvismobile.core.weather.WeatherAlertPolicy] (v1) is
     * never called from here, kept only for migration/replay comparison.
     *
     * §22's "receipt write failure → NO ALERT DISPATCH" is enforced
     * directly: a candidate is only ever returned when [ForecastDecisionReceiptRepository.record]
     * returned a non-null id. Always publishes a [WeatherAlertDiagnostic]
     * before returning, even on every early-out branch — never invents
     * "tomorrow it will rain" (or "it will not") from bad/stale/mismatched
     * data.
     */
    private suspend fun evaluateWeatherAlert(
        now: LocalDateTime,
        today: LocalDate,
        triggerSource: String,
        nowMs: Long,
    ): WeatherAlertEvalResult? {
        val targetDate = today.plusDays(1)
        val weatherEnabled = settings.weatherEnabled.first()
        val outcome = weather.fetchDatedTomorrowForecast()
        val facts = (outcome as? WeatherRequestOutcome.Success)?.value
        val requestStatus = when {
            !weatherEnabled -> "DISABLED"
            outcome is WeatherRequestOutcome.Success -> "SUCCESS"
            outcome is WeatherRequestOutcome.Failure -> outcome.reason.name
            else -> "UNKNOWN"
        }
        val freshness = if (facts != null) {
            WeatherAlertFreshnessPolicyV2.evaluate(
                facts = facts,
                expectedTargetDate = targetDate,
                // § WORK PACKAGE D §11 — this is a fresh, synchronous fetch
                // (no cache layer for dated facts): the location [facts]
                // itself was just resolved FOR is, by definition, the
                // location resolved at evaluation time — the same
                // authoritative resolution, not a fabricated match.
                currentLocationRevision = facts.locationRevision,
                now = Instant.now(),
            )
        } else {
            ForecastEligibilityReason.MISSING_FACTS
        }
        val dataStatus = when {
            !weatherEnabled -> ToolOutcomeStatus.DATA_UNAVAILABLE
            outcome is WeatherRequestOutcome.Failure && outcome.reason == WeatherFailureReason.NO_LOCATION -> ToolOutcomeStatus.DATA_UNAVAILABLE
            outcome is WeatherRequestOutcome.Failure -> ToolOutcomeStatus.SOURCE_FAILURE
            freshness != ForecastEligibilityReason.ELIGIBLE -> ToolOutcomeStatus.STALE
            else -> ToolOutcomeStatus.SUCCESS_DATA
        }
        val locationMode = when {
            facts?.locationRevision?.startsWith("place:") == true -> "saved_place"
            facts?.locationRevision?.startsWith("coord:") == true -> "gps_fallback"
            else -> "unknown"
        }

        suspend fun publishAndRecord(
            decision: WeatherAlertDecisionV2?,
            candidateCreated: Boolean,
            occurrenceClaimed: Boolean,
            occurrenceKey: String?,
        ): String? {
            val receiptId = receiptRepository.record(
                requestedTargetDate = targetDate,
                facts = facts,
                freshness = freshness,
                requestStatus = requestStatus,
                factsSource = "live",
                decision = decision,
                candidateCreated = candidateCreated,
                locationMode = locationMode,
                locationMatch = facts?.let { freshness != ForecastEligibilityReason.LOCATION_MISMATCH },
                occurrenceKey = occurrenceKey,
                triggerSource = triggerSource,
            )
            if (receiptId != null && occurrenceKey != null) {
                receiptRepository.appendOutcomeEvent(receiptId, "CLAIMED")
            }
            _weatherAlertDiagnostic.value = WeatherAlertDiagnostic(
                evaluatedAtMs = System.currentTimeMillis(),
                targetLocalDate = targetDate,
                policyVersion = WeatherAlertPolicyV2.POLICY_VERSION,
                dataStatus = if (dataStatus == ToolOutcomeStatus.STALE) freshness.name else dataStatus.name,
                hazard = decision?.hazard?.name,
                candidateCreated = candidateCreated,
                occurrenceClaimed = occurrenceClaimed,
                governorOutcome = null,
                deliveryAttempted = false,
                delivered = false,
                receiptId = receiptId,
            )
            return receiptId
        }

        // Never invent "tomorrow it will rain" (or "it will not") from bad
        // data — represent the failure honestly and let the next scheduled
        // evaluation (same evening window, up to hourly) retry.
        if (dataStatus != ToolOutcomeStatus.SUCCESS_DATA) {
            publishAndRecord(decision = null, candidateCreated = false, occurrenceClaimed = false, occurrenceKey = null)
            return null
        }

        // § §17 — a daily storm code needs date-aligned hourly evidence
        // before it can qualify on its own; requested ONLY when the daily
        // code actually is a storm code (§6 — never an unrelated fetch).
        val hourlyEvidence = if (facts!!.rawWeatherCode in WmoPrecipitationKind.STORM_CODES) {
            (weather.fetchAlignedHourlyEvidenceForTomorrow() as? WeatherRequestOutcome.Success)?.value.orEmpty()
        } else {
            emptyList()
        }
        val decision = WeatherAlertPolicyV2.evaluate(facts, hourlyEvidence)
        if (decision.hazard == WeatherHazard.NO_ALERT) {
            publishAndRecord(decision = decision, candidateCreated = false, occurrenceClaimed = false, occurrenceKey = null)
            return null
        }

        val occurrenceKey = ProactiveOccurrenceKey.weatherAlert(targetDate)
        val claim = runCatching {
            occurrenceStore.claim(occurrenceKey, kind = "WEATHER_ALERT", logicalDate = targetDate, triggerSource = triggerSource, now = nowMs)
        }.getOrNull()
        val owned = claim is OccurrenceClaimOutcome.Claimed || claim is OccurrenceClaimOutcome.TakeoverAllowed
        if (!owned) {
            Log.i(TAG, "proactive_weather_alert_claim_denied source=$triggerSource claim=$claim")
            publishAndRecord(decision = decision, candidateCreated = false, occurrenceClaimed = false, occurrenceKey = null)
            return null
        }

        // § §22 — RECEIPT WRITE FAILURE → NO ALERT DISPATCH: a candidate is
        // only ever returned when the receipt actually committed.
        val receiptId = publishAndRecord(decision = decision, candidateCreated = true, occurrenceClaimed = true, occurrenceKey = occurrenceKey)
        if (receiptId == null) {
            Log.w(TAG, "proactive_weather_alert_receipt_write_failed source=$triggerSource")
            runCatching { occurrenceStore.markFailedRetryable(occurrenceKey, "receipt_write_failed", nowMs) }
            return null
        }
        return WeatherAlertEvalResult(ProactiveComposer.weatherAlert(decision.hazard, targetDate), occurrenceKey)
    }

    /**
     * § JARVIS Implementation Master Plan — PROACTIVITY RELIABILITY CLOSURE
     * WORK PACKAGE D.1 §4. Safe debug/test path — synthesizes a
     * NON-PRODUCTION [ForecastFacts] fixture and runs it through the SAME
     * validation ([WeatherAlertFreshnessPolicyV2]) and the SAME production
     * policy ([WeatherAlertPolicyV2]) evaluateWeatherAlert() uses — never
     * [com.simone.jarvismobile.core.weather.WeatherAlertPolicy] (v1),
     * closing the D.1 gap where the debug simulator exercised a different
     * policy than production. [WeatherManager]/[ContextEngine]'s real
     * cached forecast is never read or written here — [category]/
     * [millimeters] only choose which SYNTHETIC fixture ([syntheticFactsFor])
     * to build, preserving the exact three debug scenarios the Diagnostics
     * screen already offers (Sereno/Pioggia/Temporale) without any UI
     * change. Debug-only by convention (the caller —
     * [com.simone.jarvismobile.ui.diagnostics.DiagnosticsViewModel] — gates
     * this behind `BuildConfig.DEBUG`, the same convention already used for
     * the GPS simulator). Claims a DELIBERATELY DISTINCT occurrence key
     * (`WEATHER_ALERT_DEBUG:`, never `WEATHER_ALERT:`) so a test run can
     * never suppress — or be suppressed by — the real evening evaluation
     * running the same night, and writes a receipt with
     * `factsSource="synthetic_debug_simulation"` (§32 — "a shadow
     * evaluation may write a clearly-marked diagnostic receipt but never
     * become delivery authority") — never `occurrenceKey`-linked to a
     * production `WEATHER_ALERT:<date>` row.
     */
    suspend fun simulateWeatherAlert(
        category: WeatherCategory,
        millimeters: Double?,
        now: LocalDateTime = LocalDateTime.now(),
    ): String {
        val targetDate = now.toLocalDate().plusDays(1)
        val (facts, hourlyEvidence) = syntheticFactsFor(category, millimeters, targetDate, now)
        val freshness = WeatherAlertFreshnessPolicyV2.evaluate(
            facts = facts,
            expectedTargetDate = targetDate,
            currentLocationRevision = facts.locationRevision,
            now = Instant.now(),
        )
        if (freshness != ForecastEligibilityReason.ELIGIBLE) {
            return "Fixture sintetica non valida (freshness=$freshness) — nessuna simulazione possibile."
        }
        val decision = WeatherAlertPolicyV2.evaluate(facts, hourlyEvidence)
        runCatching {
            receiptRepository.record(
                requestedTargetDate = targetDate, facts = facts, freshness = freshness,
                requestStatus = "SUCCESS", factsSource = "synthetic_debug_simulation",
                decision = decision, candidateCreated = decision.hazard != WeatherHazard.NO_ALERT,
                locationMode = "synthetic_debug", locationMatch = true,
                occurrenceKey = null, triggerSource = "DEBUG_SIMULATION",
                notificationNamespace = ProactiveNotifier.TAG_DEBUG,
            )
        }
        if (decision.hazard == WeatherHazard.NO_ALERT) {
            return "Nessun avviso da questo scenario (esito=${decision.reason}) — mai una consegna simulata per NO_ALERT/sconosciuto."
        }
        val hazard = decision.hazard
        val occurrenceKey = "WEATHER_ALERT_DEBUG:$targetDate:${hazard.name}"
        val nowMs = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val claim = runCatching {
            occurrenceStore.claim(occurrenceKey, kind = "WEATHER_ALERT_DEBUG", logicalDate = targetDate, triggerSource = "DEBUG_SIMULATION", now = nowMs)
        }.getOrNull()
        val owned = claim is OccurrenceClaimOutcome.Claimed || claim is OccurrenceClaimOutcome.TakeoverAllowed
        if (!owned) {
            return "Questo esatto scenario è già stato simulato e consegnato oggi (claim=$claim) — nessuna nuova notifica."
        }
        val baseSuggestion = ProactiveComposer.weatherAlert(hazard, targetDate)
        // § WORK PACKAGE A §18 — DEBUG ISOLATION: a distinct tag/id range and
        // a visible "[SIMULAZIONE]" prefix, so this can never be confused
        // with a real hazard warning nor debit production occurrence/budget.
        val suggestion = baseSuggestion.copy(message = "[SIMULAZIONE] ${baseSuggestion.message}")
        val fenced = occurrenceStore.markDeliveryAttempt(occurrenceKey, nowMs)
        if (!fenced) return "Presa in carico persa fra la richiesta e l'invio — nessuna notifica."
        val outcome = notifier.dispatch(suggestion, tag = ProactiveNotifier.TAG_DEBUG)
        when (outcome) {
            is ProactiveDispatchOutcome.Posted -> occurrenceStore.markDelivered(occurrenceKey, nowMs)
            is ProactiveDispatchOutcome.BlockedPermission -> occurrenceStore.markBlockedPermission(occurrenceKey, "notification_blocked", nowMs)
            is ProactiveDispatchOutcome.ApiThrew -> occurrenceStore.markUnknownEffect(occurrenceKey, outcome.exceptionClass, nowMs)
        }
        return "Notifica simulata inviata (esito=$outcome) — hazard=$hazard targetDate=$targetDate messaggio=\"${suggestion.message}\""
    }

    /**
     * § WORK PACKAGE D.1 §4. Builds a deterministic, clearly-synthetic
     * [ForecastFacts] (+ optional aligned hourly evidence for the storm
     * scenario) matching the three Diagnostics debug buttons — mirrors the
     * pre-D.1 v1 semantics (CLEAR -> no alert, RAIN+3mm -> a qualifying
     * candidate, THUNDERSTORM -> a qualifying candidate) so the buttons keep
     * behaving the same while now genuinely exercising v2. [locationRevision]
     * is a fixed, obviously-synthetic tag — never a real
     * [com.simone.jarvismobile.core.weather.WeatherLocationKey].
     */
    private fun syntheticFactsFor(
        category: WeatherCategory,
        millimeters: Double?,
        targetDate: LocalDate,
        now: LocalDateTime,
    ): Pair<ForecastFacts, List<HourlyPrecipitationEvidence>> {
        val fetchedAt = now.atZone(ZoneId.systemDefault()).toInstant()
        val rawCode = when (category) {
            WeatherCategory.CLEAR -> 0
            WeatherCategory.PARTLY_CLOUDY -> 2
            WeatherCategory.CLOUDY -> 3
            WeatherCategory.RAIN -> 61
            WeatherCategory.THUNDERSTORM -> 95
        }
        val probability = when (category) {
            WeatherCategory.RAIN, WeatherCategory.THUNDERSTORM -> 85.0
            else -> 5.0
        }
        val liquid = millimeters ?: when (category) {
            WeatherCategory.RAIN -> 3.0
            WeatherCategory.THUNDERSTORM -> null
            else -> 0.0
        }
        val facts = ForecastFacts(
            targetDate = targetDate,
            providerTimezone = "Europe/Rome",
            locationRevision = "synthetic:debug",
            fetchedAt = fetchedAt,
            rawWeatherCode = rawCode,
            category = category,
            precipitationSumMm = liquid,
            rainSumMm = liquid,
            showersSumMm = null,
            snowfallSumCm = null,
            precipitationProbabilityMaxPercent = probability,
            precipitationHours = if (category == WeatherCategory.RAIN) 3.0 else 0.0,
        )
        val hourlyEvidence = if (category == WeatherCategory.THUNDERSTORM) {
            listOf(
                HourlyPrecipitationEvidence(
                    date = targetDate, hour = 15, rawWeatherCode = 95,
                    precipitationProbabilityPercent = 85.0, rainMm = 1.0, showersMm = null, precipitationMm = 1.0,
                ),
            )
        } else {
            emptyList()
        }
        return facts to hourlyEvidence
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
     * stays quiet about it. The morning digest is different: it is offered
     * only inside [MorningWindowPolicy]'s explicit supported delivery window
     * (05:00 inclusive, 12:00 exclusive — § WORK PACKAGE B §13) — never
     * earlier, so a late-night unlock right after midnight is not mistaken
     * for waking up, and never once it is no longer morning.
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
     * offrire. Corretto (§ WORK PACKAGE B §12, [PeriodicFallbackPolicy]): il
     * tick periodico non offre mai il digest mattutino PRIMA dell'orario
     * configurato, indipendentemente da "Automazioni in background" —
     * DOPO quell'orario, invece, lo offre SEMPRE, anche a servizio
     * desiderato-attivo, perché un'impostazione ON non è mai prova che
     * l'observer runtime sia davvero vivo (§11). Il vero sblocco lo offre
     * sempre. Il dedup giornaliero del governor stesso
     * (`MORNING_DIGEST:<date>`) resta l'unico cancello "una volta al
     * giorno" fra i percorsi.
     *
     * [includeMorning]/[includeEvening] (§ PASSAGGIO 14.1 / WORK PACKAGE A
     * §15) — whether THIS caller actually won the atomic occurrence claim
     * for today's morning/evening digest (computed once in [run], before
     * this method is even called) — never recomputed here, so the hour/
     * automation-service eligibility check and the claim ownership check
     * can never drift apart into two different answers. [includeEvening]
     * gates ONLY `ProactiveComposer.eveningDigest` — `batteryBeforeAlarm`
     * keeps its existing SharedPreferences-based dedup, deliberately out of
     * scope for this occurrence-authority work.
     */
    private fun candidatesFor(
        now: LocalDateTime,
        digest: ProactiveDigestSnapshot,
        includeMorning: Boolean,
        includeEvening: Boolean,
    ): List<ProactiveSuggestion> {
        val out = ArrayList<ProactiveSuggestion>()
        val hour = now.hour
        if (includeMorning) out += ProactiveComposer.morningDigest(digest)
        if (hour in EVENING_FROM..EVENING_TO) {
            ProactiveComposer.batteryBeforeAlarm(digest)?.let { out += it }
            if (includeEvening) out += ProactiveComposer.eveningDigest(digest)
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

    /**
     * § JARVIS Implementation Master Plan — PROACTIVITY RELIABILITY CLOSURE
     * WORK PACKAGE C §5/§6/§19 — assembles the one typed [ProactiveDigestSnapshot]
     * every composer function consumes. This is the ONLY place that talks to
     * [AgendaRepository]/[ContextEngine] for digest purposes — it never
     * reimplements date filtering itself (that stays [AgendaRepository]'s
     * job via [AgendaRepository.queryResult]/`Agenda.filter`) and never
     * guesses a status: [AgendaQueryOutcome] and [ToolOutcomeStatus] are
     * carried through verbatim (§6's "EMPTY != FAILURE" invariant).
     *
     * §14/§15 — deliberately does NOT await a weather/Health refresh before
     * building the snapshot: a bounded, already-cached read happens here,
     * and [kickOffBackgroundFreshness] fires the real refresh independently,
     * never blocking this notification-critical path. A cold/stale cache at
     * dispatch time simply renders without the weather emoji (§7/§12) rather
     * than blocking indefinitely or (forbidden by Work Package A's one-shot
     * contract) posting now and rewriting later.
     */
    private suspend fun buildDigestSnapshot(today: LocalDate, now: LocalDateTime): ProactiveDigestSnapshot {
        val tomorrow = today.plusDays(1)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level < 0 || scale <= 0) -1 else level * 100 / scale
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val outcome = runCatching { agenda.queryResult(today, day = today, toDay = tomorrow) }
            .getOrElse { AgendaQueryOutcome.Failure(it.javaClass.simpleName ?: "unknown") }

        fun daySection(date: LocalDate, dayEntries: List<AgendaEntry>): ProactiveDaySection {
            val appointments = dayEntries
                .filter { it.time != null && !isBirthday(it.text) }
                .sortedBy { it.time }
                .map { "${it.text} ${clock(it.time!!)}" }
            val datedTasks = dayEntries
                .filter { it.time == null && !isBirthday(it.text) }
                .map { (if (it.starred) "⭐ " else "") + it.text }
            // No dedicated birthday feature exists (§ honesty ledger): this
            // reads agenda items the user already wrote whose text names a
            // birthday, so "il compleanno di Marco" surfaces on its own line
            // instead of blending into the task list — same narrow, existing
            // mechanism applied to [date], never a new heuristic (§11).
            val birthdays = dayEntries.filter { isBirthday(it.text) }.map { birthdayName(it.text) }
            val agendaStatus = if (dayEntries.isEmpty()) ToolOutcomeStatus.SUCCESS_EMPTY else ToolOutcomeStatus.SUCCESS_DATA
            return ProactiveDaySection(date, agendaStatus, appointments, datedTasks, birthdays)
        }

        val (todaySection, tomorrowSection, openPriorities) = when (outcome) {
            is AgendaQueryOutcome.Failure -> Triple(
                ProactiveDaySection(today, ToolOutcomeStatus.SOURCE_FAILURE),
                ProactiveDaySection(tomorrow, ToolOutcomeStatus.SOURCE_FAILURE),
                emptyList<String>(),
            )
            is AgendaQueryOutcome.Success -> {
                val todaySec = daySection(today, outcome.entries.filter { it.date == today })
                val tomorrowSec = daySection(tomorrow, outcome.entries.filter { it.date == tomorrow })
                // § §10 — starred, UNDATED tasks only: a star is priority, not
                // a date, so a task genuinely dated today/tomorrow is already
                // captured above and never duplicated here. `agenda.entries`
                // already holds the full unfiltered parse from the same
                // `queryResult` call above (no second disk read).
                val priorities = agenda.entries.value
                    .filter { !it.done && it.starred && it.date == null && !isBirthday(it.text) }
                    .map { it.text }
                Triple(todaySec, tomorrowSec, priorities)
            }
        }

        val todayFacts = runCatching { contextEngine.todayForecastFacts(now) }.getOrNull()
        val tomorrowFacts = runCatching { contextEngine.tomorrowForecastFacts(now) }.getOrNull()

        return ProactiveDigestSnapshot(
            deliveryDate = today,
            today = todaySection,
            tomorrow = tomorrowSection,
            // § §9 — today's still-open dated tasks, carried into the Evening
            // Digest under their OWN heading — never merged into `tomorrow`,
            // never automatically re-dated.
            todayCarryoverForEvening = todaySection.datedTasks,
            openPriorities = openPriorities,
            todayWeather = todayFacts?.let { ProactiveWeatherFacts(today, it.category, it.dataStatus, it.rain) },
            tomorrowWeather = tomorrowFacts?.let { ProactiveWeatherFacts(tomorrow, it.category, it.dataStatus) },
            batteryPercent = percent,
            charging = charging,
            nextAlarm = nextAlarmTime(),
        )
    }

    /**
     * § §14/§15 — fires the real weather/Health refresh independently of the
     * notification-critical path above: `trigger -> bounded local factual
     * snapshot -> dispatch`, and separately `-> Health/cache refresh`, as
     * the spec explicitly allows. Never awaited by [buildDigestSnapshot]. No
     * second scheduler: this is the same [WeatherManager.refresh]/
     * [HealthConnectManager.refresh] call [buildDigestSnapshot] used to make
     * synchronously, only no longer blocking dispatch — future runs (and the
     * dashboard) see the refreshed cache; an already-DELIVERED occurrence's
     * rendered snapshot is never touched (Work Package A's one-shot
     * contract, §16, preserved: nothing here reaches [notifier]/[dispatcher]).
     */
    private fun kickOffBackgroundFreshness() {
        CoroutineScope(Dispatchers.Default).launch { runCatching { weather.refresh() } }
        CoroutineScope(Dispatchers.Default).launch { runCatching { health.refresh() } }
    }

    // § JARVIS Implementation Master Plan — PROACTIVITY RELIABILITY CLOSURE
    // WORK PACKAGE A (§10, P0) — `refreshMorningDigestNotification()` is
    // REMOVED. It was the exact P0-1 bug: the one code path in the whole
    // app that could produce Morning-Briefing-shaped notification content
    // with occurrence claim = NO (it only checked, never held, ownership),
    // and even after MICRO-PATCH 14.2.2's fix, the audit found it remained
    // the wrong shape of fix — see `docs/JARVIS_PROACTIVITY_RELIABILITY_CLOSURE_AUDIT.md`
    // §2 for the superseded conclusion this corrects. [MorningRefreshWorker]
    // is now DATA ONLY (§10): it refreshes weather/agenda/Health caches and
    // never composes, notifies, or touches dispatch ownership.

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

    /** § MICRO-PATCH 14.2.2 §9 — bounded, human-readable label for a [MorningDeliveryReceipt]. */
    private fun OccurrenceClaimOutcome?.claimLabel(): String = when (this) {
        is OccurrenceClaimOutcome.Claimed -> "WON"
        is OccurrenceClaimOutcome.TakeoverAllowed -> "WON_TAKEOVER"
        is OccurrenceClaimOutcome.AlreadyOwned -> "ALREADY_${state.name}"
        null -> "UNKNOWN"
    }

    /**
     * § MICRO-PATCH 14.2.2 §9/§2 — which real Android component actually
     * called into [run] for a given [triggerSource], for the "scheduler/
     * alarm/work identity" column the Delivery Path Matrix requires. Purely
     * a diagnostic label; [triggerSource] itself (not this) is what the
     * occurrence claim/governor logic actually uses.
     */
    private fun schedulerSourceFor(triggerSource: String): String = when (triggerSource) {
        "NEXT_ALARM", "CONFIGURED_TIME" -> "ProactiveScheduler+AlarmReceiver"
        "FIRST_UNLOCK" -> "AutomationEventService"
        "PERIODIC_FALLBACK" -> "ProactiveWorker"
        "MANUAL_DEBUG" -> "DiagnosticsViewModel"
        else -> triggerSource
    }

    private companion object {
        const val TAG = "JarvisProactive"
        const val EVENING_FROM = 19
        const val EVENING_TO = 21

        /** § MICRO-PATCH 14.2.2 §3 — proof there is exactly ONE composer/renderer, not several diverging ones. */
        const val MORNING_DIGEST_COMPOSER_ID = "ProactiveComposer.morningDigest.v1"
        const val MORNING_DIGEST_RENDERER_ID = "MorningDigestV2"
        const val MAX_RECEIPTS = 20
    }
}
