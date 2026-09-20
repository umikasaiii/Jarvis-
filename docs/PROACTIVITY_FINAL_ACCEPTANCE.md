# PROACTIVITY FINAL ACCEPTANCE — WORK PACKAGE E

Honor 200 device acceptance protocol for the Proactivity Reliability
Closure (Work Packages A/B/C/D/D.1). This document is the single place
Honor 200 evidence gets recorded — per-scenario, with the candidate build
identity that produced it.

**No scenario below is marked PASS in this document until it has actually
been run on the real device.** An empty "Actual result"/"PASS/FAIL" column
means "not yet executed", never an implicit pass.

---

## 0. Candidate build identity

Every row of evidence below MUST cite the candidate this table describes —
never test against a floating "whatever is currently installed" build.

| Field | Value |
|---|---|
| Candidate SHA | `3e2b7e07deb920744451bba3e671857c580fa8cf` |
| CI run | run #452, https://github.com/umikasaiii/Jarvis-/actions/runs/35477928954 (all 17 steps green: core tests, assemble debug APK, Android unit tests, lint, compute SHA-256, upload, publish) |
| APK SHA-256 | `3e9d92c038b96602cc19c7f37993c3f6d4caf9fbd4e2c38cefd942296460be39` (from `app-debug.apk.sha256` in the `latest-debug` GitHub Release, cross-checked against the release asset's own digest) |
| versionName | `0.1.0` (static — this project does not derive `versionName`/`versionCode` per commit; see `app/build.gradle.kts`) |
| versionCode | `1` (static, same as above) |
| `BuildConfig.BUILD_ID` | first 7 chars of the commit SHA in CI (`local` outside CI) — visible in Diagnostics › "Ultima risposta chat" card, and embedded in every `ForecastDecisionReceiptEntity.appBuildId` written by that build |
| Database schema versions | `JarvisDatabase` (main): see `app/src/main/java/com/simone/jarvismobile/background/JarvisDatabase.kt` `@Database(version=...)`; `WeatherDecisionDatabase` (receipts, separate file `weather_decisions.db`): version 1 |
| WeatherAlertPolicy version | v2 (`WeatherAlertPolicyV2.POLICY_VERSION = 2`) — v1 remains in the codebase for replay/migration only, never called from production |
| Threshold config version | 1 (`WeatherAlertThresholdsV2().configVersion`) — CANDIDATE, not meteorologically validated |

Download the exact candidate APK from
`https://github.com/umikasaiii/Jarvis-/releases/download/latest-debug/app-debug.apk`
**only** once the CI run cited above has published it for the SHA in this
table — the `latest-debug` release is overwritten by every green push, so
re-check the SHA in the release notes ("Commit `<sha>`") matches this
table before trusting a download.

---

## 1. Preconditions checklist

Before the first morning test:

- [ ] candidate APK installed (SHA-256 matches §0)
- [ ] app opened at least once after install
- [ ] microphone/notification permission prompts resolved
- [ ] notifications globally enabled for JARVIS (Android Settings › Apps › JARVIS › Notifications)
- [ ] the "Promemoria" notification channel is enabled (Morning/Evening Digest and the weather alert are posted on this channel, `CHANNEL_REMINDERS`, `IMPORTANCE_HIGH` — not the lower-priority "Suggerimenti" channel)
- [ ] Impostazioni › «Automazioni in background» — record ON/OFF (do not silently change it to make a scenario easier; some scenarios below explicitly require it OFF)
- [ ] Impostazioni › Proattività › «Orario briefing» — record the configured hour:minute
- [ ] NEXT_ALARM offset — record the configured value (default +5 min, `SettingsRepository.morningNextAlarmOffsetMinutes`)
- [ ] exact-alarm capability — on Android 12+, confirm JARVIS has "Alarms & reminders" permission (Android Settings › Apps › JARVIS › Alarms & reminders); record granted/denied
- [ ] battery optimization / background restriction status for JARVIS — record AS-IS (Android Settings › Apps › JARVIS › Battery); do not disable battery optimization unless a specific failed scenario requires it as the fix
- [ ] Diagnostica screen reachable (Impostazioni › in fondo, o percorso equivalente nell'app)
- [ ] device local date/time/timezone recorded at test start
- [ ] confirm no same-day Morning/Evening occurrence already exists from a previous (older) build — check Diagnostica › "Briefing mattutino — ricevute di consegna (debug)" for today's logical date; if a stale receipt exists from an old build's testing, note it explicitly rather than clearing app data (clearing app data destroys the very persistence evidence Work Package A/B were built to produce — prefer waiting for the next logical day instead)

---

## 2. Primary one-morning acceptance scenario (§7 of the Work Package E task)

Target setup:
- Impostazioni › Proattività › «Orario briefing»: **08:50**
- A real phone alarm at **08:30**
- NEXT_ALARM offset: **+5 minutes** (default)
- «Automazioni in background»: **ON**

| Time | Expected | Diagnostics to check | Actual | PASS/FAIL/BLOCKED |
|---|---|---|---|---|
| ~07:40 (first real unlock after the earliest eligible hour) | `FIRST_UNLOCK` observed → claims `MORNING_DIGEST:<today>` → exactly ONE Morning Briefing notification | Diagnostica › "Diagnostica trigger briefing mattutino (debug)" › FIRST_UNLOCK section; "Briefing mattutino — ricevute di consegna (debug)" shows one `Deliver`/`Posted` row for today | | |
| ~08:35 (NEXT_ALARM-derived trigger, 08:30+5) | Same `MORNING_DIGEST:<today>` → `AlreadyOwned(DELIVERED)` → **ZERO** second notification | same ricevute card — a row with claimOutcome `AlreadyOwned` and no `Posted` | | |
| ~08:50 (CONFIGURED_TIME trigger) | Same occurrence → **ZERO** second notification | same | | |
| Later periodic fallback tick | **ZERO** second notification | same | | |
| +10min / +60min post-briefing refresh | DATA ONLY — **ZERO** notification repost, **ZERO** repeated speech | confirm no new notification appears in the shade and the existing one's timestamp/content is unchanged | | |

Wait at least past the +60min mark before declaring this scenario PASS.

---

## 3. Morning content acceptance (§8)

On the SAME real delivery captured in §2, verify the notification text itself:

- [ ] starts with the deterministic "Buongiorno" (with a weather emoji prefix ONLY if today's weather status is grounded — `SUCCESS_DATA`, never a guessed emoji on `STALE`/`SOURCE_FAILURE`/`DATA_UNAVAILABLE`)
- [ ] lists **today's** appointments only — no tomorrow entry appears under it
- [ ] a genuinely empty agenda says "Nessun impegno importante oggi" ONLY if Diagnostica confirms the agenda query status was `SUCCESS_EMPTY` (not `SOURCE_FAILURE`/`DATA_UNAVAILABLE`, which must say "Agenda non verificabile al momento." instead)
- [ ] starred/undated priorities appear under their own "Priorità aperte:" grouping, never folded into today's list
- [ ] dismissing or reopening the notification does NOT recreate/repost it

Capture: a screenshot (kept **user-side**, never committed to this repo if it contains personal agenda content), the exact timestamp, and the `receiptId`/diagnostic values from Diagnostica — not the raw notification body — in the evidence table in §9 below.

---

## 4. Alternate morning source acceptance (§9)

One successful FIRST_UNLOCK morning (§2) does not prove the other three
sources. Each of these needs its OWN test day/setup — never force-stop the
app to simulate a missing observer (force-stop genuinely prevents Android
from running JARVIS's receivers at all; that is a real platform limitation,
not a product bug — see §7 in this document).

### Scenario A — NEXT_ALARM first

Setup: no qualifying `FIRST_UNLOCK` before the expected NEXT_ALARM-derived
trigger time (e.g. the phone is already unlocked/in use before the earliest
eligible morning hour, or the user genuinely does not unlock until after
the alarm fires).

| Expected | Actual | PASS/FAIL/BLOCKED |
|---|---|---|
| NEXT_ALARM wins exactly once | | |

### Scenario B — CONFIGURED_TIME fallback

Setup: no qualifying FIRST_UNLOCK and no usable NEXT_ALARM (no phone alarm
set, or `AlarmManager.getNextAlarmClock()` returns null on this device —
record which).

| Expected | Actual | PASS/FAIL/BLOCKED |
|---|---|---|
| CONFIGURED_TIME wins exactly once | | |

### Scenario C — periodic recovery

Setup: a supported situation where the exact/configured delivery is missed
(e.g. the exact-alarm permission is genuinely denied, so `ExactAlarms`
degrades to inexact — record `ScheduleOutcome` from Diagnostica) but the
occurrence is legitimately still open after the configured due time.

| Expected | Actual | PASS/FAIL/BLOCKED |
|---|---|---|
| Periodic recovery delivers exactly once, never before the configured due time, never duplicating a prior dispatch | | |

---

## 5. NEXT_ALARM rollover device test (§10)

Setup: phone alarm at 08:30, offset +5min.

At/after 08:30, Android's Clock may report the alarm as null, tomorrow's
alarm, or a different alarm — record which on this specific Honor 200/
MagicOS build (do not assume it matches stock AOSP behavior).

| Check | Expected | Actual | PASS/FAIL |
|---|---|---|---|
| The already-matured 08:35 occurrence | survives regardless of what NEXT_ALARM now reports | | |
| At 08:35, if Morning was not already delivered by another source | the NEXT_ALARM opportunity remains valid | | |
| Diagnostica shows | source alarm identity/time, maturity, the newly observed future source, and the preserved current logical occurrence | | |

---

## 6. NEXT_ALARM change-before-maturity device test (§11)

Two sub-cases, separate test runs:

**Before maturity** — set a phone alarm for time T with the configured
offset, then change or cancel that alarm BEFORE T arrives.
Expected: the pending plan reconciles per Work Package B's rules (record
the actual `ProactiveScheduler`/`SourceAlarmReconciler` diagnostic
evidence, if surfaced, or the observed notification timing).

**After maturity** — after T has already matured (the alarm-derived
occurrence already exists), change/snooze/set a new future alarm.
Expected: the already-matured occurrence is not pushed indefinitely into
the future.

Capture actual Honor Clock behavior explicitly — do not assume it matches
AOSP.

---

## 7. CONFIGURED_TIME device test (§12)

Verifies the specific 08:50-selected/08:48-delivered anomaly from an
earlier micro-patch (§30.8 history in `docs/JARVIS_MASTER_ARCHITECTURE.md`)
stays fixed.

1. Set an explicit configured time in Impostazioni › Proattività ›
   «Orario briefing», save.
2. Confirm the saved hour/minute is what was selected (no silent
   off-by-a-few-minutes drift).
3. Change the configured time AGAIN before the original time fires.

| Check | Expected | Actual | PASS/FAIL |
|---|---|---|---|
| Delivery time vs. selected time | same intended local minute, unless an explicitly disclosed inexact-alarm degradation applies (record which) | | |
| After changing the time again | the old intent is stale/no-op, the new plan is authoritative, no duplicate delivery | | |

No arbitrary +/- minute "compensation" is an acceptable fix for a
mismatch found here — see §25/§26 of the Work Package E task for the
required failure-handling procedure if this scenario fails.

---

## 8. Process restart / reboot acceptance (§13)

Test separately, on different days if needed:

**A. Normal process recreation** (swipe JARVIS from recents, or let Android
kill it in the background, then reopen).

**B. Device reboot.**

For both:

| Check | Expected | Actual | PASS/FAIL |
|---|---|---|---|
| Schedule reconstruction | next occurrence is re-armed (`BootReceiver`/`JarvisApplication.onCreate()`'s `ProactiveScheduler.scheduleAll()`) | | |
| Occurrence persistence | no duplicate Morning for the same logical day, no prior day relabelled as today | | |
| Observers re-register | through the normal app lifecycle, no manual re-grant needed | | |
| Weather receipt DB | remains coherent (Diagnostica › "Mostra receipt completo" still returns the last real receipt) | | |
| Retention | does not delete evidence for an in-flight decision (only cutoff-by-age/excess-row pruning, never state-based — see `WeatherReceiptRetentionPolicy`) | | |

---

## 9. Timezone / DST edge acceptance (§14)

Automated `:core` tests remain the primary coverage here
(`MorningWindowPolicyTest`, `SourceAlarmReconcilerTest`, `ExactAlarms`
tests). On-device, only if it can be done SAFELY without invalidating a
scenario already in progress:

| Check | Expected | Actual | PASS/FAIL |
|---|---|---|---|
| Manual timezone/time change (off-hours, not during a live morning test) | reconciliation evidence appears, a stale plan revision is rejected, no duplicate notification | | |

Do not manipulate time/timezone during a production-use morning if doing
so would invalidate the primary §2 scenario.

---

## 10. Evening digest device acceptance (§15)

Setup real agenda entries purposely, before the 19:00-21:00 window:
- entry A: today
- entry B: tomorrow
- entry C: future, starred
- entry D: undated, starred (priority)

| Check | Expected | Actual | PASS/FAIL |
|---|---|---|---|
| Exactly one Evening Digest delivered | deterministic "Buonasera 🌙" opener | | |
| Entry B (tomorrow) | appears under "Domani:" | | |
| Entry A (today, if still open) | appears under its OWN "Da oggi restano:" heading, never merged into "Domani:" | | |
| Entry C (future starred) | appears under "Priorità aperte:", not under "Domani:" | | |
| Entry D (undated starred) | appears under "Priorità aperte:" | | |
| A verified-empty tomorrow | says "Domani non risultano impegni in agenda." | | |
| A source failure for tomorrow (e.g. airplane mode during the evening window) | says "Agenda di domani non verificabile al momento." — never claims empty | | |
| Tomorrow's weather emoji/fact, if shown | sits next to "Meteo domani: ...", separate from the 🌙 opener | | |
| Dismissing/reopening | does not cause a second Evening dispatch | | |

---

## 11. Weather production device acceptance (§16)

Weather policy remains **CANDIDATE** — this section verifies the SOFTWARE
receipt/dispatch pipeline, not meteorological quality (see §13 below).

For a real production evaluation during the 19:00-21:00 window:

1. Diagnostica › "Meteo — avviso pioggia/temporali" › "Controlla adesso".
2. Diagnostica › "Mostra receipt completo".
3. Confirm the receipt reconstructs, at minimum:
   target date, `fetchedAt`, forecast age, provider, forecast timezone,
   raw WMO code, probability max, rain/showers/snow amounts, precipitation
   hours, aligned hourly evidence count (if used), location-revision
   match, policy version, threshold config version, decision + reason,
   occurrence key, trigger source, notification namespace, outcome
   events.
4. Confirm the receipt shows NO coordinates/address/place name — only the
   already-opaque `locationRevision` tag.

| Check | Expected | Actual | PASS/FAIL |
|---|---|---|---|
| Receipt exists for every real evaluation | | | |
| All §16 fields reconstructable | | | |
| No coordinates/address/place name present | | | |

---

## 12. Weather alert device scenarios — debug plumbing only (§17)

**Debug results here do NOT count as meteorological validation** — they
prove the v2 policy/receipt/dispatch plumbing behaves as coded, nothing
about real forecast accuracy.

Using Diagnostica › "Meteo — avviso pioggia/temporali" debug buttons
(`BuildConfig.DEBUG` builds only):

| Scenario | Button | Expected | Actual | PASS/FAIL |
|---|---|---|---|---|
| Below-threshold | "Sereno" | "Nessun avviso da questo scenario" — no production occurrence/notification | | |
| Qualifying rain | "Pioggia" | "[SIMULAZIONE] Domani è prevista pioggia." in the debug namespace (`jarvis.proactive.debug`), never `jarvis.proactive.weather` | | |
| Storm without aligned hourly confidence | (not directly a debug button — verified by `:core`'s `WeatherAlertPolicyV2Test`/`WeatherAlertReplayTest`, `classify_stormWithoutAlignedEvidence_isUnknown`) | no storm claim | N/A (core-tested) | N/A |
| Qualified storm | "Temporale" | "[SIMULAZIONE] Domani sono previsti temporali." | | |
| Repeated tap, same scenario | any | "già stato simulato e consegnato oggi" — no second notification | | |

---

## 13. Real weather shadow mode (§18-§23)

This is the **meteorological quality** track, deliberately separate from
software acceptance (§21/§28 of the task spec — do not conflate the two).

### 13.1 Data integrity gate (§19) — STATUS: **READY, with a stated limitation**

The replay harness (`core/weather/replay/WeatherAlertReplay.kt`) is real,
deterministic, and reuses the exact production policy functions — built
and tested in Work Package D.1. Work Package E adds
`ForecastDecisionReceiptReplayBridge` (`app/weather/receipt/`), which
converts a REAL, already-written `ForecastDecisionReceiptEntity` into a
`ReplayFixture` — i.e. every real evening evaluation this app performs is
now, by construction, a genuine "as-of" forecast snapshot usable for
prospective shadow scoring, never a historical forecast reconstructed
from today's current data (§19's core rule).

**Stated limitation**: the bridge refuses to convert (`null`) a receipt
whose `thresholdConfigVersion` is not the one known default configuration
production has ever actually evaluated against — this cannot happen today
(production never passes a custom threshold), but if it ever does, the
bridge fails closed rather than guessing threshold numbers the receipt
never stored. A receipt whose ORIGINAL decision was itself a
`LOCATION_MISMATCH` is replayed as if the location matched (the receipt
schema does not separately store the two revisions that were compared,
only the boolean match) — not evidence for validating that specific
mismatch decision.

### 13.2 Procedure

For every real evening evaluation (§11 above already captures the
receipt):

1. **Before** the target day occurs: the receipt is already immutable —
   nothing further to do at capture time (`ForecastDecisionReceiptRepository
   .record()` already ran).
2. **After** the target day has passed: attach an observed-outcome label
   using the versioned contract in `core/weather/replay
   /WeatherObservedOutcome.kt` (`RAIN_OBSERVED` /
   `NO_MEANINGFUL_RAIN_OBSERVED` / `STORM_OBSERVED` / `SNOW_MIXED` /
   `OBSERVATION_UNKNOWN`) from an authoritative source for that day/place
   — never from tomorrow's forecast re-fetch, never guessed.
3. Convert the receipt via `ForecastDecisionReceiptReplayBridge
   .toReplayFixture(observedOutcome)`, replay it through
   `WeatherAlertReplay.evaluate()`, and accumulate results.
4. Score with `WeatherAlertReplay.aggregate()` — TP/FP/TN/FN + precision/
   recall/FPR/FNR, plus the §21 breakdown (`unknownRate`/
   `invalidDataRate`/`staleDataRate`/`locationMismatchRate`/
   `noSourceRate`) — never a single vague "accuracy" number.

### 13.3 Threshold tuning rule (§22)

The current v2 candidate thresholds are **FROZEN** during this initial
qualification round:

```
P >= 70 AND (L >= 1.0mm OR (L >= 0.5mm AND H >= 2h))
```

Do not change these after seeing one false positive/negative. If
qualification later shows a systematic problem, that becomes a SEPARATE
threshold-analysis report comparing candidate configurations on the SAME
frozen dataset, with development/holdout separation — never tuned and
"validated" on the same sample.

### 13.4 Sample-size honesty (§23)

| Field | Value |
|---|---|
| Days with a real, labelled evening evaluation | *(fill in as evidence accumulates)* |
| Status | **PRELIMINARY SHADOW EVIDENCE** — never "METEOROLOGICALLY VERIFIED" until the project-defined qualification sample size and acceptance thresholds are actually satisfied |

This status may legitimately remain open indefinitely without blocking
the SOFTWARE reliability closure below, which is reported separately.

---

## 14. Acceptable platform limitations (§26)

Documented rather than worked around:

| Limitation | Observable status | Supported fallback | User impact | Acceptance still possible? |
|---|---|---|---|---|
| Force-stopping the app prevents ALL background observation (FIRST_UNLOCK, exact alarms until re-launched, WorkManager) | Android platform behavior, not a JARVIS bug | none — this is Android's own contract for force-stopped apps | Morning/Evening/weather alerts pause until the app is opened again | Yes — never test force-stop and call a missed delivery a defect |
| `AlarmManager.getNextAlarmClock()` may return null even with a real alarm set (OEM-specific) | check via Diagnostica's NEXT_ALARM section | CONFIGURED_TIME/PERIODIC_FALLBACK cover the day | delivery may shift to a later source | Yes |
| Exact-alarm permission may be absent/revoked (Android 12+) | `ExactAlarms.ScheduleOutcome` in Diagnostica | falls back to inexact scheduling, never a `SecurityException` converted to success (Work Package B §14 fix) | delivery time may drift by OS-controlled minutes | Yes, with the drift disclosed |
| OEM (Honor/MagicOS) may defer inexact alarms further than AOSP | record actual observed delay | PERIODIC_FALLBACK/CONFIGURED_TIME eventually recover | later-than-expected delivery, never a duplicate | Yes |
| WorkManager timing is never exact | applies to the +10/+60 post-briefing refresh only | data-only, no user-visible timing requirement | none (refresh, not delivery) | Yes |

No Accessibility-service or polling workaround is acceptable merely to
paper over any of these — see §26 of the Work Package E task.

---

## 15. Software reliability acceptance summary (§27)

Filled in only once every applicable device gate above shows a real,
recorded PASS:

```
Morning:
  exactly one automatic dispatch/day             [ ] PASS  [ ] FAIL  [ ] BLOCKED
  correct source reconciliation (§2/§4/§5/§6/§7)  [ ] PASS  [ ] FAIL  [ ] BLOCKED
  no +10/+60 repost                               [ ] PASS  [ ] FAIL  [ ] BLOCKED
  correct date/content (§3)                       [ ] PASS  [ ] FAIL  [ ] BLOCKED
  restart/reboot recovery (§8)                     [ ] PASS  [ ] FAIL  [ ] BLOCKED

Evening:
  exactly one                                     [ ] PASS  [ ] FAIL  [ ] BLOCKED
  correct tomorrow facts (§10)                    [ ] PASS  [ ] FAIL  [ ] BLOCKED
  deterministic presentation                      [ ] PASS  [ ] FAIL  [ ] BLOCKED

Weather:
  v2 production path (§11)                        [ ] PASS  [ ] FAIL  [ ] BLOCKED
  receipt-before-dispatch                          [ ] PASS  [ ] FAIL  [ ] BLOCKED
  no duplicate target-date alert                   [ ] PASS  [ ] FAIL  [ ] BLOCKED
  no snow -> rain corruption (§17)                 [ ] PASS  [ ] FAIL  [ ] BLOCKED
  date/location/freshness correctness              [ ] PASS  [ ] FAIL  [ ] BLOCKED

Diagnostics:
  sufficient to reconstruct every tested decision,
  without private-body logging                     [ ] PASS  [ ] FAIL  [ ] BLOCKED
```

`PROACTIVITY SOFTWARE DEVICE VERIFIED` is declared ✅ only when every line
above is PASS. `METEOROLOGICAL QUALITY VERIFIED` is a SEPARATE, later
declaration gated by §13 above — never implied by this table alone.

---

## 16. Failure handling procedure (§25)

If ANY device scenario above FAILS:

1. Do **not** mark Work Package E complete.
2. Identify the exact failing invariant (cite the section above).
3. Correlate against persistent diagnostics (`TriggerEvidenceStore`,
   `ProactiveOccurrenceStore.lastClaim`, the receipt/replay evidence).
4. Trace construction → DI → call site → consumer → side effect.
5. Classify: code defect / OEM-Android limitation / permission-
   configuration issue / unsupported source / observability gap / product
   requirement mismatch.
6. Propose the MINIMUM systemic fix — no random delay, no debounce-as-
   correctness, no duplicate scheduler, no blind retry loop, no keyword
   fix, no notification-ID hack, no arbitrary threshold tuning (§2/§26 of
   the task).
7. Implement only that fix, add a regression test, confirm CI green.
8. Produce a NEW candidate SHA (update §0 of this document).
9. Repeat only the affected scenario(s) plus a smoke regression of §2.

Never patch based only on notification text — always correlate against
the diagnostics evidence first.
