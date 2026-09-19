# JARVIS — Proactivity Reliability Closure Audit

**Baseline:** `umikasaiii/Jarvis-`, branch `claude/jarvis-mobile-automazioni-dashboard-b4xa7e`, commit `7d527d5da96d01a7174190a08dce82577346ea9d`.

**Audit date:** 2026-09-19. **Disposition: FAILED-DEVICE / NOT CLOSED.**

This is a read-only audit and an implementation specification, not an implementation. No repository code, configuration or documentation was changed. No Pass 15 work was started. No `Jarvis-core` or protocol repository was altered or inspected as if it were part of this checkout.

The remote branch API and the cloned checkout both resolved to the requested SHA. The checkout was clean. `docs/JARVIS_MASTER_ARCHITECTURE.md` was the first project document read; its evidence hierarchy and active ownership invariants govern this report. Source searches covered the repository, including all production Kotlin/Java/XML notification sinks and caller references; implicated implementations, manifest declarations and relevant tests were inspected. There is no `AGENTS.md` in this tree. `CLAUDE.md` provides the repository conventions.

Evidence labels used below:

- **CODE VERIFIED:** a reachable implementation or a deterministic defect at this SHA.
- **DEVICE REPORTED:** the user's post-14.2.3 observations, authoritative for acceptance.
- **CAUSAL CANDIDATE:** a verified vulnerability consistent with the symptom, without a captured trace proving it caused that particular incident.
- **PROPOSED:** future behavior or implementation, not present or validated today.

No APK was installed, no Honor telemetry was retrieved, no forecast from either reported incident was available, and no Gradle/Android/device tests were run in this audit. Existing test counts and historical CI statements are not new verification.

**Decision:** adopt a one-shot notification contract, eliminate notification refresh, repair actual signal acquisition and schedule reconciliation, give all three features a durable occurrence lifecycle, and base weather alerts on dated, location-bound, structured forecasts with persistent decision receipts. Do not call this “fixed” until the new implementation passes the device matrix.

## 1. Verified current-path matrix

Path shorthand in this report: `A/` = `app/src/main/java/com/simone/jarvismobile/`; `K/` = `core/src/main/kotlin/com/simone/jarvismobile/core/`. `:core` is the pure Kotlin module INSIDE the Android repository; it is not the external Windows JARVIS Core.

### 1.1 Morning Briefing: complete production graph

| Trigger / entry | Scheduler, receiver or service | Manager and occurrence | Composition and side effect | Verified limitation |
|---|---|---|---|---|
| FIRST_UNLOCK | `AutomationServiceController.syncFromSettings()` → opt-in `AutomationEventService`; runtime `ACTION_USER_PRESENT` receiver → `onUnlock()` | `evaluateOnUnlock(FIRST_UNLOCK)` → `run(isRealUnlock=true)` → `claim(MORNING_DIGEST:D)` | `snapshot(D)` → `candidatesFor()` → `ProactiveComposer.morningDigest()` → governor → `ProactiveNotifier.show()` → `NotificationManagerCompat.notify(7200, …)` | Requires a living service and registered receiver. A setting ON is not proof the observer is alive. No replay of a missed unlock. |
| NEXT_ALARM | `MorningTriggerScheduler.scheduleNextAlarmTrigger()` reads `getNextAlarmClock()`; offset default +5 min → `ExactAlarms` → explicit `AlarmReceiver`, `KIND_MORNING_BRIEFING` | `evaluateOnUnlock(triggerSource=NEXT_ALARM)` → same morning claim | Same composer, governor and notifier | Changes observed only through a manifest-only next-alarm receiver. Pending offset can be replaced/cancelled when the source alarm rolls. |
| CONFIGURED_TIME | `scheduleConfiguredTimeTrigger()` → `ExactAlarms` with key `morning_configured_time` → `AlarmReceiver` | `evaluateOnUnlock(CONFIGURED_TIME)` → same claim | Same composer, governor and notifier | Persistent setting and external alarm booking are not atomic. No revision validation in receiver. |
| PERIODIC_FALLBACK | `ProactiveScheduler` → unique periodic WorkManager job `jarvis_proactive_check` (1 hour) → `ProactiveWorker` | `evaluate()` → `run(isRealUnlock=false)`; morning offered only if `automationServiceEnabled == false` | Same pipeline if eligible | Disabled by desired service preference even when that service is actually dead. WorkManager does not promise an hourly delivery deadline. |
| MANUAL diagnostic action | `DiagnosticsViewModel.refreshProactiveDiagnostics()` | Calls the real `evaluateOnUnlock(MANUAL)` | Can send, speak, consume budget and own the real occurrence | A button called diagnostic refresh is a production trigger, not a passive inspection. |
| +10 refresh | `schedulePostBriefingRefreshes()` → unique one-time `MorningRefreshWorker`, `+10min` label | `refreshMorningDigestNotification()` reads today's row; gate permits **DELIVERED** | Fresh `snapshot()` → same morning composer → `show(silent=true)` → **notify again** | This bypasses new-delivery claim/governor and reintroduces dismissed content. |
| +60 refresh | Same, `+60min` label | Same read-only DELIVERED check | **notify again** | A third posting is explicitly scheduled by current design. |
| Process start / reboot / package replacement | `JarvisApplication.onCreate()` after restore recovery starts scheduler/service tasks; `BootReceiver` also calls morning `scheduleAll()` | No direct composition, but creates/replaces future trigger paths | Eventually paths above | Startup and boot can race reconciliation. Boot's sequential earlier operations can throw before morning scheduling. |
| User changes morning time | `ProactiveSettingsViewModel.setMorningBriefingTime()` → DataStore → configured-time scheduling in `NonCancellable` | Does not directly claim | Eventually configured path | Cancellation protection is not process-death durability or a database-to-AlarmManager transaction. |

Canonical initial path, as it actually exists:

`signal → adapter → ProactiveManager.run → Room morning claim → snapshot → composer → SharedPreferences-backed governor → markDeliveryAttempt → notifier.show → Android notify → SharedPreferences.save → optional TTS → markDelivered → schedule +10/+60`

The refresh branch is separate:

`WorkManager +10/+60 → refresh repositories → peek(MORNING_DIGEST:executionDate) → DELIVERED? → snapshot again → same composer → show(silent=true) → Android notify`

There are **two real MORNING_DIGEST-capable `show()` call sites**: the generic `run()` delivery branch and the refresh method. The third `show()` call site in the manager is a WEATHER_ALERT debug simulation, not a morning composer. There is one real morning composer; different content does not establish multiple renderers. The receipt string `MorningDigestV2` is a diagnostic label, not a discovered second rendering implementation.

### 1.2 Evening and weather producers

| Feature / producer | Actual path | Date and state authority | Side-effect behavior |
|---|---|---|---|
| Built-in Evening Digest | Any `ProactiveManager.run()` in hours 19, 20 or 21 → `candidatesFor()` → `eveningDigest(snapshot(D), D)` | Uses today's snapshot. Dedup only in `ProactiveStore` SharedPreferences; **no Room evening occurrence claim** | Generic notifier, ID 7202; optional speech. |
| Built-in Weather Alert | Same evening `run()` → snapshot refresh → `evaluateWeatherAlert()` → `WeatherAlertPolicy` → Room `WEATHER_ALERT:D+1` claim → governor | Target key is tomorrow by device date. Forecast's provider target date was discarded | Generic notifier, ID 7203; optional speech. |
| Weather simulation | Debug UI guard → `simulateWeatherAlert(category, mm)` | Different Room key `WEATHER_ALERT_DEBUG:date:hazard` | Still uses production WEATHER_ALERT notification ID/channel/message. No visible simulation label. |
| User rule “Se domani piove” | `RuleScheduler` / another event source → `AutomationExecutor` → `Condition.RainTomorrow` → action handlers | Context boolean from `RainDecision`, not `WeatherAlertPolicy` | Arbitrary saved notification/speech text can resemble the built-in rain warning. Independent rule occurrence. |
| Legacy user automation | Clock, unlock, morning-unlock, power/network/headset/other supported event → `AutomationRunner` | User's stored action text, legacy rule state | Direct Notify, Speak fallback and Tool-result notification. Can look like a briefing without being MORNING_DIGEST. |

The app source does not reveal which user-created rules are actually installed on the Honor. Similar wording alone cannot attribute the two reported weather incidents or every extra “Buongiorno” to the built-in path.

### 1.3 Weather request-to-notification trace

1. `OpenMeteoWeatherSource.fetchRain()` requests `/v1/forecast?latitude=…&longitude=…&daily=weathercode,precipitation_sum&timezone=auto&forecast_days=2` using rounded coordinates.
2. Decoder retains two arrays only: `weathercode` and `precipitation_sum`. Index 0 becomes today; index 1 tomorrow. It does **not** decode `daily.time`, provider timezone, units, precipitation probability, rain duration or liquid/snow breakdown.
3. `RainForecast` contains category/mm pairs, without provider dates, source location identity or retrieval metadata.
4. `WeatherManager.refresh()` resolves a saved place or a last-known fine-location fix up to 120 minutes old. It fetches rain, sets an in-memory `RainFetchDiagnostic`, calls `RainDecision` for booleans, then `ContextEngine.onWeather()` with category/mm.
5. `ContextEngine` is an in-memory `StateFlow<ContextState>`. It stores `weatherUpdatedAt = LocalDateTime.now()`, not forecast valid date or provider issue time. It derives availability with a six-hour age rule.
6. The same refresh then fetches weekly outlook separately and persists it through `SettingsRepository.weatherOutlookCache`. That richer cache is not what the proactive rain policy reads.
7. `ProactiveManager.snapshot()` waits for weather refresh and Health refresh. `evaluateWeatherAlert()` combines the in-memory rain diagnostic with `ContextEngine.tomorrowForecastFacts()`.
8. `WeatherAlertPolicy.evaluate(category, millimeters)` recognizes storms directly from category; rain passes at 0.2 mm; 10 mm labels “pioggia intensa”. It never receives probability or provider target date.
9. Claim is `WEATHER_ALERT:deviceToday+1`; the composer produces “Domani …”; governor applies allow/mute, dedup and the global daily budget, choosing only one candidate.
10. `ProactiveNotifier.show()` posts to the reminders channel, swallowing notification failures; manager records delivered afterward regardless of a meaningful notifier result.

The trace is entirely deterministic. No LLM currently decides the built-in weather alert, and none should be introduced.

## 2. Root causes ranked by evidence

