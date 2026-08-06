# 0010 — JARVIS owns an offline personal calendar

Status: accepted · 2026-08-06

## Context

The user wants one structured place for appointments and activities without a
Google Calendar dependency, and does not need address-book integration. The app
already had `Agenda.md`, reminders and a dashboard agenda tile, but the weekly
calendar still displayed demo events and generic calendar phrases opened an
external app.

## Decision

- `JARVIS/Agenda.md` is the source of truth for the personal calendar.
- An entry with an hour is an appointment; an entry without an hour is a dated
  task. Both retain stable IDs, completion state and zero or more alerts.
- “Aggiungi al calendario” writes locally. Only an explicit mention of Google
  Calendar, Android calendar, phone calendar or export opens the system's
  editable event draft.
- The seven-day dashboard reads real entries and visually distinguishes events
  from tasks. Demo events are removed.
- Tasks can be marked complete through `complete_agenda`, with the exact target
  shown for confirmation before the file is changed.
- The address book is not queried or exposed as a tool. Calls and SMS drafts, if
  used, still require an explicit phone number supplied by the user.

## Consequences

The calendar remains offline, inspectable in Obsidian and usable when no Google
account is configured. There is one authoritative copy, so no silent conflicts
or duplicate events are introduced. A future calendar screen can add month/day
views, recurrence and priorities on top of the same records without migrating
the current data.
