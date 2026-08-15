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

## Status and known gaps

Phases 1–4 are done and CI-verified. **The new engine is not connected to
anything yet** — nothing calls `AutomationExecutor.onTrigger()`, and the old
Markdown engine still runs the user's automations.

Still missing: the scheduler wiring that makes the engine live, geofencing and
the Places screen (needs `ACCESS_BACKGROUND_LOCATION`, verifiable only on a real
phone), Activity Recognition, parking, the QUANDO/SE/ALLORA Rule Builder,
diagnostics with dry-run, natural-language AutomationDraft, and the hand-over
from the old engine.
