# 0011 — Location automations by on-device geofence

Status: accepted · 2026-08-15

## Context

The automation engine already fires on a wall-clock time (`TimeOfDay`), a
battery threshold (`BatteryBelow`) and the charger (`ChargingStarted`), none of
which needs a new permission. The user asked for a rule that fires on arrival —
"quando arrivo a casa ricordami di annaffiare". That cannot be done without
location, and location is exactly the kind of permission `Automation.kt` had
previously ruled out "by design".

Adding a dangling `ACCESS_BACKGROUND_LOCATION` with no feature behind it would
have been the wrong move: a red flag for Play review, non-functional without a
foreground grant, and a contradiction of the privacy posture. The permission is
only justified when a concrete, bounded feature uses it. This ADR records the
bounded feature.

## Decision

- A fourth trigger, `Trigger.ArrivedAt(place)`, fires when the phone **enters**
  a circular geofence around a place the user has saved. Only entry, not exit.
- A rule references a place **by name**; the coordinates live in the user's own
  list, `JARVIS/Luoghi.md`, one readable line per place
  (`- casa @45.464200,9.190000 r150`). A rule whose place has no coordinates yet
  is kept but stays **dormant** — it cannot arm until the place is defined.
- The geofence is registered with the OS (`GeofencingClient`) and evaluated
  **on-device**. JARVIS reads the phone's location exactly once — when the user
  taps "salva la posizione attuale" — and never polls it afterwards. No location
  ever leaves the device; the feature needs no network.
- Three permissions are declared and requested in visible steps:
  `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` (read the position once,
  register a geofence) and `ACCESS_BACKGROUND_LOCATION` (let the single
  user-created geofence fire while the app is closed — the whole point of "tell
  me when I get home"). Foreground is requested first, then the "consenti
  sempre" that background needs.
- The whole feature is inert until the user grants location **and** saves a
  place. With no grant the rules are still stored and shown, they simply do not
  arm.
- An arrival transition runs through the same `AutomationRunner` as every other
  trigger: a location rule is a new *when*, not a new *what*. The allowlisted
  actions (notify / speak / add-to-agenda) are unchanged.
- Geofences do not survive a reboot, an app update or a force-stop, so they are
  rebuilt from the files on `BOOT_COMPLETED`, on `MY_PACKAGE_REPLACED` and on
  every cold start (`JarvisApplication`), alongside the exact alarms.

## Consequences

The engine gains a location trigger without a hidden tracker: one explicit,
user-drawn fence, watched by the system, with the coordinate written where the
user can read and delete it in Obsidian. `docs/PRIVACY.md` and
`docs/SECURITY.md` are updated to describe the permission and its single use.

Geofencing depends on Google Play Services location, which the target HONOR 200
has; a pure-AOSP device without GMS would not arm arrival rules, while every
other trigger keeps working. Exit geofences, "quando esco da…", and multi-word
place names are deliberately left for later — the parser takes a single place
token today.
