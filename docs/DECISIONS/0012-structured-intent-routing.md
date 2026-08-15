# 0012 — Structured intent routing: planner CRUD resolves a target before any tool runs

Status: accepted · 2026-08-15

## Context

The conversation pipeline had a fast path (`CommandMatcher`, deterministic
regexes) and an AI path (`LlmIntentClassifier`), and nothing in between. That
was enough for *creating* things but not for changing them: the planner had no
delete, move or rename at all, even though `AgendaRepository` already exposed
`update`, `removeById` and `markDone`. So "sposta il dentista a venerdì" fell
through to the model, which either answered conversationally or — worse — could
be nudged into a tool call built from words it had partly invented.

Three specific hazards made a regex-only extension unattractive:

- **Target vs destination.** "Porta l'appuntamento *di domani* *a lunedì*" names
  two days: one identifies the entry, the other is where it goes. Reading them
  backwards silently rewrites the wrong day.
- **Ambiguity.** "Cancella la riunione" may match several entries. `markDone`
  already fails closed on ambiguity, but failing closed with no explanation is a
  dead end for the user.
- **Collisions.** "Modifica X in Y" is shaped identically for a note edit and an
  appointment rename, and a bare "cancella" during a confirmation means *abort*,
  not *delete something*.

## Decision

- Understanding becomes a **value**, not a side effect. `:core/intent` holds
  `ResolvedIntent` (domain, action, score, target, changes, source), declarative
  alias tables (`IntentAliases`), normalisation (`TextNormalizer`) and the
  planner parser (`AgendaCommandParser`). All of it is pure and unit-tested; the
  parser never touches a repository and never resolves a target itself.
- The parser locates the **destination clause first** and cuts it out; only what
  remains may describe the target. A relative shift ("di due ore") is matched
  before anything else, because it can only ever be a change.
- Resolution lives in `app/` (`AgendaIntentRouter`), which is the only component
  that can see the real planner. It has exactly three outcomes: one match →
  build a call, several → ask which, none → say so.
- **The CRUD tools take an `id`, never words.** `delete_agenda`, `move_agenda`
  and `rename_agenda` cannot search. By the time a call exists the target is
  already unambiguous, so a vague phrase cannot reach the wrong entry — it either
  never becomes a call, or it becomes a question.
- `delete_agenda` is `CONFIRMING_WRITE` and names the entry in its prompt;
  move/rename are `LOW_RISK_WRITE` because they are reversible, but always
  report the resulting day and hour so a wrong move is visible immediately.
- A destructive intent whose target was never named cannot self-approve
  (`ResolvedIntent.mayAct()`), regardless of its score.
- The pending "which one?" question is its own state (`pendingPick`), separate
  from the missing-argument state (`pendingSlot`), because the answer is a choice
  among known candidates rather than a missing field — and because a half-made
  deletion must be resolved before anything else is considered.

## Consequences

- The pipeline is now: pending confirmation → pending pick → pending slot →
  fast path → **structured path** → AI path → chat. The model is not consulted
  for planner edits at all, so they are instant and offline.
- Every input surface (voice, chat, watch) keeps entering at
  `SessionCoordinator`; no second router was created.
- Quality is measured, not asserted anecdotally: `IntentMetricsTest` scores the
  parser over a labelled corpus (37 commands, 20 near-miss negatives) and
  reports precision/recall plus the confusion between actions. False positives
  and delete/move confusion are pinned at exactly zero rather than at a
  threshold, since those are the failures that cost user data.
- Cost: two components now understand planner phrasing (`CommandMatcher` for
  creation, `AgendaCommandParser` for changes). They are kept apart by rule —
  the parser refuses `CREATE`, and `CommandMatcher` defers any generic
  "modifica X in Y" that names a planner noun.

## Known limits

- A generic "modifica X in Y" that names **no** planner noun stays a note edit,
  which is the pre-existing behaviour and is preserved deliberately.
- Multi-action sentences ("sposta il dentista e aggiungi una nota") are not
  split by this path; only the existing `SAFE_COMPOUND_TOOLS` handling applies.
- The AI fallback still returns a `Match`, not a `ResolvedIntent`; unifying it is
  future work.
- Verified by unit tests and CI only — not yet exercised on the HONOR 200.
