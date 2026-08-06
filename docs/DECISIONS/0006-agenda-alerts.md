# 0006 — Agenda alerts remain part of the Obsidian source of truth

## Decision

Each `AgendaEntry` has a stable ID and zero or more `ReminderAlert` rules. They
are stored at the end of the readable Obsidian task line as an HTML comment, for
example:

`- [ ] 2026-08-10 15:30 — dentista <!-- jarvis:id=…;alerts=MORNING_OF,ONE_DAY_BEFORE -->`

WorkManager is a rebuildable execution schedule, not the source of truth. Each
agenda reload reconciles future jobs with the Markdown rules, cancelling stale
jobs after an edit or deletion. “Morning” defaults to 08:00 and is configurable.

## Consequences

- alerts survive app exit and device reboot;
- multiple alerts and a custom date/time are supported;
- entries without rules visibly show “Avviso non impostato” on the dashboard;
- WorkManager reminders are reliable but not guaranteed to the exact minute;
  exact-alarm special access is intentionally not requested.
