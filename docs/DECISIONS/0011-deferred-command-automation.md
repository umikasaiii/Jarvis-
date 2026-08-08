# 0011 — One-shot deferred commands are automations, not immediate actions

Status: accepted · 2026-08-08

## Context

The automation grammar understood only two kinds of trigger: recurring
("ogni giorno alle 8…") and conditional ("quando la batteria…"). A phrase like
"alle 11.45 accendi la torcia" matched neither, so it fell straight through to
the command matcher and turned the torch on **immediately** — the exact opposite
of what the user meant. There was no way to say "do this device action once, at
this future minute".

## Decision

- `:core` gains two record types behind the existing sealed interfaces:
  `Trigger.Once(at: LocalDateTime)` — a single future moment — and
  `Action.Tool(command: String)` — an allowlisted device command stored as text.
  Both round-trip through `Automazioni.md`
  (`- [x] una volta 2026-08-08 11:45 — comando: accendi la torcia {#id}`).
- The decision "is this phrase a deferred command?" lives in `app/`
  (`DeferredCommand`), not `:core`, because only the app layer can run the
  remainder back through the real `CommandMatcher`. A phrase becomes a one-shot
  rule only when it names an absolute time **and** its remainder maps to a
  permitted, deferrable tool. An appointment ("domani alle 15 dentista") maps to
  no tool, so it still falls through to the agenda untouched.
- Confinement to the allowlist is enforced twice. At save time the command must
  match a tool in `DeferredCommand.ELIGIBLE_TOOLS`; at fire time the stored text
  is matched **again** through `CommandMatcher` and checked against the same set
  before `ToolRunner` executes it. The rule holds only words — there is no path
  from a stored automation to an arbitrary tool call (ADR 0008, SECURITY §15).
- `ELIGIBLE_TOOLS` is a strict subset of the command allowlist: only tools that
  take effect from a background alarm with no foreground window (`flashlight`,
  `media_control`). Anything that would have to launch an Activity is excluded,
  because Android blocks background Activity starts and a rule must never promise
  what it cannot deliver while the phone is asleep.
- A one-shot rule is scheduled with the same exact-alarm path as reminders and
  recurring time rules. It fires once: once its `lastFired` mark is set (or its
  moment has passed) it is never rescheduled. Every firing posts a visible
  notification with the outcome — an automation that silently touched the device
  would break the no-hidden-actions rule.

## Consequences

"Alle 11.45 accendi la torcia" now becomes a rule the user can see, disable and
delete, instead of a torch that turns on the instant they finish speaking. The
capability is deliberately narrow (headless device actions only) and can grow by
adding a tool name to `ELIGIBLE_TOOLS` once its background behaviour is verified
on-device. Absolute-time phrasing is required; relative "tra 10 minuti" is still
a countdown, not a deferred command.
