# Live Voice Phase 0.8 — Device Acceptance Checklist (NOT YET RUN)

Silero VAD runtime + user-import foundation. **DEVICE VERIFIED = NO.**
Do not install over the pinned Honor Morning candidate (`1236015`) without explicit instruction.

Nothing in Phase 0.8 changes user-visible conversation behavior: production STT and the wake word
are still Android `SpeechRecognizer`; Silero is a **diagnostics-only** engine (manual 10 s test) that
never starts/stops STT, never stops TTS, never dispatches a conversation event and is not wired to
barge-in (no AEC exists, so the engine could hear JARVIS's own voice).

## Model (NOT in the repository, NOT in the APK, NOT downloaded by the app or CI)

- File: `silero_vad.onnx`, MIT, upstream `snakers4/silero-vad`
- Size: **2,327,524 bytes**
- SHA-256: **`1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3`**
- Download it manually on a PC/phone from the official upstream repository, then import it in
  **Impostazioni › Modelli › Silero VAD (rilevamento voce) › Importa**.
- Import validates size, SHA-256 and the ONNX graph (inputs `input`/`state`/`sr`, outputs `output`/`stateN`),
  then atomically promotes it to app-private `files/silero_vad/silero_vad_onnx_v5/silero_vad.onnx`.
  A rejected file leaves any previously accepted model untouched.
- A different SHA (even a newer official Silero) is rejected on purpose: it needs a new manifest,
  graph audit and qualification.

## Streaming contract implemented

`previous 64 samples (zeros at session start) + current 512-sample frame = 576 floats`, recurrent state
`[2,1,128]` carried between frames (zeros at reset / new generation / forward sequence gap), `sr = 16000`
(int64 scalar). Next context = last 64 samples of the CURRENT frame. PCM16 → float = `x / 32768`.
Default threshold 0.5 = upstream reference value, **device-unqualified**; turn hysteresis = existing
`VadTurnPolicy.PLACEHOLDER_UNQUALIFIED`.

## Scenarios

Record measured values; **no pass/fail thresholds are invented here** (they come from the qualification pass).

| # | Scenario | Expected / record | Result |
|---|---|---|---|
| A | Import the exact official model | Status «Pronto»; message confirms SHA/size/graph | |
| B | Import a wrong file (other .onnx, truncated, renamed file) | Rejected with a clear message; previous model unchanged; no temp file left | |
| C | Kill + restart the app, airplane mode ON, Diagnostics › Silero VAD | Status READY, «SHA verificato: sì»; test runs fully offline | |
| D | «Test VAD 10 s» in a silent room | speech frames ≈ 0; starts = 0; record p max/mean | |
| E | Same, normal Italian speech | speech frames > 0; starts/ends plausible; record numbers | |
| F | Quiet / low voice | record speech frames, p max | |
| G | Distant speech (2–3 m) | record | |
| H | TV / background voices | record false positives | |
| I | Music | record | |
| J | Fan / appliance noise | record | |
| K | Road / car noise | record | |
| L | Repeat the test 10× back to back | No leaked state between runs (counters restart); no crash | |
| M | Start test then leave screen / app background / cancel | Microphone released; next test starts clean | |
| N | 60 s continuous inference (temporary larger duration, debug only) | Stable; record avg/max inference, dropped | |
| O | Frame drops | `Frame persi` reported; record | |
| P | Inference latency | record avg ms / max ms per 32 ms frame | |
| Q | CPU | record | |
| R | RAM | record | |
| S | Thermal behavior over repeated runs | record | |
| T | Microphone overlap: wake word ON, conversation active, translator active, other capture | Test shows «non disponibile: microfono in uso / wake word attiva»; **never** AudioRecord + SpeechRecognizer together | |
| U | In-place APK update (same package + signing) | Imported model still present and READY (no re-import) | |

## Unavailable-state wording to verify

- Model missing: «Test VAD non disponibile: modello Silero non importato/pronto».
- Wake word enabled/listening, conversation not idle, translator active, capture active:
  «Test VAD non disponibile: microfono attualmente in uso o wake word attiva (motivo)».
  To run the test: disable the wake word in Settings and return the orb to rest.

## Three truth levels (do not mix)

1. **Engine/contract tested** (JVM, fake backend): normalization, 64+512 context, state, mapping, lifecycle, import.
2. **Imported-model contract verified at runtime**: SHA, size, ORT load, tensor names/shapes — only on a real device after import.
3. **Real device inference verified**: only after scenarios A–U are executed on the Honor.

CI cannot execute the real model (not committed, not downloaded). Levels 2–3 stay **PENDING**.
