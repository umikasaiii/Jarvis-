# assets/models/supertonic3/

This directory is where `SupertonicAssetProvisioner` looks for the
**sherpa-onnx-supertonic-3-tts-int8-2026-05-11** bundle, exactly these seven
files, exact names (do not rename):

```
duration_predictor.int8.onnx
text_encoder.int8.onnx
vector_estimator.int8.onnx
vocoder.int8.onnx
tts.json
unicode_indexer.bin
voice.bin
```

**None of these files are included in the repository** — same reasoning as
`app/libs/README.md`: no network access to fetch binary model weights in the
environment that built this integration, and this project never commits model
files (`CLAUDE.md`: "Never commit models, tokens, audio, or personal notes").

Until they are added, Supertonic is simply unavailable and JARVIS speaks with
the Android system voice instead — `SupertonicAssetProvisioner.ensureExtracted()`
reports `SupertonicBundle.NotBundled`, `SupertonicTtsEngine.load()` returns
`TtsLoadResult.Failed(...)`, and `HybridTtsEngine` falls back automatically.
Nothing crashes; see `docs/VOICE.md` for the full fallback chain.

## Adding the real model

1. Obtain `sherpa-onnx-supertonic-3-tts-int8-2026-05-11` from an official
   sherpa-onnx model release matching sherpa-onnx **1.13.5**.
2. Copy the seven files above into this directory, unmodified, unrenamed.
3. Add `app/libs/sherpa-onnx-1.13.5.aar` (see that directory's `README.md`).
4. Build: `./gradlew :app:assembleDebug`.
5. On first run, `SupertonicAssetProvisioner` copies these seven files out of
   the (compressed, read-only) APK assets into app-private storage
   (`filesDir/tts/supertonic/`) once; subsequent loads reuse that copy.

## Updating to a newer model release

Replace the seven files in place with the new release's files (same names).
The app-private extracted copy is refreshed automatically the next time it
differs in size from what's bundled — no manual cache-clearing needed, no code
change required for a same-shaped model update.
