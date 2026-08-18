# Voice (TTS)

How JARVIS speaks: engines, models, fallback, and how to change or update the
voice. See `docs/MODELS.md` for STT/LLM models and `docs/PRIVACY.md` /
`docs/SECURITY.md` for the offline/no-telemetry guarantees this all sits on.

## Architecture (unchanged by adding Supertonic)

```
SessionCoordinator / reply text
        │  (TextToSpeechEngine interface — knows nothing below this line)
        ▼
HybridTtsEngine        — chunks the reply (SpeechShaper, :core), picks the
        │                 active engine, streams chunks into PcmPlayer while
        │                 the next chunk is still being generated, applies
        │                 audio focus (AudioFocusGate)
        ▼
NeuralTtsRepository    — which NeuralTtsEngine is selected, lazy+sticky
        │                 session loading, voice list, preview
        ▼
NeuralTtsEngine impl   — SupertonicTtsEngine | KokoroTtsEngine | PiperTtsEngine
        │                 (one of these, chosen in Impostazioni › «Voce JARVIS»)
        ▼
   sherpa-onnx OfflineTts   or   raw ONNX Runtime session
        ▼
   mono float PCM  →  PcmPlayer (AudioTrack, MODE_STREAM)  →  speaker/AirPods
```

`SessionCoordinator` only ever sees `TextToSpeechEngine`. It has no import of
`SupertonicTtsEngine`, sherpa-onnx, or ONNX Runtime, and never will — every
engine is a plugin behind `NeuralTtsEngine`, exactly like Kokoro and Piper were
before this.

## Engine: Supertonic 3 (default)

- **Engine**: [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) `1.13.5`,
  `OfflineTts` API, Supertonic model type (`OfflineTtsSupertonicModelConfig`).
- **Model**: `sherpa-onnx-supertonic-3-tts-int8-2026-05-11` (INT8), bundled in
  the APK at `app/src/main/assets/models/supertonic3/` — the one deliberate
  exception to this project's "models are always user-imported, never
  bundled" rule that Kokoro and Piper still follow (§ "Why bundled" below).
- **File**: `app/libs/sherpa-onnx-1.13.5.aar` (local AAR, not on Maven
  Central for this build — see that directory's `README.md`).
- **Parameters** (`SupertonicConfig`, `app/tts/SupertonicConfig.kt`):
  `language="it"`, `provider="cpu"`, `numThreads=4`, `speed` (reuses the
  existing "Velocità" setting shared by every engine), `numSteps` via a
  quality preset (`SupertonicQuality`, `:core`, tested):

  | Preset | numSteps |
  |---|---|
  | FAST | 6 |
  | BALANCED *(default)* | 8 |
  | QUALITY | 12 |

  `debug` is wired to `BuildConfig.DEBUG` — always `false` in a release build.
- **Voice**: `voice.bin` is one bundled style, exposed as a single selectable
  voice named `supertonic` (the existing multi-voice picker built for
  Kokoro/Piper packs still works, it just shows one entry). Testing further
  speakers/styles, if the model ever exposes more, goes through
  `SupertonicTtsEngine.setQualityProfile`'s sibling hooks — none are exposed
  today because the bundled `voice.bin` is single-style.
- **Cancellation**: `stop()` sets a flag `generateWithConfigAndCallback`'s
  callback checks on every chunk it receives, so an interrupted reply stops
  generating instead of finishing a chunk nobody will hear (see `stop()` in
  `SessionCoordinator`/`HybridTtsEngine` and `NeuralTtsEngine.cancelSynthesis()`).

### On the exact sherpa-onnx API surface

The class/field names `SupertonicTtsEngine.kt` uses
(`OfflineTtsSupertonicModelConfig.durationPredictor` /
`.textEncoder` / `.vectorEstimator` / `.vocoder` / `.ttsJson` /
`.unicodeIndexer` / `.voiceStyle`, `GenerationConfig.extra`,
`generateWithConfigAndCallback`) were specified for this integration and could
not be independently verified against the real AAR — this environment has no
network access to fetch or decompile it. If the real AAR's API differs
slightly, the fix is a field-name correction in `SupertonicTtsEngine.kt`
against the AAR's actual Kotlin sources/Javadoc, not a design change.

## Why bundled, unlike Kokoro/Piper

Kokoro and Piper are always user-imported (`TtsAssetStore`, SAF picker,
never shipped in the APK — see that file's own doc comment and
`docs/MODELS.md` §11). Supertonic is bundled because that was specified for
this integration; it is a deliberate, visible exception (APK size), not an
accidental drift from the project's usual model policy. `SupertonicTtsEngine`
declares `requiredAssets = emptySet()` — there is nothing for the user to
import — and resolves its own files via `SupertonicAssetProvisioner`, which
extracts the seven bundled files from (compressed, unaddressable-by-path)
APK assets into app-private storage once, then reuses that copy.

## Fallback chain

```
Supertonic 3  →  Android system TTS
```

If the model isn't bundled, is incomplete, fails to extract, or `OfflineTts`
fails to initialise (corrupt file, native/JNI error, anything), `load()`
returns `TtsLoadResult.Failed(...)` with a technical reason only (no
conversation content) logged and shown in Diagnostics — never a crash.
`HybridTtsEngine.ensureReady()` then falls through to the Android voice
automatically; this required no change to `HybridTtsEngine` itself, since the
same fallback path already existed for a misconfigured Kokoro/Piper.

## Changing the voice profile

- **Engine** (Supertonic / Kokoro / Piper / Android): Impostazioni › «Voce
  JARVIS» — the picker is generic over every registered `NeuralTtsEngine`, so
  Supertonic appears there automatically.
- **Quality preset** (FAST/BALANCED/QUALITY): not exposed in the main
  Settings UI (per spec, not required yet) — set programmatically via
  `SupertonicTtsEngine.setQualityProfile(...)`, or compared live from
  Diagnostics (debug builds only — see below).
- **Speed**: the existing "Velocità" slider in «Voce JARVIS», shared by all
  three neural engines.

## Debug diagnostics

In a debug build, Diagnostica has a "Supertonic (debug)" panel that
synthesises a fixed Italian sentence
("Ciao. Sono JARVIS. Il nuovo sistema vocale locale è attivo.") once per
quality preset and plays each in turn, so FAST/BALANCED/QUALITY can be
A/B-compared by ear without touching the app's actual TTS selection. It is
compiled out of release builds (`BuildConfig.DEBUG`), matching every other
developer-only panel in this app.

## Updating the model

See `app/src/main/assets/models/supertonic3/README.md` — replace the seven
files in place with a same-shaped newer release; the extraction step
re-copies automatically when the bundled file size no longer matches the
app-private copy.

## Privacy

Everything above runs fully offline, verified by construction rather than by
policy: `SupertonicTtsEngine`, `SupertonicAssetProvisioner` and the sherpa-onnx
`OfflineTts` session never open a socket, and no INTERNET permission was added
for this feature (the app already declares it for unrelated features — see
`docs/PRIVACY.md`). Production logs never include the text being spoken, only
technical failure codes (exception class names, missing-file counts).