| Rank | Finding | Evidence level | Consequence |
|---|---|---|---|
| P0-1 | +10/+60 refresh explicitly calls `notify()` after DELIVERED | CODE VERIFIED; strongly consistent with repeated device notifications | Silent repost is still a new visible notification after dismissal; changing weather/agenda data explains differing bodies and emoji. |
| P0-2 | NEXT_ALARM change receiver exists only in the manifest | CODE + Android API contract VERIFIED | Clock edits after scheduling need not reach it. Previous “no structural bug” conclusion is invalid. |
| P0-3 | NEXT_ALARM has no durable source-alarm occurrence; null/new `nextAlarmClock` cancels/replaces its +5-minute booking | CODE VERIFIED; incident attribution unproven | Source alarm expiration/dismissal or cold start during the offset can erase the intended briefing. A stable PendingIntent does not solve this. |
| P0-4 | FIRST_UNLOCK is a live-service dependency, while fallback eligibility uses the enabled preference | CODE VERIFIED; actual service failure on Honor unproven | Missing runtime receiver means missed unlock; periodic morning recovery is simultaneously suppressed by the ON setting. |
| P0-5 | Notifier returns Unit; permission failure returns normally; notify exceptions are swallowed | CODE VERIFIED | False DELIVERED consumes occurrence/budget and blocks later delivery. Delivery logs cannot prove Android received anything. |
| P0-6 | `DELIVERY_PENDING` becomes reclaimable after 10 min; updates have no owner token/state fence | CODE VERIFIED | Unknown side effects become blind retries; slow/stale owners may still mutate rows or post after takeover. |
| P0-7 | Evening composer says “Per domani” using today's appointments/tasks | CODE VERIFIED | Today-only items mislabelled as tomorrow; tomorrow-only appointments absent; no greeting or emoji. |
| P0-8 | Weather alert input cannot represent forecast confidence or meaningfulness beyond tiny daily accumulation | CODE VERIFIED; cannot prove provider was wrong in either incident | 0.2 mm trace/light precipitation can notify; a daily storm code alone always notifies. |
| P1-1 | Provider date, timezone, units and rain-location identity are lost before alert evaluation | CODE VERIFIED | A young forecast can still be for the wrong day/location. Six-hour freshness is not temporal grounding. |
| P1-2 | No-location and disabled refresh return early without replacing old rain diagnostic/context | CODE VERIFIED | Previously successful, still-young facts can survive a location failure/change. Weather-disabled alert itself is checked, but morning emoji can still read cached context. |
| P1-3 | Rain fetch failure overwrites ContextEngine with null facts and a new “updated at”; shared source error field races across request methods | CODE VERIFIED | Lost useful data / misleading status; error and forecast can be from different requests. |
| P1-4 | Morning delivery performs sequential agenda IO, two weather HTTP calls, Health refresh and possibly TTS inside receiver/service work | CODE VERIFIED; actual timeout not observed | Unbounded total critical path can exceed broadcast lifetime or be cancelled by service/process death. |
| P1-5 | Evening/weather rely on coarse periodic work or incidental unlock calls within 19:00–21:59 | CODE VERIFIED | Doze/network timing can miss the entire useful evening window. There is no dedicated durable evening decision deadline. |
| P1-6 | SharedPreferences governor load/decide/save is outside Room claim transaction; evening has no durable claim | CODE VERIFIED | Concurrent runs can duplicate evening, lose budget/dedup updates or starve weather. Battery priority 80 beats weather 70, morning 50 and evening 40. |
| P1-7 | `NonCancellable` is described as protection against process death; no durable schedule revision/outbox | CODE VERIFIED | Stale configured alarm is still possible. Separate hour/minute flow reads can mix concurrent setting revisions. |
| P1-8 | Null-tag numeric notification namespaces overlap | CODE VERIFIED; actual collision unknown | Reminder range 6000–10095 and legacy automation range 5000–9095 include all proactive IDs 7200–7203. They can overwrite/cancel a briefing. |
| P1-9 | Agenda `reload()` collapses local read failure to empty; fallback may use cached entries | CODE VERIFIED | “Nessun impegno” can be asserted after a read problem. Starred tasks from unrelated dates enter the digest. |
| P1-10 | Debug weather simulation uses the production notification namespace and text | CODE VERIFIED | A synthetic warning can replace/appear identical to a real warning without poisoning the production occurrence key. |

**Not established:** which exact MagicOS restriction was active; whether the Clock app exposed the user's alarm; actual notification/channel/exact-alarm permissions; original provider payloads; precipitation at the forecast grid location; identities of all device notifications. Those require the acceptance evidence below, not another assumption.

### 2.1 FIRST_UNLOCK diagnosis

Registration is present and dynamic, using `ContextCompat.registerReceiver(... RECEIVER_NOT_EXPORTED)` in the service. This is not a missing-register-call defect. `onCreate()` does not guarantee the service remains alive, registration succeeded, `startForeground()` succeeded, or that an unlock occurring before registration will replay.

Application startup runs only when Android starts the process and after the restore-recovery barrier. It is not an always-running supervisor. The startup comment that this is always a foreground-enough context is not valid for every receiver/worker cold start. `START_STICKY` is not a promise of uninterrupted observation. Failure can therefore be lifecycle/registration/start restriction/process death; MagicOS is a possible contributor, not a verified sole cause.

`ACTION_USER_PRESENT` must not be replaced with `ACTION_USER_UNLOCKED`: the latter concerns unlock of credential-encrypted storage after boot, not every keyguard unlock. Do not use Accessibility, hidden polling, a second service, or notification-text scraping to simulate a stronger signal.

The current earliest time is 05:00, with **no upper morning cutoff**. Every later unlock, even evening, can propose “Buongiorno” if that day's occurrence has not delivered. This also competes with evening candidates. Proposed cutoff is explicit in §4.

### 2.2 NEXT_ALARM diagnosis

