# Phase-1 Physical Test Checklist — HONOR 200

The debug APK is produced and verified to **compile** by CI (see
`docs/ANDROID_BUILD_AUDIT.md`). Compilation is NOT proof that the audio loop,
AirPods routing, or offline TTS actually work — those can only be confirmed by
running this checklist on the physical HONOR 200. Record the result of each step.

## Install

- CI artifact: run `app-debug` → contains `app-debug.apk` (+ `.sha256`).
- Verify the download: `sha256sum app-debug.apk` must match the value in
  `app-debug.apk.sha256`.
- Install: `adb install -r app-debug.apk`.

## Checklist

| # | Step | Pass? | Notes |
|---|------|-------|-------|
| 1 | APK installs without error | ☐ | |
| 2 | On first launch, permissions requested: microfono, notifiche, Bluetooth | ☐ | granted / denied |
| 3 | Connect AirPods; Home shows "AirPods" only when actually detected | ☐ | |
| 4 | Open app; UI renders, no crash | ☐ | |
| 5 | **Test voce** (Diagnostica): hears *"Sistema audio operativo. Sono pronto."* | ☐ | offline voice? |
| 6 | **Test microfono** (Diagnostica): mic level moves while speaking | ☐ | level 0..1 |
| 7 | Main **Parla** button: press → notification appears → fixed reply spoken | ☐ | |
| 8 | Reply comes out of the **AirPods** (not phone) when connected | ☐ | route shown in Diagnostica |
| 9 | **Tile**: add JARVIS to Quick Settings → tap → app opens → session starts | ☐ | |
| 10 | Disconnect AirPods mid-session | ☐ | behavior |
| 11 | Fallback to phone mic/speaker; Diagnostica shows the change; no crash | ☐ | |
| 12 | Screen off during a session | ☐ | continues? |
| 13 | Incoming call during a session | ☐ | audio focus released; app recovers |
| 14 | **Chiudi sessione** / **Interrompi** from the notification stops cleanly | ☐ | service gone |
| 15 | Collect technical errors (Diagnostica "Ultimo errore" + `adb logcat`) | ☐ | redacted only |

## Diagnostica screen fields to record

input richiesto · output richiesto · comm. device applicato · input via Bluetooth ·
audio focus · sample rate · stato TTS · voce offline · livello microfono · ultimo errore.

## Honesty note

- ✅ Provable by CI: the app **compiles** and the debug **APK is produced**.
- ⛔ NOT proven yet (needs this checklist on-device): AirPods mic/route actually
  used, offline TTS audible, mic capture level correct, FGS + tile behavior,
  call/AirPods-loss handling.

Do not advance to Phase 2 (sherpa-onnx STT/VAD) until these steps pass on the
HONOR 200.
