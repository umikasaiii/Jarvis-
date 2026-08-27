# 0013 — Generic Context + Automation engine, built beside the old one

Status: in progress · 2026-08-15

## Context

Phase 6f gave JARVIS automations, but of a fixed shape: one trigger, one action,
no conditions, no priority, no cooldown, stored as a Markdown line. That covers
"ogni giorno alle 8, dimmi il meteo" and stops there. It cannot express "quando
arrivo a casa, **se** è sera **e non** sto guidando, metti la modalità casa".

Three things were also quietly wrong:

- `Trigger.ArrivedHome` existed in the model, but **no geofencing exists
  anywhere in the app** — a rule armed on it could never fire, while looking
  active in the UI.
- `jarvis.db` used `fallbackToDestructiveMigration()`. Correct for the document
  and navigation caches it held; catastrophic once it also holds rules the user
  typed and nothing can rebuild.
- Nothing distinguished "the sensor says no" from "the sensor cannot say".

## Decision

Build the generic engine **beside** the existing one rather than replacing it,
so the automations that work today keep working while the new path is assembled.
Hand-over is the last step, not the first.

### Shape

```
sources → ContextEngine → ContextState ─┐
                                        ├→ RuleGate → ConflictResolver → AutomationExecutor → handlers
rules (Room) ───────────────────────────┘
```

Everything that decides lives in `:core` and is pure: the condition tree, the
gate, the conflict resolver, the fusion. The app layer keeps only what genuinely
needs Android — sensors, timers, notifications, persistence. That split is why
the interesting logic is unit-tested rather than only compiled.

### The rules that shape the design

1. **An unknown fact is never true, and `NOT(unknown)` stays unknown.** If the
   phone cannot say where it is, "quando sono a casa" does not hold — and neither
   does its negation. The cost is a missed automation when a sensor is
   unavailable; the alternative is firing on a guess.
2. **Nothing may look armed if it cannot fire.** Trigger and action kinds whose
   plumbing does not exist are `implemented = false` and refused by validation. A
   test pins the available-actions list to the handlers actually bound in DI, so
   the two can only change together.
3. **A rule that cannot be decoded is quarantined, never partially loaded.**
   Dropping an unreadable condition would *widen* the rule: one meant for home
   would start running everywhere.
4. **The log records why something did *not* happen**, not only why it did — and
   holds no coordinates and no message bodies, which would otherwise become a
   tracking history living on in every backup.
5. **Migration refuses rather than approximates.** A legacy rule with no faithful
   equivalent is reported with a reason instead of being converted into something
   that fires at a different time.

### Persistence

Rules, places, executions and parking move to Room (owner's decision, over
keeping them in the vault). Tree-shaped parts are stored as versioned JSON via a
hand-written codec, so there is one place to migrate an older shape. A real
migration is registered ahead of the destructive fallback, and **every version
bump from 4 on must ship one**.

## Consequences

- Adding a trigger or action is a registry entry plus a handler; no `when` in the
  engine or the UI changes.
- The pure core is testable without a device: 488 tests green, covering AND/OR/NOT
  including unknowns, cooldown boundaries, duplicate suppression, conflict
  resolution, storage round-trips and geofence oscillation.
- Two design faults surfaced only because the logic was testable:
  signal decay made the dwell unsatisfiable (standing still would never confirm
  an arrival), and source weights sat exactly on the entry threshold.

## Audit, 26 August 2026

A pass over the engine looking for behaviour that misbehaves quietly rather than
failing loudly. Six defects were real and are fixed (CI-verified):

1. `executionPolicy` was honoured by the gate and ignored by the executor, so
   `QUEUE` behaved exactly like `SKIP_IF_RUNNING` — the gate said "fire", the
   executor said "no", and nothing in between could notice. `QUEUE` now
   serialises per rule through a per-rule `Mutex`.
2. **`REPLACE` was removed from `ExecutionPolicy`.** Cancelling an in-flight run
   requires the executor to own its `Job`, which it does not; leaving the option
   would give the user a setting that silently does something else. Unknown
   values decode to `SKIP_IF_RUNNING`, so re-adding it later costs no migration.
3. `CancellationException` was swallowed in the executor and three handlers, so
   a cancel became a "failed action" and the loop carried on with the remaining
   ones — "stop" did not stop. Now rethrown, as `LitertLmEngine` already did.
4. `armable()` pre-filtered candidates by cooldown and expiry *before* the gate,
   so those rules never reached the execution log: the history could not answer
   "perché non è scattata?" for the most common reason, and the gate's
   `SKIP_COOLDOWN`/`SKIP_EXPIRED` branches were unreachable. Replaced by
   `candidatesFor(triggerType)`, which leaves deciding to the gate.
5. `importLegacy` used a row count as its has-this-run guard, so deleting every
   imported rule resurrected them on the next pass. Now a persisted flag.
