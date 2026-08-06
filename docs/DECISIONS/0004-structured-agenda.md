# 0004 — Reminders are structured agenda entries, and time maths lives in code

Status: accepted · 2026-08-06

## Context

Two failures observed on the HONOR 200:

1. Asked "quanto manca alle 16 di oggi pomeriggio" at 08:03, the local model
   answered "2 ore e 45 minuti". The correct answer is 7 ore e 57 minuti. A 3–4B
   model does not do reliable clock arithmetic, and a confidently wrong number is
   worse than a refusal.
2. "Ricordami di X domani" was stored as a free-text note whose body contained the
   word "domani". Nothing in the app could tell *when* X was due, so the reminder
   could never be surfaced at the right moment, sorted, or turned into a real
   notification.

## Decision

**Dates and times are data, not prose, and the arithmetic over them is code.**

- `:core` gains an `agenda` package:
  - `AgendaEntry(date, time?, text, done)` — one dated item. A time means an
    appointment/event; no time means an activity/task. It is serialised as
    an Obsidian task line `- [ ] 2026-08-07 15:00 — revisione auto`.
  - `ItalianDateTimeParser` — turns Italian date/time expressions into
    `LocalDate`/`LocalTime`, and reports whether the user actually *named* a day
    (`dateExplicit`) or whether the day was merely inferred from a bare clock time.
  - `Agenda` — parse/render the file, sort, filter by day and by part of the day,
    and say a date or a duration in Italian.
  All of it is pure Kotlin and unit-tested, so the answers are verifiable in CI
  rather than trusted.

- The app gains `AgendaRepository` and four tools — `add_reminder`,
  `list_agenda`, `complete_agenda`, `time_until` — registered in the same `ToolRegistry` as
  everything else, so the model gains understanding but never new privileges.

## Consequences

- **Storage.** The agenda lives in `JARVIS/Agenda.md` inside the user's vault, in
  Obsidian's own task syntax, so it stays human-readable and editable outside the
  app (the vault remains the source of truth). With no vault connected the same
  Markdown is written to app-private storage and folded into the vault the first
  time one is picked — a reminder is never silently dropped just because the user
  has not connected Obsidian yet.
- **Personal calendar first.** “Aggiungi al calendario” writes to this local
  source of truth. Google Calendar/the Android calendar is not required and is
  reached only by an explicit export request, which opens an editable system
  draft. JARVIS never silently duplicates an item in two calendars.
- **No address book.** Calendar and task management do not require contacts;
  contact access is outside the registered capabilities.
- **Asking beats guessing.** A reminder with no day cannot be filed, so JARVIS
  asks "Quando?" and parses the answer the same way. "Nessuna data" is accepted
  and falls back to a plain note.
- **Arguments never come from the model.** As with the calculator, the intent may
  come from the AI but every date, time and digit is re-extracted from the user's
  own words.
- **Unicode word boundaries.** Java's `\b` is defined over `[A-Za-z0-9_]`, so
  `\bvenerdì\b` never matches. Italian day names need lookaround on `\p{L}`.
- This is the substrate the next step needs: real scheduled notifications have
  something concrete to fire on.
