# Device Test Guide — HONOR 200 (MagicOS, Snapdragon 7 Gen 3)

## Setup

1. Enable Developer options → USB debugging.
2. `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
3. Grant microphone, notifications and Bluetooth permissions when prompted.
4. Add the **JARVIS** tile to Quick Settings (edit tiles → drag JARVIS in).
5. Pair AirPods and connect them.

## MagicOS background execution

MagicOS aggressively kills background apps. If the listening service is stopped
unexpectedly:

- Settings → Battery → App launch → **JARVIS Mobile** → set to **Manage manually**
  and enable *Auto-launch*, *Secondary launch*, *Run in background*.
- Settings → Apps → JARVIS Mobile → Battery → **Unrestricted**.

We deliberately do **not** request a blanket battery-optimization exemption; the
above is user-controlled and reversible.

## Long-press-volume activation (optional, external)

Not built in (no Accessibility abuse). To bind a hardware gesture, use **Tasker**
(or a MagicOS gesture) to open `jarvis://listen` or launch the app's listening
Activity. Example Tasker: Event → Volume Long Press → Task → *Browse URL*
`jarvis://listen`.

## 20-step functional checklist (§25)

| # | Step | Expected |
|---|------|----------|
| 1 | AirPods connected | Home shows "AirPods". |
| 2 | Airplane mode ON | App still functional (offline path). |
| 3 | Start via Tile | Explicit session starts, notification appears. |
| 4 | Listening tone | Short cue plays. |
| 5 | Dictate in Italian | Captured. |
| 6 | End of sentence | VAD auto-finalizes *(phase 2)*. |
| 7 | Local transcription | Correct text *(phase 2)*. |
| 8 | Local model reply | Textual answer *(phase 3)*. |
| 9 | TTS in AirPods | Spoken through AirPods. |
| 10 | Second question in follow-up | Handled within the window. |
| 11 | Disconnect AirPods mid-session | Graceful. |
| 12 | Fallback to phone | Route switches, shown in UI, no crash. |
| 13 | Screen off | Session continues. |
| 14 | Battery saver | Still works (maybe slower). |
| 15 | Manual interrupt | Stops promptly. |
| 16 | Incoming call | Audio focus released; app recovers. |
| 17 | Model not loaded | Clear "Modello non caricato". |
| 18 | Insufficient RAM | Load blocked with explanation. |
| 19 | Vault unavailable | Clear message, no crash. |
| 20 | Network vanishes during PC request | Fast fallback to local, no long hang. |

Steps 6–8/17–20 depend on later phases; verify as they land.

## Acceptance scenarios A–F

- **A (fully offline):** internet off, PC off, AirPods on, vault available →
  Tile → speak Italian → local model reply in AirPods → can query an indexed note.
- **B (no AirPods):** phone mic/speaker used, no crash, routing change shown.
- **C (memory):** "Ricordati che per il PC preferisco 96 GB di RAM." → app proposes
  a change, shows target + content, writes only after confirmation.
- **D (PC available):** complex question sent to authorized server with visual
  indication and local fallback if it doesn't respond.
- **E (Home Assistant):** innocuous command executes and is verified via returned
  state; a security command requires biometrics.
- **F (privacy):** with the network blocked, the local path works and **no**
  unexpected external host appears in network logs.

Record measured latencies (press→tone, STT, TTFT, tokens/sec, TTS) and thermal/
battery behavior in a results table per run.