6. Introduced by the fix for (1) and caught on re-reading it: the cleanup in the
   `finally` went through a *suspending* `withLock`. Suspending in a `finally`
   of an already-cancelled coroutine throws at once, so the running flag would
   never clear and the rule would stay "running" for ever.

7. Five trigger kinds were offered (`implemented = true`) while nothing in the
   app ever builds a `TriggerEvent` of that type: both Bluetooth kinds (no
   source class exists), `CALENDAR_EVENT_APPROACHING` and `REMINDER_DUE`
   (`RuleSchedule.nextOccurrence()` understands only `TIME_AT` and
   `RECURRING_TIME`), and `TIME_WINDOW` — which is not merely unimplemented but
   a modelling mistake, since a window never fires; that is `Condition.TimeRange`
   and the builder already uses it. The action registry had a test pinning it to
   its handlers; the trigger registry had none, so it drifted. It has one now,
   naming the source for each offered kind. The test immediately caught that
   `LegacyRuleConverter` translated the legacy Bluetooth trigger, meaning the
   one-time import would have produced a rule that validated as active and could
   never run.

Checked and found clean: the migration chain (3→7, every step registered, and
every migration's SQL matches its `@Entity` column for column — a mismatch would
crash on first open after an upgrade); secrets (Keystore-backed
`EncryptedSharedPreferences`, no plaintext, none logged); the manifest (no
accidental exports, both `specialUse` services declare their subtype); and the
absence of empty catches or suspending cleanup anywhere else.

## Status and known gaps

Phases 1–4 are done and CI-verified. **Phases 5 and 6 are now done and
CI-verified**, so the engine is live for the first time:

- **Phase 5 — scheduler.** `RuleSchedule` (pure, tested) computes the next
  occurrence of `TIME_AT` / `RECURRING_TIME`; `RuleScheduler` books it as an
  exact alarm (new `KIND_RULE`, separate from the old engine's), and the
  `AlarmReceiver` builds a `TriggerEvent` + `EvaluationContext` and calls
  `AutomationExecutor.onTrigger()` — the first thing ever to drive the engine.
  Re-armed on cold start and boot.
- **Phase 6 — geofencing.** `PLACE_ENTER` / `PLACE_EXIT` are now `implemented`.
  Geofencing is GMS-free (`LocationManager.addProximityAlert`, matching the
  project's no-Play-Services stance); `PlaceGeofenceSource` arms one fence per
  saved place, gated on `ACCESS_BACKGROUND_LOCATION`; `PlaceProximityReceiver`
  feeds the `ContextEngine` and delivers the event. A pure `TriggerMatching`
  makes a place rule fire only for *its* place (the gate and executor share it),
  closing the type-only-matching gap. `PLACE_DWELL` stays off (no dwell tick).
- **Testability.** A `RulesScreen` + `RulesViewModel` (Automazioni › "Regole
  avanzate") let the user save a place and build a clock/place rule, so the
  above is exercisable rather than only compiled. It offers only trigger kinds
  with a live source, so nothing armed there can fail to fire.

Also done and CI-verified since:

- **Charger triggers.** `DEVICE_CHARGING` / `DEVICE_UNPLUGGED` are delivered by
  a manifest `EnginePowerReceiver` (protected broadcast, GMS-free, fires even to
  a stopped app) and offered in the builder — testable offline by plugging in.
- **Phase 7 — activity recognition: deliberately NOT built.** There is no AOSP
  activity-recognition API; it lives in Play Services, which this project
  avoids. Rather than pull in GMS or fake it, `ACTIVITY_*` stays `UNSUPPORTED`
  (the CapabilityManager already reports this honestly).
- **Phase 8 — parking, GMS-free.** `SAVE_PARKING_LOCATION` is implemented (a real
  handler reading the last-known `LocationManager` fix) and there is a manual
  "salva dove sono parcheggiato" button + a "distanza da qui" (reusing
  `Geo.distanceMeters`). The auto-trigger is intended to be "driving mode ended
  AND not at a saved place", which waits on a driving mode the owner will add
  after the 10 phases.
- **Phase 9 — conditions + diagnostics.** The builder now sets a "SE": weekdays,
  "solo in carica", a time window, combined with AND into the tested `Condition`
  tree. A "Prova adesso" dry-runs a rule through the real gate (no side effect)
  and a "Diagnostica" card shows recent firings and, crucially, non-firings with
  the reason — reusing the already-written execution log. What remains of phase 9
  is more condition kinds and a richer builder (nested OR/NOT); the core logic is
  already tested.

The old Markdown engine still runs the user's existing automations; hand-over is
still deliberately last.

Still missing: full activity recognition (only if GMS is ever accepted); a
Bluetooth-connect/disconnect source and the driving-mode-exit trigger that would
auto-save parking; nested condition editing; natural-language `AutomationDraft`;
and the hand-over from the old engine (phase 10). Real geofence and charger
firing are verifiable only on a phone.
