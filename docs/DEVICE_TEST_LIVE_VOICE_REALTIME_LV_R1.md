# LV-R1 — Honor 200 device test plan (AEC3 + duplex monitor + post-AEC Silero + acoustic barge-in)

Status: **DEVICE PENDING.** Nothing below has been run. Do not mark any row PASS without executing it.
Build under test: `latest-debug` produced from the LV-R1 runtime commit (package `com.simone.jarvismobile.debug`,
update in place over the existing debug install; signing unchanged).

## Preconditions

1. Silero model imported and READY (Impostazioni > Modelli).
2. A **neural** voice selected (Kokoro or Supertonic; both 24 kHz). Piper (22,05 kHz) and the Android system voice are
   expected to be DENIED for automatic interruption (see matrix row 9-10) — tap barge-in must still work.
3. Impostazioni > Voce > **Interruzione vocale automatica = ON** (default OFF).
4. Wake word OFF for the first pass (it is a separate recognizer; the monitor refuses to start while it listens).
5. Diagnostica > card "Interruzione acustica (AEC3, debug)" visible. Note `libreria AEC3=caricata`.

## Matrix (record result + the card values for each row)

| # | Scenario | Expected | Record |
|---|----------|----------|--------|
| 1 | Neural TTS speaks, **user silent**, speaker volume 30/60/100 % | No self-interruption, 3 full utterances; `confermato=false` | frames/drops/AEC avg-max ms |
| 2 | User interrupts at start (first 1 s) | Playback stops; STT starts; `rilascioDopoConferma` small; `rilascio=RELEASED` | ms |
| 3 | User interrupts mid-utterance / near the end | same | ms |
| 4 | Loud voice / quiet voice | note reliability (quiet voice may not trigger: record, do not tune) | |
| 5 | Low volume vs high volume speaker | no self-trigger at 100 % | |
| 6 | Wired/BT headphones, A2DP | no regression; record whether duplex runs (`negato=`) | |
| 7 | Repeat the interruption 10x | each time exactly one stop, mic free, next turn normal | count |
| 8 | After an interruption, the follow-up STT transcribes the user | `AudioRecord` released BEFORE `SpeechRecognizer` start; no `ERROR_RECOGNIZER_BUSY`, no stuck mic | |
| 9 | Android system TTS | `negato=FAR_END_UNAVAILABLE`; TTS plays normally; tap-to-interrupt works | |
| 10 | Piper 22,05 kHz | `negato=FAR_RATE_UNSUPPORTED`; Piper plays normally; tap works | |
| 11 | Feature OFF | `negato=FEATURE_DISABLED`; zero microphone use during TTS | |
| 12 | Translator running / wake word listening | duplex denied (`TRANSLATOR_ACTIVE` / `WAKE_WORD_ACTIVE`) | |
| 13 | Diagnostica debug button "Test interruzione acustica" | long utterance; interrupt by voice works and is reported | |
| 14 | CPU / RAM / drops over a 60 s utterance | note `droppedCattura`, AEC avg/max, Silero avg/max | |
| 15 | MagicOS regression: normal orb turn, follow-up, wake word, translator, with the option OFF **and** ON | no behaviour change | |

## Pass criteria (device gate, all must hold before anything is called DEVICE VERIFIED)

- No self-interruption while the user is silent (rows 1, 5) on the real speaker.
- Reliable interruption by the user's voice (rows 2, 3, 7) without touching the screen.
- AudioRecord released before the recognizer starts in every interruption (row 8).
- Android TTS and 22,05 kHz stay unaffected and safely denied (rows 9, 10).
- No stuck microphone, no crash, acceptable CPU/battery (row 14).

If AEC3 does not remove the echo well enough on this phone the feature stays OFF; thresholds are NOT tuned by guessing.
