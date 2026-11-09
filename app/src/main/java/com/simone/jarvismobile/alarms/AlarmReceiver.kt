package com.simone.jarvismobile.alarms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.simone.jarvismobile.agenda.AgendaRepository
import com.simone.jarvismobile.automation.AutomationRepository
import com.simone.jarvismobile.automation.AutomationRunner
import com.simone.jarvismobile.automation.rule.AutomationExecutor
import com.simone.jarvismobile.automation.rule.RuleScheduler
import com.simone.jarvismobile.context.ContextEngine
import com.simone.jarvismobile.core.automation.rule.TriggerEvent
import com.simone.jarvismobile.core.proactive.ProactiveTriggerSource
import com.simone.jarvismobile.core.proactive.StaleIntentValidator
import com.simone.jarvismobile.core.proactive.TriggerEvidenceSource
import com.simone.jarvismobile.core.proactive.TriggerEvidenceStage
import com.simone.jarvismobile.proactive.ProactiveScheduler
import com.simone.jarvismobile.proactive.TriggerEvidenceStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.simone.jarvismobile.core.reliability.AutomationFireGuard
import com.simone.jarvismobile.core.reliability.BoundedWork
import com.simone.jarvismobile.core.reliability.WorkOutcome
import com.simone.jarvismobile.reminders.ReminderDeliveryLedger
import com.simone.jarvismobile.reminders.ReminderNotifier

