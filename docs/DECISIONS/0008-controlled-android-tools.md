# ADR 0008 — Controlled Android tools

## Decision

Android actions are fixed, typed tools registered in `ToolsModule`; model output
may select a registered name, but every argument is extracted again from the
user's own text and validated.

Available surfaces include an allowlist of apps/settings, editable calendar and
SMS drafts, the system dialer, navigation, media search/control, explicitly
confirmed notification reads, and search limited to the selected Obsidian vault.

## Safety rules

- Calls use `ACTION_DIAL`, never `ACTION_CALL`; JARVIS cannot press Call.
- SMS uses `ACTION_SENDTO` with `smsto:`, never direct sending.
- Calendar events use an editable `ACTION_INSERT` draft; the calendar app owns Save.
- External communication drafts show the exact target/content and require confirmation.
- Notification text is available only after Android's Notification Access grant,
  requires confirmation each time, and is never stored or logged.
- App launch uses a fixed package/system allowlist. There is no arbitrary intent,
  package name, URL, shell, Accessibility service, `QUERY_ALL_PACKAGES`, or
  `MANAGE_EXTERNAL_STORAGE`.

## Limits

JARVIS deliberately does not read the full phone calendar or contacts database.
It opens the relevant system app/picker or asks for an explicit number. There is
no portable Android stopwatch creation intent; the controlled fallback opens the
Clock app. Weather/location collection remains outside this offline step.