Android documents `ACTION_NEXT_ALARM_CLOCK_CHANGED` as sent only to registered receivers; this checkout has no runtime registration for it. `getNextAlarmClock()` sees alarms scheduled through `setAlarmClock()`, not every third-party alarm representation. Both receiver acquisition and source availability must be tested. [Android AlarmManager API](https://developer.android.com/reference/android/app/AlarmManager)

Deterministic failure scenario: JARVIS sees an 08:30 source alarm and books 08:35. At/after 08:30 the next alarm becomes null or tomorrow's alarm. A call to `scheduleNextAlarmTrigger()` cancels 08:35 or replaces it with tomorrow's offset. That call may come from application cold start even if the broken manifest receiver never ran. Adding runtime registration alone can make this cancellation happen more consistently; it must ship together with source-occurrence preservation.

`ExactAlarms` falls back to inexact scheduling when permission is absent, so exact permission is a precision/degradation issue, not proof of the observed cancellation. Its Boolean `schedule()` also reports SecurityException as true because it tests only `!= FAILED`; morning uses the richer outcome method, but other schedulers do not. Fix this shared adapter contract without changing their business logic.

### 2.3 Corrections to prior audit conclusions

- “Silent refresh closes duplication” is superseded: it does not close notification reappearance.
- “FIRST_UNLOCK has no architectural gap because startup calls sync” is superseded: preference, lifecycle readiness and event observation are different facts.
- “NEXT_ALARM has no structural bug because PendingIntent identity is stable” is superseded by receiver registration and source-rollover defects.
- “NonCancellable makes persist-and-schedule atomic against process death” is false.
- “Hourly periodic work caps delay near one hour” is not an Android scheduling guarantee.
- “Expired DELIVERY_PENDING is safe to retry” contradicts the master's UNKNOWN/no-blind-retry invariant.
- “Daily code is the dominant condition / proves confidence” is incorrect. Open-Meteo defines it as the day's most severe condition. [Open-Meteo daily variables](https://open-meteo.com/en/docs)

Record these as explicit supersessions in the future documentation change; preserve the historical record.

## 3. Every direct and indirect notification side-effect path

### 3.1 Actual sinks and namespace interference

| Sink | Reachable producers | Can affect Morning identity/content? |
|---|---|---|
| `A/proactive/ProactiveNotifier.kt:show` | `run()` deliver; morning refresh; weather simulation | Direct morning post and refresh, IDs 7200–7203, null tag. |
| `A/alarms/AlarmReceiver.kt:notifyReminder` | Reminder alarms scheduled by `ReminderScheduler`; agenda reloads also reconcile these alarms | Does not call morning composer; arbitrary entry text can resemble one. **Numeric ID can equal 7200.** |
| `A/automation/AutomationRunner.kt:notify` | `Action.Notify`; `Action.Speak` if speech false; Tool success/failure message; supported event/clock/manual automation runs; residual `AutomationWorker` | Arbitrary text can resemble briefing/weather. **Numeric ID can equal 7200.** |
| `A/automation/rule/ActionHandlers.kt:NotifyActionHandler` | `SHOW_NOTIFICATION`; `SpeakActionHandler` fallback, via `AutomationExecutor` | Independent user-rule message; tag `jarvis_automation` prevents exact proactive ID collision. |
| `A/background/AssistantTaskWorker.kt:showReadyNotification` | Persistent assistant task completion | Arbitrary assistant answer, not a built-in proactive composer; IDs 3000–7095 do not reach 7200. |
| `A/backup/BackupWorker.kt` | Backup status/result | ID 7100, not morning. |
| `A/navigation/NavigationService.kt` | Navigation progress | ID 7100, not morning; collides with backup, a separate observed code risk outside closure scope. |
| Foreground notifications | `ListeningService` 1001, `DrivingModeService` 1002, `AutomationEventService` 1102, assistant processing `ForegroundInfo` 2000–6095, navigation 7100 | None is a direct morning renderer; do not count foreground-service status as another Morning occurrence. |
| `ProactiveActionReceiver` | Mute action; accepts any valid enum extra | Calls cancel on proactive ID. Digests no longer expose mute actions, but the receiver itself does not restrict them. |
| `ReminderActionReceiver` | Mark-done PendingIntent | Cancels supplied ID. A colliding reminder ID can cancel morning content. |

`JarvisNotifications.styled()` builds notifications; it is not itself a posting owner. Notification listener access checks and DND/channel configuration are not message-posting paths.

### 3.2 Indirect producers that must remain in regression coverage

- `snapshot()` and `MorningRefreshWorker` call `agenda.reload()`, which invokes `ReminderScheduler.sync()`. Refresh therefore has scheduling side effects even before proactive notify. Under the new contract, refreshing source data may reconcile legitimate reminder state, but it cannot republish a digest or claim its identity.
- Unlock dispatches **three independently meaningful branches**: legacy screen/morning automations, built-in ProactiveManager, and new-rule FIRST_UNLOCK_OF_DAY. User-authored “Buongiorno” remains possible outside the built-in feature. It must be labelled as an automation; never silently delete a rule by matching message text.
- Weather-refresh workers update data; they do not directly call ProactiveNotifier. Rule alarms force weather refresh before evaluating `RainTomorrow`; other rule events can consume cached ContextEngine data.
- Manual diagnostic evaluation is a live production producer today. Debug simulation is a live production notification producer today. Both need isolated, visibly synthetic behavior.
- `speakBackgroundResponse()` is an additional side effect after initial notify. It has no built-in notification fallback in ProactiveManager, but does in automation action handlers. TTS completion must not control whether the notification is committed.
- Old work surviving an app update can still instantiate `MorningRefreshWorker`. Removing scheduling alone is insufficient; change the worker implementation to be data-only as part of the same cutover.

The exact strong invariant should govern **posting/updating**, not merely sound or the number of currently active cards. If interpreted literally as every NotificationManager operation, remove digest cancellation from `ProactiveActionReceiver` too. User dismissal and Android's own automatic removal are not JARVIS issuing another call. Passive OS inspection can be allowed separately for diagnostics, with no repost or mutation.

## 4. Final canonical ownership architecture — PROPOSED

### 4.1 Product contract and one-shot invariant

Adopt:

> For `MORNING_DIGEST:<logicalDate>`, once notification dispatch has returned successfully, no application path may post, update or recreate that day's notification. Dismissal, opening, +10/+60 refresh, settings edits, process restart, retries and source changes do not reopen it.

Strengthen it at the crash boundary:

> Once a durable dispatch intent enters the possibly-side-effecting state, an unknown outcome is never automatically retried. Only a proven pre-dispatch/no-effect failure may retry.

This is an **at-most-once automatic dispatch policy with explicit UNKNOWN**, not an impossible guarantee of exactly-once human-visible delivery across SQLite, Android's notification service and process death. Android `notify()` returning proves an API call returned, not that the user saw a heads-up, heard sound or read it. Track `POSTED`, `ACTIVE_OBSERVED` and user-open/dismiss evidence separately if observed; never infer one from another.

**+10/+60 become data refresh only.** They may refresh agenda/weather/Health and update dashboard/cache flows. They must have no ProactiveNotifier or notification-dispatch dependency, no morning composer invocation for notification, no speech and no mutation of the morning delivery state. Bind work input to its original logical date and delivery receipt ID; expired work must not switch to today's occurrence. Retain unique WorkManager names and background Health permission checks.

Freeze the delivered notification's factual snapshot and presentation choice. A later weather emoji appearing in the dashboard is valid; silently replacing the already-delivered briefing is not.

### 4.2 One owner for each responsibility

| Responsibility | Canonical owner | Forbidden competing owner |
|---|---|---|
| User settings | `SettingsRepository` | UI-local scheduling defaults, duplicated toggle stores |
| Temporal proactivity plan and reconciliation | Extend existing `ProactiveScheduler` to absorb morning scheduling decisions | Keep `MorningTriggerScheduler` as an independently active scheduler |
| OS adapters | `ExactAlarms`, WorkManager | Receivers recomputing business policy or owning occurrences |
| Runtime device signal observation | Existing `AutomationEventService`, through `AutomationServiceController` | Second unlock service, notification sniffing or new polling loop |
| Trigger eligibility, candidate orchestration | Existing `ProactiveManager`, refactored to typed requests | Receivers/workers/diagnostics deciding to deliver directly |
| Durable occurrence, dispatch permission and budget | Existing Room `ProactiveOccurrenceStore`/DAO extended transactionally | SharedPreferences dedup/budget as another authority |
| Agenda facts | Existing `AgendaRepository` | Proactive copy of agenda, CalendarContract queried independently |
| Weather acquisition, dated forecast cache, request result | Existing `WeatherManager` + `WeatherSource` | ContextEngine inventing fresh dates or a second provider client |
| Context | `ContextEngine` as a derived projection of dated facts | Undated booleans as the authoritative forecast |
| Weather decision | Pure versioned `WeatherAlertPolicy` on structured facts | LLM, text matching, emoji parsing |
| Composition | Existing `ProactiveComposer` with date-specific typed inputs | Alternative notification composer in refresh/diagnostics |
| Android proactive posting | `ProactiveNotifier` with internal dispatch operation usable only by the occurrence-controlled dispatcher | General public `show(suggestion)` bypass |
| Decision proof | Immutable `ForecastDecisionReceipt` written by weather evaluation and linked to occurrence | Receipt mutating forecast truth or a diagnostic boolean controlling eligibility |

`WeatherScheduler` remains the owner of ordinary periodic weather cache maintenance. It does not own proactive delivery. `RuleScheduler` and `ReminderScheduler` remain owners of user rules and reminders, not built-in digests. Reuse their shared `ExactAlarms` adapter; do not merge unrelated domains into one giant scheduler.

The morning scheduler class may temporarily remain as a **stateless forwarding compatibility adapter** during cutover, with zero settings reads, calculation, alarm/WorkManager calls or state. Final state removes it and its injection sites. This is not permission for two independently computing schedulers.

### 4.3 Typed trigger and schedule contract

Replace `isRealUnlock=true` for every kind of signal with a typed request containing:

`source, observedAtInstant, intendedLogicalDate, zoneId, scheduleRevision?, scheduledForInstant?, sourceAlarmIdentity?, diagnosticCorrelationId`.

Only a real USER_PRESENT observation is FIRST_UNLOCK. Manual preview, app-resume recovery, clock offset and configured-time fallback retain their actual identities.

Proposed product timings, explicit rather than inferred:

- Morning eligible window defaults to **05:00–12:00 local**, configurable only through a validated morning-window setting if needed. The existing unrestricted 00:00–23:59 configured-time UI must validate against this window, with migration/readiness notice for out-of-window existing settings. A first observed eligible unlock wins unless the same occurrence already posted.
- NEXT_ALARM means **offset after a system-exposed scheduled alarm time**, not proof the user woke up or dismissed it. Default +5 min remains.
- Configured time is an explicit fallback; after it is due, periodic recovery may recover an unposted occurrence **regardless of the desired service toggle**. It must not post before fallback merely because periodic work ran.
- After the morning window expires, record `EXPIRED` and do not say “Buongiorno” in the evening. A schedule firing late carries its original date and cannot become tomorrow's morning.
- Proposed evening digest time: **20:00 local**, retaining the 19:00–22:00 eligibility window. Weather decisions start at 19:00, with bounded re-evaluation before 22:00 if facts are unavailable/below gate or delivery was provably not attempted. The cutoff is exclusive at 22:00.
- These are design defaults for this specification, not claims about previously approved exact evening copy or timing. They are centralized settings/policy, not hidden constants in several callers.

Persist the desired plan revision, intended instants/date, source alarm metadata, enable state and reconciliation result. DataStore is still settings truth; a schedule row is a derived execution plan. Read all relevant settings from one snapshot, not separate hour/minute flows. Serialize reconciliation; if the settings revision changed during calculation, do not commit an old plan as current.

Settings changes create durable reconciliation work. Reconcile after startup **and restore barrier**, boot, package replacement, clock/timezone change, relevant permission grant, app foreground/resume, source alarm changes and schedule execution. Receivers validate plan revision, intended date, due time and enabled state before any dispatch. Late obsolete intents become no-ops with evidence. Failures in agenda/place rearming cannot prevent proactive reconciliation.

AlarmManager and the local plan cannot commit atomically. Make reconciliation idempotent and recoverable; OS booking “returned successfully” is separately recorded. A fixed PendingIntent identity prevents duplicate bookings for a slot; it does not prove that the booking matches current settings.

Exact access absent: use the existing inexact fallback, persist `DEGRADED_INEXACT`, and make readiness visible. A SecurityException is failure, never Boolean success. Do not claim minute precision or request new broad permissions simply to hide degraded state. Android documents exact-access revocation, cancellation of exact alarms and reconciliation on access grant. [Android alarm scheduling](https://developer.android.com/develop/background-work/services/alarms)

### 4.4 Preserve the source alarm through its offset

Maintain, in the same schedule owner, a source-alarm record: `sourceScheduledAt`, available source identity (opaque/local), observedAt, offset, intended fire time, logical date, revision and phase.

Required rules:

1. Before the stored source time, a reliable source change/cancellation can revise or cancel the pending record.
2. At or after that source time, freeze a still-valid offset occurrence. A null next-alarm or tomorrow's next-alarm **must not cancel the elapsed source's pending offset**. Store the new future source separately from the pending current one; one current-day due slot and one future observation are distinct data, not duplicate posting owners.
3. Snooze/new alarm observations after the source time do not move an already matured briefing forward forever. First eligible occurrence still wins. Document this behavior because `getNextAlarmClock()` does not report “dismissed versus snoozed”.
4. A next-alarm slot must remain date/window eligible. Source metadata never authorizes a second same-day digest.
5. When the service starts, register a runtime next-alarm observer and reconcile immediately from `getNextAlarmClock()`. On a missed registration/lifecycle gap, surface `OBSERVER_UNAVAILABLE` or `SOURCE_NOT_EXPOSED`; do not claim successful tracking.
6. If the Clock app does not expose an alarm, no general Android API here can reconstruct it reliably. Keep configured fallback and unlock as truthful alternatives. Do not add Clock notification parsing or Core involvement.

Runtime registration repairs acquisition while the process is alive. Persistent bookings allow an already observed source to fire later without it. **No design in this scope can promise first-unlock observation after force-stop or uninterrupted alarm-edit observation while the app has no live receiver.** That limitation belongs in readiness and acceptance, not in hidden optimistic comments.

### 4.5 Delivery state machine and dispatch fencing

Extend the existing occurrence table rather than add a second ledger:

- Key for morning: existing `MORNING_DIGEST:D`.
- Key for evening: `EVENING_DIGEST:D` (delivery evening), with agenda target `D+1` explicitly stored.
- Key for weather: existing `WEATHER_ALERT:T` (forecast target date). Location/model/policy changes do not mint a new daily alert after dispatch.
- Fields: owner token, monotonically increasing attempt generation, lease, intended date/zone, schedule revision, state, immutable receipt reference, budget reservation and timestamped outcome.

Proposed transitions:

`AVAILABLE / RETRYABLE_NO_EFFECT → CLAIMED → PREPARED → DISPATCH_INTENT → POSTED`

Other outcomes: `BLOCKED_PERMISSION`, `BLOCKED_POLICY`, `EXPIRED`, `FAILED_FINAL`, `UNKNOWN_EFFECT`.

Rules:

- Claim/takeover and every transition use expected state **and owner token/generation** in SQL. Check affected row counts. Old owners cannot finalize or reopen a new owner's row.
- Only CLAIMED/PREPARED with no dispatch intent can expire into safe takeover. `DISPATCH_INTENT`, existing ambiguous DELIVERY_PENDING and UNKNOWN_EFFECT never become retryable merely because time passed.
- Build/validate notification and perform known preflight checks before spending dispatch permission. Then transactionally enter DISPATCH_INTENT and commit; **if that durable transition fails, do not call Android**.
- Only the winner holding the fresh dispatch permission may call the internal notifier. No other API exposes `show()` to refresh workers, diagnostic helpers or arbitrary callers.
- A normal return records POSTED. Missing permission/channel/global notifications detected before dispatch records proven no-effect and can retry within the same logical occurrence after permission recovery.
- A crash after intent commit, during notify or before the POSTED commit is UNKNOWN_EFFECT. A later observation of the uniquely tagged active notification may reconcile state without a new call; absence is not proof no effect occurred because the user may have dismissed it.
- TTS is a separate optional child effect. Commit the notification outcome before speech. TTS failure must not release the notification occurrence. If speech retry would be ambiguous, do not replay it.
- Recheck user enable/disable state at dispatch authorization. A disable already committed must stop pending automatic delivery. No distributed claim of atomicity with the platform settings UI.
- Do not use receipt lists, WorkManager IDs, process IDs, `setOnlyAlertOnce`, `setSilent` or active-notification presence as delivery ownership.

`NotificationManager` is outside the database transaction. The unavoidable crash window trades possible omission for no automatic duplicate. Expose that as UNKNOWN and provide an in-app read-only briefing view, not a “retry” that silently violates the one-shot promise.

Use explicit stable IDs and **non-null feature tags**, e.g. `jarvis.proactive.morning`, `jarvis.proactive.evening`, `jarvis.proactive.weather`. Keep logical date/receipt in private extras. Do not derive IDs from enum ordinal. This prevents current reminder/legacy-automation numeric collisions without rewriting unrelated producers.

### 4.6 Budget and governor

The Room occurrence ledger becomes the authority for dedup and atomic budget reservation. `ProactiveGovernor` stays a pure decision policy over a transactionally obtained state; `ProactiveStore` becomes migration-only and then is removed.

Keep the user's explicit global maximum. With default maximum 3 and all three requested features enabled, reserve availability for morning, evening and a qualifying weather alert; an optional battery tip cannot spend their needed slots. If the user chooses a limit smaller than the enabled daily commitments, show the conflict, preserve their limit and record the selected suppression policy. A missing alert due to an explicit limit is a policy outcome, not forecast “no rain”.

One evaluation must not choose one candidate and silently forget all others until an incidental next unlock. Queue still-eligible occurrences within their deadlines, with a bounded spacing policy (proposed 2 minutes) for evening digest versus weather. Once a notification is posted, no new evaluation updates it.

### 4.7 Morning and evening factual presentation

Use separate typed inputs with explicit `deliveryDate`, `agendaTargetDate`, `weatherTargetDate` and data-status fields. Reuse `AgendaRepository` and its structured query outcome; do not duplicate local/vault merge rules or start querying external calendars from the composer.

Current agenda authority is a local file merged with the configured vault; vault entries win shared IDs and local-only entries remain. `reload()` hides a local read failure, whereas `queryResult()` exposes it. Use a date-scoped structured query or a small extension of that owner returning the required typed projection. Existing vault-null fallback limitations must be surfaced as degraded local data where distinguishable, not called a complete external calendar.

Proposed behavior:

- Morning: `Buongiorno <fresh dated weather emoji>`; today's incomplete appointments and tasks. If weather is unknown, omit the weather emoji or say weather unavailable; never substitute a guessed sun. Read failures cannot produce “nessun impegno”.
- Evening: **`Buonasera 🌙`**, then a clearly labelled **tomorrow** section from `D+1`. If desired, today's unfinished items are a separate `Da oggi restano…` section, never inserted under `Domani`.
- A moon is an evening greeting marker, not a claim about weather. If tomorrow's weather is shown, put its separately grounded emoji next to the tomorrow weather section, not next to today's data.
- Undated priorities and starred future tasks stay in a separate `Priorità aperte` group or are omitted from tomorrow's dated commitments. A star does not change an entry's date.
- A verified empty tomorrow can say `Domani non risultano impegni in agenda.` For the opted-in evening digest this specification recommends sending that short greeting once. Unknown/failure instead says the agenda could not be verified. This explicitly changes the current empty → no notification behavior.
- Do not infer “tomorrow's alarm” from `LocalTime` alone. Preserve the next alarm's instant/date before the battery-before-alarm text is allowed.
- Health refresh is not a required dependency for a digest that currently contains no Health fields. Preserve its data refresh function without making notification latency wait on it.

The critical trigger path must use bounded local/validated cached data; prefetch weather before expected morning/evening decisions and refresh opportunistically. Do not perform two synchronous HTTP requests plus Health/TTS in a broadcast callback. Persist the trigger and hand off bounded durable work; a fast local dispatch path may finish within the receiver budget, but it must use the same occurrence owner. WorkManager handles deferred work/retries and is not a claim of instant first-unlock delivery. [Android broadcast lifecycle guidance](https://developer.android.com/develop/background-work/background-tasks/broadcasts)

### 4.8 Weather facts and policy

**Provider facts:** Open-Meteo's daily code is the most severe daily condition; `precipitation_sum` includes rain, showers and snow; `rain_sum` and `showers_sum` separate liquid components; `precipitation_hours` is duration information. Its precipitation probability relates to occurrence of measurable precipitation, not confidence that the entire day is rainy or a thunderstorm probability. These fields need explicit interpretation, not an LLM. [Open-Meteo API definitions](https://open-meteo.com/en/docs)

Consequences for this code:

- `MIN_MILLIMETERS=0.2` is unsuitable as the sole meaningful-alert threshold; increasing it alone does not repair confidence, date, location, missing warning or delivery defects.
- Raw WMO codes must be retained. Current snow → RAIN mapping can produce “pioggia” from snowfall. Keep presentation categories separate from hazard facts.
- The current 10 mm/day → “pioggia intensa” label is a product heuristic, not a verified intensity measurement. Daily accumulation cannot establish an hourly intensity. Use factual wording such as “accumulo previsto di circa X mm” or “piogge abbondanti” under a validated product rule; do not present the old comment as a WMO standard.
- A single daily maximum precipitation probability is not a calibrated probability of meaningful rain. Daily maximum and storm code may refer to different hours. Add hourly alignment when claiming a storm risk/time window.

Extend the existing request/model, with validated nullable fields:

`daily.time, weather_code, precipitation_sum, precipitation_probability_max, precipitation_hours, rain_sum, showers_sum, snowfall_sum; timezone, utc_offset_seconds, daily_units`.

When hourly qualification is required: `hourly.time, weather_code, precipitation_probability, rain, showers, precipitation` with units and provider timezone. Match by date/time values, not `index * 24`; account for DST day lengths and missing periods. The existing hourly UI method does not currently provide these hazard facts, so it cannot be silently reused as if it did.

Return a request-scoped structured result rather than nullable data plus shared mutable `lastFetchErrorType`. Store `fetchedAtInstant`, request identity, provider valid date, timezone, unit validation, location revision/tag and field availability. Provider issue/model-run time is nullable when not supplied; **do not call `generationtime_ms` an issue timestamp**. The endpoint uses best-match unless a model was actually selected; never claim every response came from ICON merely because a comment says so.

`WeatherManager` is the single writer of persisted normalized dated forecasts. ContextEngine only projects that forecast by requested date. Concurrent refreshes use request tokens/location revisions and single-flight or monotonic acceptance so a slow response from old location A cannot overwrite current location B. No-location, permission loss and disabled states update the current request outcome; they must not leave an old success masquerading as the new attempt.

Validate target against the provider's explicit date, the selected location's forecast timezone and the device's intended delivery date. If travel/timezone semantics are ambiguous, suppress with `TARGET_DATE_MISMATCH` until a correctly dated request resolves it; never silently relabel index 1. A fresh fetch time does not make an old valid date fresh.

Freshness proposal: for an evening alert, require a last successful matching forecast no older than **3 hours**, aim to fetch within **60 minutes** of decision, reject future timestamps beyond a small clock tolerance, and record last failed refresh separately. A failed refresh may leave a matching, still-valid cached forecast usable under this explicit rule; it may not fabricate success or dry weather. Date/location mismatch always rejects regardless of age.

#### Candidate policy v2 — exact, but pending calibration

These are engineering starting thresholds, not proven meteorological optimality. Implement them as a versioned configuration, compare in replay/shadow mode, and do not claim weather reliability closure until §9 passes.

Let `P` be daily precipitation_probability_max in percent, `L` liquid total (`rain_sum + showers_sum`), and `H` precipitation_hours. Require finite values, valid units, P in 0–100, nonnegative amounts and a valid duration bound for that local day. Zero is data, null is unavailable. If liquid component fields are unavailable, total precipitation can substitute only when a validated raw rain/drizzle/shower code and no conflicting snow/mixed evidence make the interpretation safe; record this fallback explicitly.

| Input/result | Proposed deterministic decision |
|---|---|
| Wrong date/location, stale, future-dated, invalid units/values, missing mandatory confidence field | UNKNOWN with an exact reason; no automatic alert |
| Valid liquid forecast, `P >= 70` and (`L >= 1.0 mm` OR (`L >= 0.5 mm` AND `H >= 2 h`)) | Meaningful rain candidate |
| Below this gate with valid required inputs | NO_ALERT / BELOW_THRESHOLD, not “tomorrow will be dry” |
| Snow-only raw codes/amounts | Never render as rain; separate unsupported/mixed precipitation outcome, not a false rain alert |
| Storm WMO 95/96/99 | Request/check date-aligned hourly evidence; do not immediately notify solely from the daily code |
| At least one aligned storm-coded hour with precipitation probability >=70% and liquid precipitation >=0.2 mm in that hour | Storm-risk candidate; wording `Domani sono possibili temporali`, not a numerical probability of thunder |
| Daily storm code but required aligned hourly confidence unavailable/inconsistent | UNKNOWN_STORM_CONFIDENCE; bounded refresh/re-evaluation. A separately qualifying liquid-rain candidate may still issue a generic rain warning |
| Daily amount >=10 mm | May add the numeric accumulation to a qualifying candidate; it does not independently bypass confidence or assert intensity |

A high-confidence very short storm can qualify without two hours of rain; do not make the generic rain-duration threshold a storm veto. Conversely, this gate cannot guarantee that a forecast storm happens at the user's precise location. Validate false negatives and false positives separately and tune only on held-out chronological evidence.

Template examples: `Domani è probabile pioggia; accumulo previsto circa 3 mm.` / `Domani sono possibili temporali.` Add a time window only from aligned hourly facts. P is not attached to “temporale” as if it were thunder probability. No claim of exact probability calibration until validated.

Keep generic `RainTomorrow` condition semantics distinct from “worth a proactive interruption”: a user condition for any measurable rain should not silently acquire a 1 mm/70% gate. Both read the same dated forecast; `RainDecision` and `WeatherAlertPolicy` are explicitly different policies, not competing sources of truth. User-authored notification text is not weather truth and must be identified as an automation. Typed built-in weather alert actions, if routed through rules in future, must use the same weather policy and occurrence owner.

### 4.9 Persistent privacy-safe ForecastDecisionReceipt

Every weather evaluation needs a durable decision record, including suppression, unknown and no-alert outcomes. Every attempted production weather notification must reference one immutable record committed **before** dispatch. A receipt write failure prevents the alert from being sent without proof and is itself surfaced as diagnostic failure.

| Group | Required fields |
|---|---|
| Identity | receipt UUID; schemaVersion; app version/build SHA; evaluator/policy version; normalized input hash; evaluation sequence |
| Time | evaluatedAt UTC; requested target date; actual provider target date; device decision zone; forecast zone; fetchedAt; provider issue/run time nullable; horizon; age; validity interval |
| Provenance | provider ID; endpoint kind (no URL/query); model selection requested; actual model ID if returned, otherwise unknown; forecast/request ID; field/unit schema version |
| Location | opaque location-revision token; location mode saved-place/fix; matching-location Boolean; location fix age/status if used; **no coordinates, address or place name** |
| Facts | raw WMO; category projection; precipitation_sum; rain_sum; showers_sum; snowfall_sum; probability_max; precipitation_hours; exact validated hourly facts actually used for storm/time qualification; null/missing flags; normalized units |
| Validation | date match, location match, freshness result, field coverage, invalid-value reason, last request status/error-class enum, cache/live source |
| Decision | threshold configuration values; hazard/result enum; precise reason codes; candidateCreated; branch IDs; planned template ID; forecast facts digest |
| Delivery link | occurrence key; trigger/schedule revision; decision/budget outcome; notification namespace; dispatch-attempt ID |
| Outcome events | appended CLAIMED/PREFLIGHT_BLOCKED/DISPATCH_INTENT/POSTED/UNKNOWN, API result class; no fabricated user-seen status |

Store the decision inputs immutably and append delivery outcome events; never rewrite old facts when a new forecast arrives. Multiple evaluations for the same target each get a receipt; only the occurrence ledger authorizes dispatch. This allows a later audit to distinguish “forecast changed” from “our policy or delivery failed”.

Privacy/storage design: add the receipt and outcome tables to the existing Room database, with a single `ForecastDecisionReceiptStore` writer. No briefing body, task title, Health data, raw provider response, account/device identifier or URL query. An opaque random token per weather-location revision is sufficient for consistency checks; hashing latitude without a secret is not anonymization. Keep facts local by default and exclude receipt/trigger evidence from **exported backup copies**, without scrubbing the live DB or changing canonical agenda/automation data. Explicit sanitized export is a separate user action. The existing encrypted-backup opt-in is not a reason to silently add a detailed location-linked forecast history to cloud copies.

Default retention: 90 days and at most 4096 decision rows, with FK-safe deletion of outcome events. Protect the receipt referenced by an active/retained occurrence; prune those occurrences first only when outside the dedup/rollback safety horizon. Keep compact daily dispatch tombstones longer than detailed diagnostics if required by restore/time rollback policy. Bound in-memory views too. Numeric weather observations plus timestamps can still be personal context; keep export opt-in and redact location tokens when sharing.

The receipt proves **what forecast facts and policy caused JARVIS's decision**, not that the forecast was meteorologically correct. Independent observations are required for that claim.

## 5. Exact files/components to modify in a future implementation

No file in this table was modified during the audit. New filenames are proposed; existing paths are verified. Apply the `A/` and `K/` expansions defined in §1; test paths are written explicitly.

| Existing path | Required change |
|---|---|
| `A/proactive/ProactiveManager.kt` | Typed trigger request, explicit windows/target dates, cheap terminal dedup, bounded snapshot acquisition, receipt-linked candidate orchestration; remove notification refresh; do not infer delivery from Unit. |
| `A/proactive/ProactiveNotifier.kt` | Internal one-shot dispatch API, explicit preflight/result, feature tag/stable ID, no public suggestion-only bypass, no silent-update mode for digests. |
| `A/proactive/ProactiveOccurrenceEntities.kt` | Owner fencing, dispatch states, receipt IDs, atomic conditional transitions and budget reservations; evening occurrence; non-destructive schema change. |
| `A/proactive/ProactiveOccurrenceStore.kt` | Enforce durable dispatch permission and UNKNOWN handling; stop swallowing canonical-state write failures; return typed results. |
| `A/proactive/ProactiveOccurrenceMigrations.kt` | Migrate current v13 state conservatively; retain delivered history; never reset dedup. |
| `A/proactive/ProactiveStore.kt` | One-time legacy state import only; retire as runtime authority. |
| `A/proactive/ProactiveScheduler.kt` | Sole proactivity schedule planner/reconciler; absorb morning logic and add dated evening decision/catch-up plan; use existing OS adapters. |
| `A/proactive/MorningTriggerScheduler.kt` | Remove independent logic after absorption; compatibility forwarding only during cutover, then remove class/injections. |
| `A/proactive/MorningRefreshWorker.kt` | Data-only work, original date/receipt input, no notifier/manager delivery dependency; guard expiration and Health background permission. |
| `A/proactive/ProactiveWorker.kt` | Reconcile durable due work; typed outcome and bounded safe retries, not swallow everything then success. |
| `A/proactive/NextAlarmChangedReceiver.kt` | Runtime signal adapter or remove obsolete manifest-only implementation; delegate source observation without deleting matured offsets. |
| `A/proactive/ProactiveActionReceiver.kt` | Route permitted discretionary cancellation by tagged identity; reject digest mute/cancel replay if literal no-more-NM invariant is adopted. |
| `A/proactive/TriggerEvidenceStore.kt`, `TriggerEvidenceEntities.kt`, `TriggerEvidenceMigrations.kt` | Correlated schedule/runtime checkpoints, actual outcome labels and invoked bounded pruning; no diagnostic field becomes runtime authority. |
| `A/automation/AutomationEventService.kt` | Register next-alarm changes at runtime alongside unlock; report observer lifecycle; emit typed events; avoid duplicate source ownership. |
| `A/automation/AutomationServiceController.kt` | Desired state versus actual start/readiness evidence; idempotent recovery on allowed lifecycle entry points. |
| `A/alarms/AlarmReceiver.kt` | Validate typed date/revision and dispatch eligibility; short handoff; reconcile successor independently of slow work; no long network+Health+TTS path. |
| `A/alarms/ExactAlarms.kt` | Correct Boolean/outcome semantics; retain stable explicit PendingIntent; support revision payload and testable clock/adapter contract. |
| `A/alarms/BootReceiver.kt` | Independent, restore-safe proactive reconciliation; time/timezone/permission actions only where registered and supported; no unrelated failure short-circuit. |
| `app/src/main/AndroidManifest.xml` | Remove unsupported reliance on manifest NEXT_ALARM changes; register legitimate reconciliation actions; retain current foreground-service/notification constraints. |
| `A/JarvisApplication.kt` | Start one scheduler owner after recovery; no direct competing morning schedules; register readiness through existing service controller. |
| `A/ui/MainActivity.kt` | App-foreground reconciliation/readiness hook if not represented by a shared lifecycle observer; never invent a FIRST_UNLOCK event. |
| `A/ui/settings/ProactiveSettingsViewModel.kt` | Durable reconciliation request after atomic setting snapshot; category/toggle/offset changes all reconcile; remove false NonCancellable durability claim. |
| `A/ui/settings/SettingsScreen.kt`, `A/data/SettingsRepository.kt` | Validated morning window/time, evening time, coherent settings version and honest readiness; no hidden changed defaults. |
| `A/weather/OpenMeteoWeatherSource.kt` (also holds `WeatherSource`, `RainForecast`, outlook DTOs) | Dated structured response, requested confidence/duration/liquid fields, request-scoped result/error, raw WMO, units/timezones and necessary hourly data. |
| `A/weather/WeatherManager.kt` | Single normalized forecast writer/cache, per-request state, location revision, concurrency protection, distinct latest attempt/last good facts. |
| `A/weather/WeatherOutlookCache.kt` | Preserve explicit valid dates/location metadata for consumers; do not relabel positional cached days after midnight. |
| `A/weather/WeatherRefreshWorker.kt`, `WeatherScheduler.kt` | Typed refresh outcomes, same manager, no alert side effects; coordinated prefetch request without a second delivery scheduler. |
| `A/context/ContextEngine.kt`, `K/context/ContextModel.kt` | Date/location-aware derived weather projection and status; remove undated freshness authority. |
| `K/weather/WeatherAlertPolicy.kt` | Versioned meaningful/confident decision from normalized forecast facts; exact unknown/no-alert reasons; no LLM. |
| `K/weather/RainDecision.kt`, `WeatherCategory.kt`, `WeatherFreshnessPolicy.kt` | Separate condition semantics from alert policy; preserve raw snow/mixed distinctions; Instant/date/location validity. |
| `K/proactive/ProactiveModels.kt`, `ProactiveComposer.kt`, `ProactiveGovernor.kt`, `ProactiveOccurrence.kt` | Typed temporal snapshots, correct evening text, fenced states, ledger-backed budget semantics, conservative rendering. |
| `K/proactive/MorningRefreshGate.kt` | Remove notification-refresh authorization contract; delete or repurpose only as data-refresh eligibility with no side effects. |
| `A/agenda/AgendaRepository.kt` | Small structured snapshot/query extension only if required for dates/status; retain sole agenda owner and merge behavior. |
| `A/background/JarvisDatabase.kt`, `A/di/DatabaseModule.kt`, `A/di/WeatherModule.kt` | Room schema/migration wiring, DAO providers, no destructive production migration; test schema evolution. |
| `A/ui/diagnostics/DiagnosticsViewModel.kt`, `DiagnosticsScreen.kt` | Passive diagnostics; explicit production evaluation action if retained; isolated previews; persistent receipts for all delivery stages. |
| `A/backup/BackupRepository.kt`, `RestoreSanitize.kt` | Preserve one-shot safety across same-device restore; sanitize ambiguous restored dispatches; redact diagnostic receipts in backup copies; uphold existing startup barrier. |
| `docs/JARVIS_MASTER_ARCHITECTURE.md`, `docs/DEVICE_TEST_MORNING_BRIEFING_DEDUP.md`, `docs/DEVICE_TEST_WEATHER_ALERT.md`, `CLAUDE.md` | Explicit superseded conclusions, revised invariants, actual stage status and executable acceptance checklist. |

New components, within existing domain ownership:

| Proposed exact path | Role |
|---|---|
| `K/proactive/ProactiveTrigger.kt` | Typed trigger/date/source/revision contract. |
| `K/proactive/ProactiveSchedulePlan.kt` | Pure planning and reconciliation rules, including source-alarm rollover. |
| `A/proactive/ProactiveScheduleEntities.kt` | Durable derived plan/reconciliation state, not duplicate settings truth. |
| `A/proactive/ProactiveDeliveryDispatcher.kt` | Thin orchestrator of ledger-issued dispatch permission and internal notifier; no second decision policy. |
| `K/weather/ForecastFacts.kt` | Validated dated forecast/provenance/status types. |
| `K/weather/ForecastDecisionReceipt.kt` | Immutable privacy-bounded decision schema and reason enums. |
| `A/weather/ForecastDecisionReceiptEntities.kt` | Receipt/outcome Room tables. |
| `A/weather/ForecastDecisionReceiptStore.kt` | Single receipt writer/read API and retention. |

Do not expand this into unrelated automation, speech, classifier or backup redesign. Existing arbitrary automation notifications remain distinct features; tag isolation on proactive output fixes the relevant ID overlap. Fix the backup/navigation ID 7100 collision separately only if later authorized as an additional objective.

## 6. Migration plan with no duplicate owners

1. **Freeze baseline and contracts.** Confirm SHA/branch before implementation; inspect any intervening changes rather than overwrite them. Pin the new one-shot contract and record the old conclusions as superseded. Add behavioral regression fixtures reproducing the defects before replacing the old source-scan assertions.
2. **Change refresh behavior first in the same release.** Existing `MorningRefreshWorker` class becomes data-only, so already queued +10/+60 jobs cannot post under new code. Cancel obsolete refresh schedules by their existing tag and re-enqueue only supported data-refresh work when appropriate. WorkManager cancellation alone is not the safety boundary.
3. **Non-destructive Room migration.** At this baseline schema is 13. Add a complete 13→14 migration (or the next version if baseline changed), including every required table/column/index and schema tests. Never use destructive fallback to make migration pass. Preserve occurrence keys.
4. **Import legacy evidence exactly once.** Existing DELIVERED maps to conservative POSTED/legacy tombstone. SharedPreferences delivered keys with no Room row also become terminal legacy records, including evening keys. Preserve budget conservatively. Do not “repair” an old DELIVERED to pending simply because the old notifier may have lied; that would create duplicates without proof.
5. **Handle ambiguous old states.** Any existing DELIVERY_PENDING maps to UNKNOWN_EFFECT; do not time it out into a new send. CLAIMED/GENERATED from the old unfenced implementation should be treated conservatively during upgrade (UNKNOWN unless no-effect is actually provable), because its mark-attempt failures were swallowed. Proven no-effect FAILED_RETRYABLE may be re-evaluated within the valid window.
6. **Activate one delivery owner atomically with callers.** All morning/evening/weather call sites move to the ledger-controlled dispatcher. Remove public direct `show` use, including debug production namespace. No temporary “old path on failure” switch.
7. **Consolidate scheduling.** `ProactiveScheduler` absorbs morning plans. Remove old injections/calculations or retain a stateless forwarding adapter for one migration release. Cancel/reconcile the legacy keys `morning_next_alarm`, `morning_configured_time`, `jarvis_proactive_check` and `jarvis_morning_refresh*` deliberately; reuse stable identities where safe. Generation-tagged intents from obsolete plans are rejected by the new receiver.
8. **Install runtime NEXT_ALARM observation and source freezing together.** Do not ship registration alone while the cancellation bug remains. Boot/start/resume reconciliation all delegate to the same scheduler.
9. **Migrate notification namespace without a duplicate.** New occurrences use non-null feature tags. Today's legacy delivered record suppresses a new tagged post even if the old untagged card is absent. Do not blanket-cancel IDs 7200–7203: a colliding reminder could own one. A tagged tomorrow occurrence can proceed under tomorrow's key.
10. **Invalidate undated forecast caches for alert eligibility.** Old data without provider date/location/required fields is UNKNOWN for the new alert policy. Do not invent probability or reconstruct provider dates from current time. UI may display explicitly stale legacy information if appropriate, but alert qualification cannot use it.
11. **Weather v2 qualification.** Run pure replay and shadow evaluation without production notification side effects. Shadow uses the same normalized forecast/policy implementation in read-only evaluation mode, not a second independent policy engine. Switch active policy version once validation gates pass; never have v1 and v2 both own notification delivery.
12. **Restore, rollback and clock changes.** Keep compact delivered/unknown tombstones across ordinary updates and same-device restore; merge pre-restore current-day dispatch evidence before any old snapshot can re-enable a send. Restore of in-flight work is not authorization to execute. If prior state cannot be recovered, conservatively inhibit automatic replay for the affected current period and show the limitation. Do not promise dedup through uninstall/clear-data without preserved evidence. An incompatible older APK must not be used as a data-destructive downgrade.
13. **Remove legacy runtime authority.** After import, `ProactiveStore` must not receive new writes. Delete obsolete refresh gate/tests and scheduler calculations. Final static scan plus behavioral sink tracing verifies one owner. Update docs to actual results, not planned pass counts.

User-authored rules are not automatically removed, reclassified or text-matched into built-in occurrences. Provide a provenance inventory of any overlapping rules during device testing. Only user-reviewed rules or structurally identified built-in legacy records can be migrated/disabled; no invented historical IDs.

## 7. Automated test matrix — required, not executed here

These tests protect actual observable outcomes. Existing tests asserting three call-site strings or `silent=true` encode the obsolete design and must be replaced, not treated as acceptance.

| ID | Layer / scenario | Required assertion |
|---|---|---|
| N01 | Real dispatcher with fake notification sink, all morning triggers in parallel | One durable dispatch authorization and at most one actual notify call. |
| N02 | +10/+60 after delivered; shade present, dismissed, opened or cancelled by OS | **Zero** subsequent morning notify/update/cancel calls and zero speech; caches may refresh. |
| N03 | Process/repository reconstruction after POSTED | Every same-day signal remains suppressed without in-memory flags. |
| N04 | Kill/fault after claim, preparation, dispatch-intent commit, notify entry/return, before POSTED commit | Only proven pre-effect states retry; DISPATCH_INTENT/UNKNOWN never blindly retries. |
| N05 | Slow owner A, takeover B, A resumes | Owner-token CAS prevents A dispatch/finalization; terminal states cannot be overwritten. |
| N06 | Failure committing mark-attempt/receipt/budget | No Android call. Canonical DB failure is not swallowed. |
| N07 | POST_NOTIFICATIONS absent, global notifications disabled, channel blocked | Explicit preflight outcome, no false POSTED; allowed safe recovery only before dispatch. |
| N08 | Notify throws / process disappears during call | Conservative typed unknown unless no-effect is proven; no automatic resend. |
| N09 | TTS slow/failing/cancelled after notify | Notification already committed; no notification/TTS replay via stale claim. |
| N10 | Force numeric legacy reminder and automation hashes to 7200–7203 | Non-null proactive tags prevent overwrite and wrong cancellation. |
| N11 | Debug weather and manual preview | Distinct debug tag/key/channel or in-app only, labelled synthetic, no production budget/key changes. |
| N12 | App architecture rule | Only dispatcher can obtain/use internal proactive notifier; refresh has no delivery dependency; all direct notify sinks enumerated. |
| S01 | Real runtime receiver setup (Robolectric/instrumentation) | USER_PRESENT and NEXT_ALARM_CHANGED are registered in supported lifecycle; unregistered on destruction; no duplicate observers. |
| S02 | 08:30 source, +5 offset, source rolls to null/tomorrow at 08:30 | Current 08:35 occurrence retained; future source observation does not replace it. |
| S03 | Source edit/cancel before 08:30; snooze/change after 08:30 | Correct pre-maturity revision and documented post-maturity freezing. |
| S04 | Source not exposed by AlarmManager | Explicit unavailable status; configured/unlock fallback remains viable; no guessed source time. |
| S05 | Settings saved then process dies before OS scheduling; two rapid time edits | Durable reconciliation eventually uses last coherent revision, no mixed hour/minute. |
| S06 | Old scheduled intent races newer settings | Old revision rejected; correct date/due plan remains. |
| S07 | Exact permission unavailable/granted/revoked or SecurityException | Honest exact/inexact/failure result; Boolean adapter never treats exception as success. |
| S08 | Boot/update/clock/timezone/DST transition | One current plan; no yesterday catch-up relabelled as today; no double send on repeated hour. |
| S09 | Automation desired ON but service absent; periodic tick before/after fallback | Before fallback no early morning; afterward due undelivered occurrence can recover. |
| S10 | Morning before 05:00 / after cutoff; delayed worker next day | No misdated/late Buongiorno; expired event cannot consume a new day. |
| S11 | Restore barrier blocked / unrelated boot rearm throws | No canonical-state access before barrier; proactive reconciliation is not skipped by unrelated failure afterward. |
| E01 | Only today has an appointment; only tomorrow has another | Tomorrow section includes only tomorrow entry; today may appear only in labelled carryover. |
| E02 | Starred task dated next week, undated task, completed item, birthday-like text | No fabricated tomorrow deadline; completion/date rules explicit; no accidental duplicate grouping. |
| E03 | Verified empty agenda versus read failure | Empty wording only on successful empty; failure rendered as unavailable. |
| E04 | Concurrent evening worker and unlock | One Room evening occurrence, one call, one budget debit. |
| E05 | Greeting and emoji snapshots | Morning known category maps correctly; unknown not invented; evening `Buonasera 🌙`; weather target labelled. |
| E06 | Evening + weather + battery candidates with max 3; max 1/2; disabled/muted kinds | Atomic budget, requested daily commitments not silently preempted; explicit max preserved and suppression recorded. |
| W01 | MockWebServer response: dates reordered/missing, null elements, mismatched arrays, wrong units | Parse by provider date; no index assumptions or null-as-zero. |
| W02 | Decimal edge cases: negative, NaN/Infinity at domain boundary, P outside 0–100, impossible hours | UNKNOWN_INVALID_DATA; no alert. |
| W03 | P 69/70/71; L 0.19/0.2/0.49/0.5/0.99/1.0; H near 2 | Exact policy boundary results and reason codes. |
| W04 | Storm daily code, probability missing/low; storm hour vs high-probability non-storm hour | No confidence inferred from unrelated hourly maximum; aligned rule applied. |
| W05 | Snow-only, mixed, rain_sum/showers_sum unavailable, heavy daily total | Never label snow as rain; fallback explicit; amount does not fabricate hourly intensity. |
| W06 | Clock rollback, midnight, DST 23/25 hours, provider/device timezone mismatch | Valid-date/freshness checks independent; no fresh timestamp laundering wrong day. |
| W07 | Location A→B while A request is in flight; no location; weather disabled | Old A cannot satisfy B; stale successful diagnostic not reused for new request. |
| W08 | Concurrent rain/outlook/hourly requests, one fails | Results and errors remain request-scoped; no shared-error race. |
| W09 | Process restart offline with a previously valid dated forecast | Explicit cached freshness result; invalid/expired forecast does not alert. |
| W10 | Same target repeatedly evaluated with changed severity/location/policy | Receipts appended; once posted no second production dispatch. |
| W11 | Receipt serialization/replay | Replaying exact normalized inputs + policy config reproduces decision/reasons/template, byte-stable facts hash. |
| W12 | Receipt persistence failure, retention and backup export | No receiptless alert; no prohibited fields; bounded pruning; exported copy scrubbed without mutating live DB. |
| M01 | Room v13→new migration with every old state and SharedPreferences-only evening key | No loss, terminal/unknown preservation, idempotent import, no double owners. |
| M02 | Same-device backup restore from before today's post | Cannot resurrect today's delivery; restore barrier and tombstone reconciliation tested. |
| M03 | Old queued workers/old alarm extras after APK update | Data-only refresh; obsolete generation rejected; no production side-effect fallback. |

Use real Room transactions for race/migration tests; an in-memory fake DAO alone cannot prove SQL fencing. Use controlled clocks, coroutine barriers and a counting sink; do not simply mirror the same `if` statements in expected-value code. Keep source architecture rules as supplemental enforcement, not primary behavioral evidence.

Future commands from the repository's documented build interface:

- `cd core && ./gradlew test`
- `./gradlew :app:testDebugUnitTest`
- `./gradlew :app:assembleDebug`
- `./gradlew :app:lint`
- `./gradlew :app:connectedDebugAndroidTest`

Run applicable gates on the exact candidate SHA and retain actual output/artifact identity. If SDK/dependencies prevent a gate, label it BLOCKED rather than claim success. Passing these gates does not replace §8.

## 8. Honor 200 device acceptance matrix

Record once per run: build SHA/APK version, Android/MagicOS version, timezone, real Clock app used, effective notification/channel/exact permissions, battery/app-launch configuration, opt-in observer state and Core disconnected. Use opaque scenario IDs and synthetic agenda fixtures; do not export notification bodies or Health/agenda text.

Instrument actual sink entries with occurrence, tag/id, source, intended/actual time, dispatch state and result. Correlate service registration and schedule receipts. A single notification currently visible is insufficient: count **calls and reappearances over time**.

| ID | Real-device scenario | Pass criteria / evidence |
|---|---|---|
| H01 | Overnight with normal settings, observer ON, first eligible unlock before configured fallback | FIRST_UNLOCK observed → correct day's single dispatch; no fallback/refresh repost. Target local processing-to-post ≤5 s using cached/local facts; report actual latency. |
| H02 | Honor Clock alarm before configured time; no unlock before offset | NEXT_ALARM source exposed and persisted; +5-minute post survives source rollover; record source and sink times. Proposed exact-capable tolerance ≤60 s after intended offset. |
| H03 | Edit/cancel Clock alarm before source time | Revised/cancelled plan; no stale offset firing. |
| H04 | Alarm fires, dismiss/snooze it, then process restarts during +5-minute offset | Matured occurrence retained; no move to tomorrow/no duplicate. |
| H05 | Clock app/alarm not exposed by `getNextAlarmClock` | Readiness says source unavailable; fallback operates; no false “NEXT_ALARM reliable” claim. |
| H06 | Configured fallback only | One correct-day notification at intended minute when exact-capable; independent of Clock/service presence. |
| H07 | Change time twice, navigate away immediately, background/kill before reconciliation | Last persisted coherent revision wins; stale delivery rejected; schedule mismatch visible until reconciled. |
| H08 | Dismiss morning immediately, wait ≥90 min | No card reappears at +10/+60; zero second morning sink calls. Repeat with notification opened and left in shade. |
| H09 | Weather/Health arrive only after briefing | Dashboard/cache updates; original notification unchanged; no repeated speech. |
| H10 | Natural process reclaim / swipe from recents / service restart | Record actual observer lifecycle separately for each action; successful recovered delivery retains same occurrence. Do not equate recents swipe with force-stop. |
| H11 | Explicit Android force-stop, unlock before relaunch | Document unsupported observation, do not claim pass as first-unlock delivery. On relaunch, reconcile due in-window occurrence without replaying a posted one. |
| H12 | Reboot overnight and first credential unlock | Startup barrier respected; observer registration visible; schedules reconstructed; no next-alarm source loss due to startup race. |
| H13 | Effective exact permission denied where OS permits, then granted | Explicit degraded status; no false precision claim; grant reconciles. Inspect effective API state because both USE_EXACT_ALARM and SCHEDULE_EXACT_ALARM exist in this manifest. |
| H14 | Notifications denied / channel muted / DND on | No false visible-delivery claim; distinguish blocked channel from DND suppression; permission recovery cannot duplicate prior possible dispatch. |
| H15 | Battery saver/Doze and Honor automatic vs allowed background-launch settings | Report each actual configuration. Normal supported configuration meets its timing goal; restricted configurations show degradation, no duplicates. |
| H16 | Evening agenda has different synthetic items today/tomorrow | `Buonasera 🌙`, tomorrow facts only under tomorrow; optional today carryover labelled; one evening occurrence. |
| H17 | Evening and qualifying weather together, plus battery tip | Both requested messages are handled within deadline and explicit budget; no race, duplicate, or unrecorded starvation. |
| H18 | Airplane mode/no location at first unlock | Timely grounded morning from available data; no invented weather emoji or empty agenda assertion; no notification refresh later. |
| H19 | Dry and wet real days with receipt collection enabled | Every decision reconstructable; empirical weather score separate from delivery success. |
| H20 | Date/time/timezone change, including backward correction | No repeat for the same retained logical date; no yesterday weather relabelled tomorrow; due/expiry changes explained. |
| H21 | Upgrade candidate with old +10/+60 jobs queued and legacy card present | No republish under new tag; old work remains data-only. |
| H22 | Existing user notification rules enabled | Card provenance identifies built-in vs rule vs reminder; unrelated rules retained; no numeric overwrite. |
| H23 | Open Diagnostics / run synthetic preview | Merely viewing/refreshing diagnostics sends nothing; synthetic run is labelled and isolated. |
| H24 | Same-device backup/restore and subsequent unlock | No reactivated already-posted occurrence; no ambiguous pending replay; restored diagnostic data not misread as current facts. |

Minimum acceptance campaign: three consecutive ordinary mornings, one reboot morning, one process-reclaim morning, three evenings including paired digest/weather tests, plus the fault/configuration scenarios above. Count all sink calls through the full +90-minute observation period. Natural weather confidence requires the larger replay/observation sample in §9; a few device days cannot establish forecasting skill.

An impossible first-unlock guarantee while force-stopped is not a reason to mark the whole supported path successful. Acceptance must state exactly which lifecycle/configuration is supported and where the configured fallback is the available contract.

## 9. Weather historical/replay validation strategy

Two distinct validations are mandatory:

1. **Software reliability:** did JARVIS use the right location/date/facts, run the correct policy, respect settings and deliver once before the deadline?
2. **Forecast usefulness:** did the forecast discriminate meaningful precipitation well enough to justify interruption?

A correct forecast decision may still be followed by no rain. A rainy day can be missed by the provider, the policy or delivery. Without the original forecast and receipt, these explanations cannot be separated honestly.

### 9.1 Reconstruct the two reported incidents where possible

Request/obtain in a later device investigation: actual alert/evaluation dates and times, forecast target dates, selected weather place mode, notification producer provenance, build identity and available trigger receipts. There is no complete historical ForecastDecisionReceipt at this SHA; current weather diagnostics are process-local and do not retain input facts. Therefore the original incidents are **not retrospectively proven** by this audit.

If no original payload exists, archived forecasts can provide a labelled approximation, not proof of exactly what the app fetched. Do not fetch the current forecast and claim it explains last week's notification.

### 9.2 Use archived forecast runs, not hindsight

Open-Meteo's Historical Forecast series stitches early forecast hours across runs; it is not automatically the evening-before forecast the app had. Use **Single Runs** with a model initialization available before the decision cutoff where supported, or documented **Previous Runs** lead-time products. Respect archive/model/field availability and issue-to-publication latency. [Historical Forecast documentation](https://open-meteo.com/en/docs/historical-forecast-api), [Single Runs documentation](https://open-meteo.com/en/docs/single-runs-api), [Previous Runs documentation](https://open-meteo.com/en/docs/previous-runs-api)

Archive assumptions must be recorded: model identity, initialization time, assumed availability, requested location grid, probability model/resolution, local target date and timezone. A best-match historical approximation is not guaranteed identical to the live best-match blend originally served. Never use later runs or reanalysis as if they were the forecast available at 19:00 the previous evening.

Use gauge observations and, where available, quality-controlled radar/local observation products for outcomes at a suitable spatial scale. Reanalysis may be a secondary comparison, clearly labelled; it is not an eyewitness measurement at the exact user location. Missing/unrepresentative station data is UNKNOWN, not a dry negative. Obtain the actual observation source and rights when building the dataset; this audit did not acquire it.

### 9.3 Dataset and replay contract

- Build chronological target-day records for the relevant Rome/Lazio cells, multiple seasons and precipitation regimes. Aim for at least 180 days and enough wet/convective cases to report uncertainty; if fewer are available, label preliminary.
- Replay v1 and candidate v2 on the same **as-of** inputs. Keep threshold tuning dates separate from final chronological holdout dates; do not tune on the two anecdotes and call it calibrated.
- Define meaningful-rain outcomes before evaluating, e.g. ≥1 mm liquid/day or ≥0.5 mm spread over ≥2 observed wet hours, with storm occurrence a separate label where real observations support it. State observational resolution and missing coverage. These are product definitions, not universal meteorological standards.
- Include every decision/no-decision opportunity, not only posted alerts. Persist expected schedule opportunities so a missing receipt can be classified as “evaluation never ran”.
- Replay chain: normalized forecast fixture → validation/freshness → policy → receipt → fake occurrence/dispatch sink. A replay never writes production ContextEngine, occurrences, notifications or model training data.
- Version the schema, fixtures, policy and threshold file; retain hashes. Store public/synthetic fixtures in tests; never commit the user's precise locations or raw private receipts.

### 9.4 Metrics and release decision

Report confusion matrices, precision, recall, false-alert ratio, missed-meaningful-day rate, alerts/week, lead time and data coverage. Stratify storms, ordinary rain, very light rain, missing probability, location mismatch and stale data. Separate “policy chose alert but OS delivery failed” from meteorological false negative.

Do not use an undefined “accuracy” number dominated by dry days. Do not compute a Brier score for “meaningful-rain day” by treating `precipitation_probability_max` as that event's probability. Calibration requires a matching event definition or an explicitly derived/calibrated predictor; no LLM is involved.

Proposed release gate: v2 improves false-alert ratio on held-out days without hiding a materially worse meaningful-rain/storm recall; target precision ≥80% and meaningful-rain recall ≥75% are provisional product goals, reported with confidence intervals and sample counts. If unmet, keep WEATHER RELIABILITY OPEN and revisit thresholds/inputs/provider suitability. These goals are not results and are not grounds to claim guaranteed weather truth.

Run local shadow decisions for at least 30 days if historical probability/observation coverage is insufficient. Shadow mode creates receipts only; no second notifier. Investigate each miss using the attribution ladder: no scheduler run → unavailable facts → wrong date/location → below policy gate → governor suppression → dispatch blocked/unknown → posted but not visible → provider forecast error/spatial mismatch.

## 10. Semantic Impact Check

| Required check | Result |
|---|---|
| Representable with current intent/domain/operation/slots? | Yes: existing morning/evening/weather features. Trigger/date/status contracts are internal typed reliability data, not a new user-language intent. |
| New domain/capability? | No for this closure. No classifier taxonomy expansion. |
| New operation/slot? | Internal provider fields and schedule metadata only. Do not add language slots merely because a forecast DTO grows. |
| Semantic confusion? | Preserve today/tomorrow distinction in typed data; prevent user-rule text from being treated as provider truth. No keyword recognition workaround. |
| Retraining required? | No. Do not touch tokenizer, encoder, learned head, datasets, calibration artifacts or BLIND sets. |
| Regression needed? | Existing weather/agenda user-query behavior and temporal grounding must remain consistent; source data adapters can affect those callers, so test them. |

The existing birthday heuristic is not a justification for a new general keyword router. This audit does not authorize semantic training or Pass 15. A future natural-language preference command would require its own impact assessment if it introduced a new operation; it is not needed here.

## 11. Protocol / external Core impact

**No protocol change required. No external JARVIS Core work required.** Android owns observation, agenda/Health/weather access, occurrence reliability, local decisions, notification delivery and diagnostics. This closure must work with Core off, disconnected and unreachable.

The `core/` files listed in §5 are the Android repository's pure Kotlin domain module. They are proposed implementation locations for local policies/types; no external `umikasaiii/Jarvis-core` changes, event bridge, new endpoint, protocol version, shared server scheduler, remote forecast truth or Core-owned receipt storage is required.

Do not export receipts or user forecasts through Event Bridge. Do not wake an LLM for weather qualification or rendering of controlled facts. Forecast network access is the existing explicit weather opt-in, not permission to send agenda/Health context elsewhere.

## 12. Ready-to-execute implementation specification for Claude Code

The following is a **future handoff**. It is not an instruction to execute during this read-only audit. The user can give this report to Claude Code when implementation is authorized.

### Mandate

Implement the reliability closure described in this report on the verified Android branch, starting by reading `docs/JARVIS_MASTER_ARCHITECTURE.md`, `CLAUDE.md` and this full audit. Check branch/HEAD against `7d527d5da96d01a7174190a08dce82577346ea9d`; if it has advanced, inspect the diff and revalidate relevant paths. Preserve user changes. Do not start Pass 15, train models, touch external JARVIS Core or change the wire protocol.

Treat the user's post-14.2.3 Honor evidence as FAILED-DEVICE. Do not defend older comments or source-scan tests that encode silent repost. Do not equate code present, CI green, notify returning, notification active and device acceptance.

### Non-negotiable implementation requirements

1. No same-date morning post/update after posted or possibly dispatched; +10/+60 are data-only, including already queued worker classes.
2. All production proactive dispatches require the existing Room occurrence owner with CAS fencing, durable attempt permission and typed outcomes; evening joins it. Unknown effects never automatically retry.
3. No swallowing canonical DB transition failures followed by Android calls. ProactiveNotifier must report permission/preflight/API outcomes and cannot remain a public ungated `show(suggestion)` service.
4. ProactiveScheduler is the sole proactivity temporal owner. Absorb MorningTriggerScheduler; remove duplicate state/calculation. Existing reminder/rule/weather-data owners retain their own domains.
5. Runtime NEXT_ALARM_CHANGED observation and matured source-alarm preservation ship together. Fixed PendingIntent identity alone is not a fix.
6. FIRST_UNLOCK readiness reflects live observation; desired service ON must not suppress configured fallback recovery. Force-stop and unexposed alarms are explicit limits, never hidden success.
7. Receivers carry intended date/revision and stay bounded. Persist-and-schedule uses durable reconciliation, not a NonCancellable durability claim.
8. Agenda snapshots carry target dates/status. Evening uses tomorrow's agenda, `Buonasera 🌙`, and explicitly separates any today carryover. No empty-when-failed assertion. Never wait on unused Health fields before notification.
9. Weather requests retain dates, zones, location revision, raw codes, units, probability/duration/liquid fields and request-scoped failure. ContextEngine is derived. Weather policy is pure, versioned and independent of language models.
10. Receipt persisted before every alert dispatch; keep complete normalized decision facts, reason/version/thresholds and outcome linkage, with privacy and bounded retention from §4.9.
11. Stable non-null proactive notification tags; isolated debug previews. Passive diagnostics cannot consume today's occurrence.
12. Migration preserves delivered/unknown/legacy dedup, settings and user rules; no destructive database reset, duplicate owners, arbitrary rule deletion or silent fallback to old posting paths.

### Execution sequence and concrete deliverables

**Work package A — lock down side effects.** Add behavioral failing tests for refresh reappearance, false delivery, stale DELIVERY_PENDING retry, owner takeover and evening race. Refactor the notifier/occurrence transitions and add one-shot dispatcher. Convert MorningRefreshWorker to data-only. Isolate notification namespaces/debug simulation. Migrate the SharedPreferences ledger conservatively.

**Work package B — reliable signals and schedules.** Add pure schedule planning tests with a controlled clock and source-alarm changes. Consolidate scheduler ownership; implement persistent plan revisions/reconciliation and runtime observer wiring. Correct exact alarm outcome semantics, restore barrier integration, date/cutoff handling and fallback readiness. Avoid extra services/polling and long broadcast work.

**Work package C — factual presentation.** Introduce typed date-specific snapshot/composer inputs. Reuse AgendaRepository's error-aware read authority. Correct evening data/copy/emoji, keep morning unknown weather honest and move Health/network refresh out of the latency-critical path. Preserve user settings and source ownership.

**Work package D — weather proof.** Extend the provider request/normalization/cache with the structured fields and per-request result. Implement candidate policy v2 and persistent ForecastDecisionReceipt. Wire all decisions, including no-alert/unknown, into reproducible receipts; isolate synthetic fixtures. Preserve ordinary RainTomorrow condition meaning while sharing dated facts. Implement backup-copy diagnostic exclusion and retention tests.

**Work package E — qualification and handoff.** Run applicable §7 gates, obtain CI on the exact candidate SHA, and produce an updated call-path inventory proving every sink. Execute available replay with honest dataset provenance; mark unavailable historical data as pending. Provide the Honor §8 checklist and evidence bundle, without claiming device acceptance until it actually passes.

For each work package return: changed paths and why; ownership removed/retained; schema migration result; exact tests run and counts; blocked gates; Semantic Impact Check; external Core/protocol impact; and remaining device evidence. Use coherent reviewable commits only when separately permitted by the implementation task; never push/merge/deploy merely because this audit contains a future specification.

### Definition of done

- The app's counting sink records at most one automatic dispatch for each retained morning/evening/target-weather occurrence under concurrency, process restart and crash injection.
- No morning notification call from any +10/+60 or passive diagnostics path.
- NEXT_ALARM rollover fixture and supported Honor Clock scenario pass; FIRST_UNLOCK normal live-observer scenario passes with measured latency.
- Configured fallback, revision reconciliation, permission degradation and force-stop limitations are observable and correct.
- Evening uses the correct dates and greeting; no false empty/fresh/date claims.
- Weather decision inputs are dated, location-bound and sufficiently qualified under a versioned policy; every alert is reproducible from its receipt.
- One canonical owner per responsibility remains, with old work and legacy state migrated safely.
- Automated, CI, device and meteorological-quality gates are reported separately. If any material gate is open, the feature remains NOT CLOSED.

**Current audit outcome:** the closure architecture and implementation requirements are specified. The implementation, replay campaign and Honor acceptance are not performed. No production-readiness claim is made.


## Evidence index — immutable baseline source links

These links point to the audited commit, not the moving branch. Each anchor starts the relevant implementation; conclusions above concern the complete control flow, not a comment in isolation.

| Evidence | Pinned source |
|---|---|
| Master authority | [docs/JARVIS_MASTER_ARCHITECTURE.md:50](https://github.com/umikasaiii/Jarvis-/blob/7d527d5da96d01a7174190a08dce82577346ea9d/docs/JARVIS_MASTER_ARCHITECTURE.md#L50) |
| Old 14.2.3 conclusions | [docs/JARVIS_MASTER_ARCHITECTURE.md:1076](https://github.com/umikasaiii/Jarvis-/blob/7d527d5da96d01a7174190a08dce82577346ea9d/docs/JARVIS_MASTER_ARCHITECTURE.md#L1076) |
| Application startup/restore barrier | [app/src/main/java/com/simone/jarvismobile/JarvisApplication.kt:53](https://github.com/umikasaiii/Jarvis-/blob/7d527d5da96d01a7174190a08dce82577346ea9d/app/src/main/java/com/simone/jarvismobile/JarvisApplication.kt#L53) |
| Unlock event and sibling automations | [app/src/main/java/com/simone/jarvismobile/automation/AutomationEventService.kt:145](https://github.com/umikasaiii/Jarvis-/blob/7d527d5da96d01a7174190a08dce82577346ea9d/app/src/main/java/com/simone/jarvismobile/automation/AutomationEventService.kt#L145) |
| Runtime receiver filter | [app/src/main/java/com/simone/jarvismobile/automation/AutomationEventService.kt:248](https://github.com/umikasaiii/Jarvis-/blob/7d527d5da96d01a7174190a08dce82577346ea9d/app/src/main/java/com/simone/jarvismobile/automation/AutomationEventService.kt#L248) |
| Observer preference/start | [app/src/main/java/com/simone/jarvismobile/automation/AutomationServiceController.kt:45](https://github.com/umikasaiii/Jarvis-/blob/7d527d5da96d01a7174190a08dce82577346ea9d/app/src/main/java/com/simone/jarvismobile/automation/AutomationServiceController.kt#L45) |
| Manifest-only next-alarm receiver | [app/src/main/AndroidManifest.xml:353](https://github.com/umikasaiii/Jarvis-/blob/7d527d5da96d01a7174190a08dce82577346ea9d/app/src/main/AndroidManifest.xml#L353) |
| Next-alarm change receiver | [app/src/main/java/com/simone/jarvismobile/proactive/NextAlarmChangedReceiver.kt:35](https://github.com/umikasaiii/Jarvis-/blob/7d527d5da96d01a7174190a08dce82577346ea9d/app/src/main/java/com/simone/jarvismobile/proactive/NextAlarmChangedReceiver.kt#L35) |
| Source-alarm cancellation/replacement | [app/src/main/java/com/simone/jarvismobile/proactive/MorningTriggerScheduler.kt:84](https://github.com/umikasaiii/Jarvis-/blob/7d527d5da96d01a7174190a08dce82577346ea9d/app/src/main/java/com/simone/jarvismobile/proactive/MorningTriggerScheduler.kt#L84) |
| Configured time and refresh scheduling | [app/src/main/java/com/simone/jarvismobile/proactive/MorningTriggerScheduler.kt:128](https://github.com/umikasaiii/Jarvis-/blob/7d527d5da96d01a7174190a08dce82577346ea9d/app/src/main/java/com/simone/jarvismobile/proactive/MorningTriggerScheduler.kt#L128) |
| Alarm adapter | [app/src/main/java/com/simone/jarvismobile/alarms/ExactAlarms.kt:62](https://github.com/umikasaiii/Jarvis-/blob/7d527d5da96d01a7174190a08dce82577346ea9d/app/src/main/java/com/simone/jarvismobile/alarms/ExactAlarms.kt#L62) |
| Morning receiver delivery/re-arm | [app/src/main/java/com/simone/jarvismobile/alarms/AlarmReceiver.kt:124](https://github.com/umikasaiii/Jarvis-/blob/7d527d5da96d01a7174190a08dce82577346ea9d/app/src/main/java/com/simone/jarvismobile/alarms/AlarmReceiver.kt#L124) |
| Boot reconciliation | [app/src/main/java/com/simone/jarvismobile/alarms/BootReceiver.kt:29](https://github.com/umikasaiii/Jarvis-/blob/7d527d5da96d01a7174190a08dce82577346ea9d/app/src/main/java/com/simone/jarvismobile/alarms/BootReceiver.kt#L29) |
| Periodic scheduling | [app/src/main/java/com/simone/jarvismobile/proactive/ProactiveScheduler.kt:34](https://github.com/umikasaiii/Jarvis-/blob/7d527d5da96d01a7174190a08dce82577346ea9d/app/src/main/java/com/simone/jarvismobile/proactive/ProactiveScheduler.kt#L34) |
| Morning claim, snapshot and delivery | [app/src/main/java/com/simone/jarvismobile/proactive/ProactiveManager.kt:226](https://github.com/umikasaiii/Jarvis-/blob/7d527d5da96d01a7174190a08dce82577346ea9d/app/src/main/java/com/simone/jarvismobile/proactive/ProactiveManager.kt#L226) |
| Weather evaluation | [app/src/main/java/com/simone/jarvismobile/proactive/ProactiveManager.kt:392](https://github.com/umikasaiii/Jarvis-/blob/7d527d5da96d01a7174190a08dce82577346ea9d/app/src/main/java/com/simone/jarvismobile/proactive/ProactiveManager.kt#L392) |
| Production-namespace simulation | [app/src/main/java/com/simone/jarvismobile/proactive/ProactiveManager.kt:473](https://github.com/umikasaiii/Jarvis-/blob/7d527d5da96d01a7174190a08dce82577346ea9d/app/src/main/java/com/simone/jarvismobile/proactive/ProactiveManager.kt#L473) |
| Agenda/Health/weather snapshot | [app/src/main/java/com/simone/jarvismobile/proactive/ProactiveManager.kt:578](https://github.com/umikasaiii/Jarvis-/blob/7d527d5da96d01a7174190a08dce82577346ea9d/app/src/main/java/com/simone/jarvismobile/proactive/ProactiveManager.kt#L578) |
| Post-delivery notify bypass | [app/src/main/java/com/simone/jarvismobile/proactive/ProactiveManager.kt:670](https://github.com/umikasaiii/Jarvis-/blob/7d527d5da96d01a7174190a08dce82577346ea9d/app/src/main/java/com/simone/jarvismobile/proactive/ProactiveManager.kt#L670) |
| Queued refresh worker | [app/src/main/java/com/simone/jarvismobile/proactive/MorningRefreshWorker.kt:35](https://github.com/umikasaiii/Jarvis-/blob/7d527d5da96d01a7174190a08dce82577346ea9d/app/src/main/java/com/simone/jarvismobile/proactive/MorningRefreshWorker.kt#L35) |
| Actual notification sink | [app/src/main/java/com/simone/jarvismobile/proactive/ProactiveNotifier.kt:53](https://github.com/umikasaiii/Jarvis-/blob/7d527d5da96d01a7174190a08dce82577346ea9d/app/src/main/java/com/simone/jarvismobile/proactive/ProactiveNotifier.kt#L53) |
| Occurrence atomic claim but unfenced updates | [app/src/main/java/com/simone/jarvismobile/proactive/ProactiveOccurrenceStore.kt:65](https://github.com/umikasaiii/Jarvis-/blob/7d527d5da96d01a7174190a08dce82577346ea9d/app/src/main/java/com/simone/jarvismobile/proactive/ProactiveOccurrenceStore.kt#L65) |
| SQL transitions and takeover | [app/src/main/java/com/simone/jarvismobile/proactive/ProactiveOccurrenceEntities.kt:68](https://github.com/umikasaiii/Jarvis-/blob/7d527d5da96d01a7174190a08dce82577346ea9d/app/src/main/java/com/simone/jarvismobile/proactive/ProactiveOccurrenceEntities.kt#L68) |
| Ambiguous-state retry policy | [core/src/main/kotlin/com/simone/jarvismobile/core/proactive/ProactiveOccurrence.kt:66](https://github.com/umikasaiii/Jarvis-/blob/7d527d5da96d01a7174190a08dce82577346ea9d/core/src/main/kotlin/com/simone/jarvismobile/core/proactive/ProactiveOccurrence.kt#L66) |
| Second delivery-state authority | [app/src/main/java/com/simone/jarvismobile/proactive/ProactiveStore.kt:21](https://github.com/umikasaiii/Jarvis-/blob/7d527d5da96d01a7174190a08dce82577346ea9d/app/src/main/java/com/simone/jarvismobile/proactive/ProactiveStore.kt#L21) |
| Governor single winner/budget | [core/src/main/kotlin/com/simone/jarvismobile/core/proactive/ProactiveGovernor.kt:17](https://github.com/umikasaiii/Jarvis-/blob/7d527d5da96d01a7174190a08dce82577346ea9d/core/src/main/kotlin/com/simone/jarvismobile/core/proactive/ProactiveGovernor.kt#L17) |
| Evening wrong-date rendering | [core/src/main/kotlin/com/simone/jarvismobile/core/proactive/ProactiveComposer.kt:102](https://github.com/umikasaiii/Jarvis-/blob/7d527d5da96d01a7174190a08dce82577346ea9d/core/src/main/kotlin/com/simone/jarvismobile/core/proactive/ProactiveComposer.kt#L102) |
| Morning emoji mapping | [core/src/main/kotlin/com/simone/jarvismobile/core/proactive/ProactiveComposer.kt:29](https://github.com/umikasaiii/Jarvis-/blob/7d527d5da96d01a7174190a08dce82577346ea9d/core/src/main/kotlin/com/simone/jarvismobile/core/proactive/ProactiveComposer.kt#L29) |
| Provider request/positional parsing | [app/src/main/java/com/simone/jarvismobile/weather/OpenMeteoWeatherSource.kt:209](https://github.com/umikasaiii/Jarvis-/blob/7d527d5da96d01a7174190a08dce82577346ea9d/app/src/main/java/com/simone/jarvismobile/weather/OpenMeteoWeatherSource.kt#L209) |
| Weather refresh and early return | [app/src/main/java/com/simone/jarvismobile/weather/WeatherManager.kt:99](https://github.com/umikasaiii/Jarvis-/blob/7d527d5da96d01a7174190a08dce82577346ea9d/app/src/main/java/com/simone/jarvismobile/weather/WeatherManager.kt#L99) |
| Derived weather state | [app/src/main/java/com/simone/jarvismobile/context/ContextEngine.kt:137](https://github.com/umikasaiii/Jarvis-/blob/7d527d5da96d01a7174190a08dce82577346ea9d/app/src/main/java/com/simone/jarvismobile/context/ContextEngine.kt#L137) |
| Age-only tomorrow facts | [app/src/main/java/com/simone/jarvismobile/context/ContextEngine.kt:244](https://github.com/umikasaiii/Jarvis-/blob/7d527d5da96d01a7174190a08dce82577346ea9d/app/src/main/java/com/simone/jarvismobile/context/ContextEngine.kt#L244) |
| Alert threshold and storm bypass | [core/src/main/kotlin/com/simone/jarvismobile/core/weather/WeatherAlertPolicy.kt:77](https://github.com/umikasaiii/Jarvis-/blob/7d527d5da96d01a7174190a08dce82577346ea9d/core/src/main/kotlin/com/simone/jarvismobile/core/weather/WeatherAlertPolicy.kt#L77) |
| Rain threshold | [core/src/main/kotlin/com/simone/jarvismobile/core/weather/RainDecision.kt:33](https://github.com/umikasaiii/Jarvis-/blob/7d527d5da96d01a7174190a08dce82577346ea9d/core/src/main/kotlin/com/simone/jarvismobile/core/weather/RainDecision.kt#L33) |
| Snow/category loss | [core/src/main/kotlin/com/simone/jarvismobile/core/weather/WeatherCategory.kt:43](https://github.com/umikasaiii/Jarvis-/blob/7d527d5da96d01a7174190a08dce82577346ea9d/core/src/main/kotlin/com/simone/jarvismobile/core/weather/WeatherCategory.kt#L43) |
| False settings atomicity claim | [app/src/main/java/com/simone/jarvismobile/ui/settings/ProactiveSettingsViewModel.kt:111](https://github.com/umikasaiii/Jarvis-/blob/7d527d5da96d01a7174190a08dce82577346ea9d/app/src/main/java/com/simone/jarvismobile/ui/settings/ProactiveSettingsViewModel.kt#L111) |
| Manual production evaluation | [app/src/main/java/com/simone/jarvismobile/ui/diagnostics/DiagnosticsViewModel.kt:168](https://github.com/umikasaiii/Jarvis-/blob/7d527d5da96d01a7174190a08dce82577346ea9d/app/src/main/java/com/simone/jarvismobile/ui/diagnostics/DiagnosticsViewModel.kt#L168) |
| Reminder namespace collision | [app/src/main/java/com/simone/jarvismobile/alarms/AlarmReceiver.kt:203](https://github.com/umikasaiii/Jarvis-/blob/7d527d5da96d01a7174190a08dce82577346ea9d/app/src/main/java/com/simone/jarvismobile/alarms/AlarmReceiver.kt#L203) |
| Legacy automation namespace collision | [app/src/main/java/com/simone/jarvismobile/automation/AutomationRunner.kt:88](https://github.com/umikasaiii/Jarvis-/blob/7d527d5da96d01a7174190a08dce82577346ea9d/app/src/main/java/com/simone/jarvismobile/automation/AutomationRunner.kt#L88) |
| Independent rule notification/speech fallback | [app/src/main/java/com/simone/jarvismobile/automation/rule/ActionHandlers.kt:84](https://github.com/umikasaiii/Jarvis-/blob/7d527d5da96d01a7174190a08dce82577346ea9d/app/src/main/java/com/simone/jarvismobile/automation/rule/ActionHandlers.kt#L84) |
| Agenda source/empty failure | [app/src/main/java/com/simone/jarvismobile/agenda/AgendaRepository.kt:61](https://github.com/umikasaiii/Jarvis-/blob/7d527d5da96d01a7174190a08dce82577346ea9d/app/src/main/java/com/simone/jarvismobile/agenda/AgendaRepository.kt#L61) |
| Error-aware agenda query | [app/src/main/java/com/simone/jarvismobile/agenda/AgendaRepository.kt:220](https://github.com/umikasaiii/Jarvis-/blob/7d527d5da96d01a7174190a08dce82577346ea9d/app/src/main/java/com/simone/jarvismobile/agenda/AgendaRepository.kt#L220) |
| Restore sanitizer scope | [app/src/main/java/com/simone/jarvismobile/backup/RestoreSanitize.kt:47](https://github.com/umikasaiii/Jarvis-/blob/7d527d5da96d01a7174190a08dce82577346ea9d/app/src/main/java/com/simone/jarvismobile/backup/RestoreSanitize.kt#L47) |
| Tests enforce obsolete refresh behavior | [app/src/test/java/com/simone/jarvismobile/proactive/MorningBriefingCanonicalGateRegressionTest.kt:23](https://github.com/umikasaiii/Jarvis-/blob/7d527d5da96d01a7174190a08dce82577346ea9d/app/src/test/java/com/simone/jarvismobile/proactive/MorningBriefingCanonicalGateRegressionTest.kt#L23) |
| Tests only pin alarm identity | [app/src/test/java/com/simone/jarvismobile/proactive/MorningTriggerSchedulerAlarmIdentityRegressionTest.kt:29](https://github.com/umikasaiii/Jarvis-/blob/7d527d5da96d01a7174190a08dce82577346ea9d/app/src/test/java/com/simone/jarvismobile/proactive/MorningTriggerSchedulerAlarmIdentityRegressionTest.kt#L29) |