/**
 * Where an exact alarm lands.
 *
 * A broadcast receiver is what an alarm can wake directly, so the work happens
 * here rather than by handing off to a deferrable job — handing off would put
 * back exactly the delay this change exists to remove.
 *
 * The notification is built from the alarm's own extras rather than by reading
 * the agenda again: at 03:00 on a sleeping phone the cheapest correct thing is
 * to post what was already decided when the alarm was set. Automations do read
 * their rule back, because a rule may have been edited in Obsidian since.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ExactAlarms.ACTION_FIRE) return
        val kind = intent.getStringExtra(ExactAlarms.EXTRA_KIND) ?: return
        val id = intent.getStringExtra(ExactAlarms.EXTRA_ID) ?: return

        when (kind) {
            ExactAlarms.KIND_REMINDER -> {
                // BETA 7: the outcome is recorded (permission denied / notifications off / channel muted are no longer
                // silent returns) and a blocked reminder is NOT marked delivered, so it can still be offered late.
                val outcome = ReminderNotifier.post(
                    context = context,
                    id = id,
                    entryId = intent.getStringExtra(ExactAlarms.EXTRA_ENTRY_ID).orEmpty(),
                    text = intent.getStringExtra(ExactAlarms.EXTRA_TITLE).orEmpty(),
                    subText = intent.getStringExtra(ExactAlarms.EXTRA_SUBTEXT).orEmpty(),
                )
                runCatching { ReminderDeliveryLedger(context).record(id, outcome) }
                Log.i(TAG, "reminder_fired outcome=${outcome.name}")
            }
            ExactAlarms.KIND_AUTOMATION -> {
                val deps = EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    AlarmEntryPoint::class.java,
                )
                val pending = goAsync()
                CoroutineScope(Dispatchers.Default).launch {
                    try {
                        val repository = deps.automations()
                        repository.reload()
                        val rule = repository.find(id) ?: return@launch
                        // BETA 7: a duplicated delivery of the same alarm must not run the rule twice.
                        if (!AutomationFireGuard.shouldRun(rule.enabled, rule.lastFired, java.time.LocalDateTime.now())) {
                            Log.i(TAG, "alarm_automation_duplicate_or_disabled_skipped")
                            repository.reload()
                            return@launch
                        }
                        val ran = withTimeoutOrNull(ALARM_WORK_BUDGET_MS) { deps.runner().run(rule) }
                        if (ran == true) repository.markFired(id)
                        // reload() re-arms the schedule, which is what makes a
                        // daily rule recur: only the next occurrence is ever booked.
                        repository.reload()
                    } catch (e: Throwable) {
                        Log.w(TAG, "alarm_automation_failed ${e.javaClass.simpleName}")
                        // The successor must be booked even if the action failed.
                        runCatching { deps.automations().reload() }
                    } finally {
                        pending.finish()
                    }
                }
            }
            ExactAlarms.KIND_RULE -> {
                val deps = EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    AlarmEntryPoint::class.java,
                )
                val triggerType = intent.getStringExtra(ExactAlarms.EXTRA_TRIGGER_TYPE) ?: return
                val occurrence = intent.getStringExtra(ExactAlarms.EXTRA_OCCURRENCE).orEmpty()
                val pending = goAsync()
                CoroutineScope(Dispatchers.Default).launch {
                    try {
                        val now = java.time.LocalDateTime.now()
                        // Bug reale segnalato dall'utente: un avviso "domani piove"
                        // sbagliato — la causa reale è che il meteo veniva letto
                        // solo dalla cache di ContextEngine, aggiornata da un
                        // worker indipendente ogni 3 ore, mai da un refresh legato
                        // allo scatto della regola stessa. Un modello meteo che si
                        // aggiorna nel frattempo lascia una regola "se domani
                        // piove" a valutare un dato vecchio fino a 3 ore. refresh()
                        // è già un no-op economico quando il meteo è spento
                        // (controllato al suo interno), quindi chiamarlo qui prima
                        // di ogni scatto non costa nulla quando l'utente non lo usa.
                        // BETA 7: bounded — offline, a stuck refresh must not eat the receiver's whole budget.
                        withTimeoutOrNull(WEATHER_BUDGET_MS) { runCatching { deps.weather().refresh() } }
                        // The occurrence is the dedup key, so a duplicated delivery
                        // of the same firing is collapsed by the gate.
                        val event = TriggerEvent(type = triggerType, at = now, dedupKey = occurrence)
                        // BETA 7: bounded, and the successor is re-armed even if the rule action hangs or fails.
                        val outcome = BoundedWork.runThenAlways(
                            timeoutMs = ALARM_WORK_BUDGET_MS,
                            work = {
                                val evalContext = deps.contextEngine().evaluationContext(now = now)
                                deps.ruleExecutor().onTrigger(event, evalContext)
                            },
                            afterwards = { deps.ruleScheduler().sync(now) },
                        )
                        if (outcome != WorkOutcome.COMPLETED) Log.w(TAG, "alarm_rule_outcome=${outcome.name}")
                    } catch (e: Throwable) {
                        Log.w(TAG, "alarm_rule_failed ${e.javaClass.simpleName}")
                    } finally {
                        pending.finish()
                    }
                }
            }
            ExactAlarms.KIND_MORNING_BRIEFING -> {
                val deps = EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    AlarmEntryPoint::class.java,
                )
                // § WORK PACKAGE B §4 — a typed trigger, never a bare string
                // threaded blindly through to isRealUnlock=true.
                val trigger = runCatching {
                    ProactiveTriggerSource.valueOf(intent.getStringExtra(ExactAlarms.EXTRA_TRIGGER_SOURCE) ?: "")
                }.getOrDefault(ProactiveTriggerSource.CONFIGURED_TIME)
                val intentPlanRevision = intent.getStringExtra(ExactAlarms.EXTRA_PLAN_REVISION)?.toLongOrNull()
                val intentLogicalDate = intent.getStringExtra(ExactAlarms.EXTRA_LOGICAL_DATE)
                val pending = goAsync()
                CoroutineScope(Dispatchers.Default).launch {
                    try {
                        val evidence = deps.triggerEvidence()
                        val evidenceSource = if (trigger == ProactiveTriggerSource.NEXT_ALARM) {
                            TriggerEvidenceSource.NEXT_ALARM
                        } else {
                            TriggerEvidenceSource.CONFIGURED_TIME
                        }
                        val firedStage = if (evidenceSource == TriggerEvidenceSource.NEXT_ALARM) {
                            TriggerEvidenceStage.NEXT_ALARM_RECEIVER_FIRED
                        } else {
                            TriggerEvidenceStage.CONFIGURED_TIME_RECEIVER_FIRED
                        }
                        evidence.record(evidenceSource, firedStage, detail = "trigger=${trigger.name} revision=$intentPlanRevision date=$intentLogicalDate")

                        val scheduler = deps.proactiveScheduler()
                        // § WORK PACKAGE B §15 — RECEIVER VALIDATION: a fired
                        // intent must carry the SAME plan revision/logical
                        // date the CURRENT plan holds. A mismatch is a
                        // NO-OP + evidence — never "recompute against
                        // current settings and deliver anyway".
                        val currentPlan = scheduler.currentPlan(trigger)
                        val isValid = intentPlanRevision != null && intentLogicalDate != null &&
                            StaleIntentValidator.isValid(
                                intentPlanRevision = intentPlanRevision,
                                intentLogicalDate = intentLogicalDate,
                                currentPlanRevision = currentPlan?.planRevision,
                                currentLogicalDate = currentPlan?.logicalDate,
                            )
                        if (!isValid) {
                            val staleStage = if (evidenceSource == TriggerEvidenceSource.NEXT_ALARM) {
                                TriggerEvidenceStage.NEXT_ALARM_STALE_INTENT_REJECTED
                            } else {
                                TriggerEvidenceStage.CONFIGURED_TIME_STALE_INTENT_REJECTED
                            }
                            evidence.record(evidenceSource, staleStage, detail = "intentRev=$intentPlanRevision currentRev=${currentPlan?.planRevision}")
                            Log.i(TAG, "alarm_morning_briefing_stale_intent_rejected source=${trigger.name}")
                        } else {
                            evidence.record(evidenceSource, TriggerEvidenceStage.PROACTIVE_CALL_ATTEMPTED)
                            // § MICRO-PATCH 14.2.3 §6 — a failure INSIDE
                            // evaluateOnUnlock must never skip the re-arm
                            // below: without this runCatching, an exception
                            // here would leave the alarm that just fired
                            // WITHOUT a successor scheduled for the next
                            // occurrence — a real systemic-reliability gap
                            // found during that audit, not just a
                            // diagnostics gap.
                            // BETA 7: bounded by the receiver's time budget. A hung evaluation used to block the
                            // re-arm below and keep goAsync() open until the process was killed — losing every
                            // later briefing. Now a hang is its own evidence stage and the successor is still booked.
                            var failure: Throwable? = null
                            val outcome = BoundedWork.runThenAlways(
                                timeoutMs = MORNING_WORK_BUDGET_MS,
                                work = {
                                    try {
                                        deps.proactiveManager().evaluateOnUnlock(trigger = trigger)
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (e: Throwable) {
                                        failure = e
                                        throw e
                                    }
                                },
                                afterwards = { },
                            )
                            when (outcome) {
                                WorkOutcome.COMPLETED -> evidence.record(evidenceSource, TriggerEvidenceStage.PROACTIVE_CALL_SUCCEEDED)
                                WorkOutcome.TIMED_OUT -> {
                                    evidence.record(evidenceSource, TriggerEvidenceStage.PROACTIVE_CALL_TIMED_OUT, detail = "budgetMs=$MORNING_WORK_BUDGET_MS")
                                    Log.w(TAG, "alarm_morning_briefing_evaluate_timed_out")
                                }
                                WorkOutcome.FAILED -> {
                                    evidence.record(evidenceSource, TriggerEvidenceStage.PROACTIVE_CALL_FAILED, detail = "error=${failure?.javaClass?.simpleName}")
                                    Log.w(TAG, "alarm_morning_briefing_evaluate_failed ${failure?.javaClass?.simpleName}")
                                }
                            }
                        }
                        // Both signals are one-shot exact alarms — re-arm the
                        // NEXT occurrence for whichever one just fired,
                        // exactly like KIND_RULE's own re-arm above. Always
                        // reached, whether the intent was valid or stale, and
                        // even if evaluateOnUnlock above failed.
                        when (trigger) {
                            ProactiveTriggerSource.NEXT_ALARM -> scheduler.reconcileNextAlarm()
                            else -> scheduler.reconcileConfiguredTime()
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "alarm_morning_briefing_failed ${e.javaClass.simpleName}")
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AlarmEntryPoint {
        fun automations(): AutomationRepository
        fun agenda(): AgendaRepository
        fun runner(): AutomationRunner
        fun contextEngine(): ContextEngine
        fun ruleExecutor(): AutomationExecutor
        fun ruleScheduler(): RuleScheduler
        fun weather(): com.simone.jarvismobile.weather.WeatherManager
        fun proactiveManager(): com.simone.jarvismobile.proactive.ProactiveManager
        fun proactiveScheduler(): ProactiveScheduler
        fun triggerEvidence(): TriggerEvidenceStore
    }

    private companion object {
        const val TAG = "JarvisAlarms"
        /** goAsync() gives a few seconds; stay well inside it so the PendingResult is always finished. */
        const val ALARM_WORK_BUDGET_MS = 8_000L
        const val WEATHER_BUDGET_MS = 4_000L
        /** Composing a briefing may refresh weather/agenda; still bounded so the next briefing is always booked. */
        const val MORNING_WORK_BUDGET_MS = 9_000L
    }
}
