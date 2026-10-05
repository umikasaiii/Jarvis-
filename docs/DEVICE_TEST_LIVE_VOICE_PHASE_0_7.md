# Live Voice Phase 0.7 — Device Acceptance Checklist (NOT YET RUN)

Single capture ownership + VAD input foundation. **DEVICE VERIFIED = NO.**
Do not install over the pinned Honor Morning candidate (`1236015`) without
explicit instruction.

Nothing in Phase 0.7 changes user-visible behavior: production conversation STT
and the wake word are still Android `SpeechRecognizer`; no VAD runs; no PCM
consumer is subscribed; nothing calls `captureContinuous()`. The only live code
path that changed is the diagnostics **mic test** (`capture(durationMs)`), now a
wrapper over `PcmCaptureEngine` + the single `AudioRecord` source.

| # | Scenario | Expected | Result |
|---|---|---|---|
| A | Diagnostics "Test microfono" on the built-in mic | Level meter moves; detail `ok source=… frames=… peak=…`; COMPLETED | |
| B | Start mic test, cancel mid-window, start again | First ends cancelled, second works; no stuck orb, no "already_owned" | |
| C | Repeat mic test 20× quickly (start/stop) | Always releases; no `no_source_init`; no leaked recorder (mic indicator off after each) | |
| D | Bluetooth (AirPods) connected, phone-mic path | Same as A (built-in mic); route/focus/mode behave exactly as before | |
| E | Bluetooth microphone path | NOT in scope: only if explicitly activated by a later phase | n/a |
| F | Live Translator active, then home-orb press | Translator handoff unchanged (`translatorTakingOver`); no capture started by 0.7 code | |
| G | Normal voice turn (orb) and hands-free follow-up | STT via SpeechRecognizer exactly as before; no `AudioRecord` opened concurrently | |
| H | MagicOS regression: orb → record → reply in AirPods; wake word on Home | Unchanged (no audio focus / mode / comm-device calls added) | |
| I | 60 s of continuous PCM capture (needs a temporary debug trigger; not shipped) | CPU/RAM stable, no GC storm; record numbers | |
| J | Same 60 s with a deliberately slow consumer | `framesDropped` > 0 and capture still finishes on time | |

Notes:
- Items I/J need a debug-only harness that does not exist in this build
  (intentionally: production must not subscribe yet). They remain PENDING.
- Never record PCM to disk while testing; the foundation persists nothing.
