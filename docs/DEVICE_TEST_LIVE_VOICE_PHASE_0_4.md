# Live Voice Phase 0.4 — Device Acceptance Checklist (NOT YET RUN)

Audio route + audio focus causal observability. This phase is **CODE
PRESENT / AUTOMATED TESTED / CI VERIFIED** once CI confirms, but
**DEVICE VERIFIED = NO** until every scenario below is actually run on the
Honor 200. Do **not** install this build over the pinned Honor Morning
candidate (`1236015`) — use a separate test build.

Phase 0.4 is observability-only: none of these scenarios should produce any
behavior difference from before this phase (same mic path, same TTS voice
selection, same barge-in, same hands-free loop). What changes is only what
Diagnostica now *shows*.

## How to read the new lines

In Impostazioni → Diagnostica → "Diagnostica vocale (debug)", each turn now
shows two extra lines:

```
route=PHONE→BLUETOOTH_HEADSET · btIn=sì · cambiRoute=1
focus=inizio:GRANTED·perso:no·ripreso:no · cambiFocus=0
```

- `route=X→Y` — the best-classified preferred/connected input device at the
  start of the turn (`X`) vs. at the end (`Y`). **Not** proof that STT
  actually captured through that device — the on-device recognizer owns its
  own mic path and exposes no such introspection to the app. Bounded to
  PHONE/SPEAKER/WIRED_HEADSET/BLUETOOTH_HEADSET/AIRPODS/UNKNOWN.
- `btIn=sì/no` — whether a Bluetooth/AirPods device was the preferred input
  classification at the start or end of the turn. Again: connectivity/
  preference, never proof of use.
- `cambiRoute=N` — how many genuine (de-duplicated) route changes were
  observed during the turn.
- `focus=inizio:X·perso:Y·ripreso:Z` — the audio-focus state observed at the
  start of TTS playback (GRANTED/DENIED/DELAYED/UNKNOWN/NOT_AVAILABLE),
  whether a real loss (permanent or transient) was observed during the
  turn, and whether a gain was observed after that loss.
- `cambiFocus=N` — how many genuine (de-duplicated) focus-state changes were
  observed during the turn.

## Scenarios

### A. Phone mic / phone output (baseline, no behavior change)

1. No Bluetooth device connected. Press the orb, ask a short question,
   let JARVIS answer.
2. **Expect**: identical behavior to before this phase — same latency, same
   voice, no new permission prompt, no new notification.
3. **Expect in Diagnostica**: `route=PHONE→PHONE`, `btIn=no`,
   `cambiRoute=0`, `focus=inizio:GRANTED·perso:no·ripreso:no`,
   `cambiFocus=0`.

### B. Bluetooth headset connected before the turn starts

1. Connect a Bluetooth headset/earbuds. Press the orb, ask a question.
2. **Expect**: behavior unchanged from before this phase (capture still
   uses the same minimal path; TTS output routing is whatever Android
   already chose for `USAGE_MEDIA`, unchanged).
3. **Expect in Diagnostica**: `route=BLUETOOTH_HEADSET→BLUETOOTH_HEADSET`
   (or `AIRPODS` if the device name contains "AirPods"), `btIn=sì`.

### C. Bluetooth disconnects mid-turn

1. Start a turn with Bluetooth connected, then physically turn the headset
   off (or walk out of range) partway through the exchange.
2. **Expect**: no crash, no behavior change to the turn itself.
3. **Expect in Diagnostica**: `route=BLUETOOTH_HEADSET→PHONE` (or
   `→SPEAKER`), `cambiRoute≥1`.

### D. Bluetooth reconnects mid-turn

1. Start a turn with Bluetooth off, reconnect it partway through.
2. **Expect in Diagnostica**: `route=PHONE→BLUETOOTH_HEADSET`,
   `cambiRoute≥1`.

### E. Audio focus loss from another app (e.g. an incoming call, another
   app starting music/media playback) while JARVIS is speaking

1. Trigger a turn, and while JARVIS is speaking (TTS in progress), cause a
   focus loss — e.g. accept an incoming call, or start Spotify/another
   media app.
2. **Expect**: JARVIS's speech stops, **exactly as it already did before
   this phase** (the pause-on-loss decision itself is unchanged — see the
   Master Architecture entry for this phase).
3. **Expect in Diagnostica**: `focus=inizio:GRANTED·perso:sì·ripreso:no`
   (or `·ripreso:sì` if focus was regained and a further turn/playback
   happened within the same turn), `cambiFocus≥1`.

### F. Repeated turns in the same session (hands-free follow-up loop)

1. Ask a question, let JARVIS answer, then continue the conversation in
   the follow-up window two or three times.
2. **Expect**: each turn in Diagnostica shows its own independent
   route/focus evidence — a route change observed in turn 1 must **not**
   appear as a change at the start of turn 2 (turn 2's `initialInputRoute`
   should equal whatever the device state genuinely was when turn 2 began,
   and `cambiRoute` resets to 0 unless a *new* change happens inside turn 2
   itself).

### G. Communication-device API not engaged (expected normal case)

1. Any ordinary turn, no concurrent call, no other app requesting
   communication mode.
2. **Expect**: `communicationRouteAppliedAtStart` (not shown directly in
   the UI line above, visible via the "Mostra receipt completo"-style
   detail if added later, or inferred: this is the normal/expected case)
   is `false` — JARVIS's own capture path never engages communication mode
   today (see this phase's own honesty note in the Master Architecture:
   the on-device recognizer and `AudioFocusGate`'s `USAGE_MEDIA` output
   never request it). This is **not a bug** — it is the accurate,
   honest reflection of the real, unchanged architecture.

## Sign-off

| Scenario | Result | Notes |
|---|---|---|
| A — phone mic/output baseline | ☐ PASS / ☐ FAIL | |
| B — Bluetooth connected throughout | ☐ PASS / ☐ FAIL | |
| C — Bluetooth disconnect mid-turn | ☐ PASS / ☐ FAIL | |
| D — Bluetooth reconnect mid-turn | ☐ PASS / ☐ FAIL | |
| E — focus loss from another app | ☐ PASS / ☐ FAIL | |
| F — repeated turns stay independent | ☐ PASS / ☐ FAIL | |
| G — communication device not engaged | ☐ PASS / ☐ FAIL | |

**DEVICE VERIFIED = NO until this table is filled in on the real Honor 200
(a separate test build, never the pinned Morning candidate `1236015`).**
