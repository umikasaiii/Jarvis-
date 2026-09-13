# Semantic classifier offline pipeline (FASE 2A.11 §13)

Offline Python pipeline to (eventually) train the definitive classification
head for the on-device `EmbeddingGemma`-based Semantic Understanding Layer.
It reads the SAME corpus the Kotlin app ships
(`core/src/main/resources/semantic/prototypes.json`, mirrored as an Android
asset), so a `PrototypeSemanticClassifierEngine` retrained here and one built
at runtime from `EmbeddingSemanticClassifier` are working from identical data.

## Honesty — what this actually is right now

This sandbox has no network access to `huggingface.co` (verified: 403 via the
environment's proxy), so **no real EmbeddingGemma embedding has ever been
computed in this session** — `embed.py`'s `real_embedder()` is written
against the most plausible public API (`sentence_transformers`) but has never
been executed with real weights. Every script here also accepts `--fake`,
which swaps in a deterministic synthetic bag-of-words embedder
(`embed.fake_embedder()`) purely to prove the plumbing runs end-to-end
(`run_selftest.py` was actually executed with `--fake` this session — see its
printed accuracy, which is near-zero, exactly as expected from a toy embedder
with no real semantic structure). **Do not read any number these scripts
print in `--fake` mode as a real accuracy/calibration result** — it proves
the code runs, not that the classifier works.

## PASSAGGIO 14B — real execution on your own PC

**This is now built and ready.** If you have a Windows PC with network
access to the official EmbeddingGemma source, see
`run_real_training.ps1` — a ONE-COMMAND runner that does everything below
for you, in order, with fail-closed gates at every step. Read
`models/README.md` first for exactly which two files to download and where
to put them, then:

```powershell
cd tools\semantic_classifier
.\run_real_training.ps1 -ModelDir ".\models" -OutputDir ".\real_run"
```

It runs: artifact manifest → TOKENIZER_GATE → ENCODER_GATE → preflight →
real embedding generation (TRAIN/VALIDATION/TEST only, BLIND untouched) →
frozen-encoder Learned Head training + export (`REAL_TRAINED`,
`CalibrationStatus=PENDING`) → the ONE-TIME TEST evaluation → a verification
bundle → the final `PASSAGGIO_14_REAL_EXECUTION` receipt. Nothing in this
runner calibrates confidence/OOD thresholds — that is a later pass's scope.

The result is still **not** authoritative production behavior — Android's
`LearnedHeadExport.isCompatibleWithRuntime()` only ever accepts
`PRODUCTION_ELIGIBLE`, never `REAL_TRAINED` — until a future calibration
pass re-exports it.

### The individual scripts, if you want to run steps by hand

- `artifact_manifest.py` — hashes + records provenance of your downloaded model/tokenizer.
- `tokenizer_qualification.py` — TOKENIZER_GATE: loads the real SentencePiece tokenizer, introspects its real format (never assumed), runs a fixed golden corpus, checks determinism.
- `encoder_qualification.py` — ENCODER_GATE: loads the real `.tflite` via `ai-edge-litert` (the same LiteRT family Android uses), checks shape/finite/non-zero/determinism/pooling.
- `preflight.py` — the full fail-closed check before any expensive work: dataset, manifest, both qualification gates, dependencies, disk space, no synthetic-cache leakage.
- `generate_embeddings.py` — resumable real embedding generation for TRAIN/VALIDATION/TEST (never BLIND), with periodic on-disk cache flush.
- `export.py --real` — trains the Learned Head on frozen real embeddings (VALIDATION-based model selection), exports `head_weights.json` (`REAL_TRAINED`, `CalibrationStatus=PENDING`).
- `run_test_protocol.py` — the ONE-TIME TEST evaluation; refuses to silently re-run against the same candidate.
- `verify_candidate.py` — rejects a candidate whose dataset/model/tokenizer/contract/schema identity doesn't match what you expect, before you copy it anywhere.
- `build_bundle.py` / `write_receipt.py` — assemble the small verification bundle and the final machine-readable receipt.

**Onestà — validated in THIS environment, but not with the real EmbeddingGemma artifact**: every script above was executed for real against a genuine (if tiny, toy) SentencePiece tokenizer + `.tflite` encoder built in this session (network to PyPI is reachable, HuggingFace/Google are not) — the code paths, gates, and failure modes are proven to work, not just written. The one thing this environment cannot do is fetch the actual `litert-community/embeddinggemma-300m` files — that first real run is yours.

## History — what came before PASSAGGIO 14B

Whoever continued this with real network access to fetch
`litert-community/embeddinggemma-300m` used to need to:

1. Verify/adjust `real_embedder()` in `embed.py` against the real model's
   actual public API. **Done in PASSAGGIO 14B** — `real_embedder()` now
   loads the real `.tflite` via `ai-edge-litert` directly (matching
   Android's own runtime family), not a separate `sentence-transformers`
   checkpoint; `real_embedder_sentence_transformers()` remains as an
   explicit, clearly-labeled alternate for the rare case the official repo
   doesn't expose a directly-loadable `.tflite`.
2. Re-run every script below with the real embedder instead of `--fake`.
   **Now orchestrated by `run_real_training.ps1`.**
3. Copy `thresholds.json` from `export.py`'s output into
   `app/src/main/assets/semantic/thresholds.json` (read by
   `EmbeddingSemanticClassifier.loadThresholds()` — falls back to
   `ClassifierThresholds.CONSERVATIVE_DEFAULT` if the asset is absent).
   **Still true, still manual** — copying an artifact into the app remains
   a deliberate, separate decision from training it (§20's non-authoritative
   default applies regardless).

## Pipeline

```
dataset.py      load_corpus(), check_near_duplicate_leakage() (embedding-similarity, not just exact text)
embed.py        fake_embedder() (self-test only) / real_embedder(model_dir) (untested, see above)
train_heads.py  sklearn LogisticRegression (intent, softmax) + OneVsRestClassifier (domains, multi-label sigmoid)
calibrate.py    grid-search intent confidence/margin thresholds on the VALIDATION split
export.py       writes thresholds.json + head_weights.json (small JSON, no framework-specific pickle)
report.py       accuracy, macro-F1, per-class precision/recall, confusion matrix, OOD false-positive rate on the TEST split
run_selftest.py orchestrates all of the above with --fake, end-to-end — the only thing verified in this environment
```

## Run

```bash
cd tools/semantic_classifier
python3 run_selftest.py
```

Requires `numpy` and `scikit-learn` (both installed and verified working in
this session via PyPI, which is reachable even though HuggingFace/Google are
not).

## Runtime classifier interface parity

`PrototypeSemanticClassifierEngine` (the Kotlin runtime engine, prototype/
centroid classifier — the one actually shipped) and a future
`LearnedHeadClassifier` (using `head_weights.json` from `export.py`) are
meant to implement the same `SemanticClassifier` interface
(`core/semantic/embedding/SemanticClassificationResult.kt`) so a trained head
can swap in later without touching `ConversationalJarvisEngine` — no such
learned-head runtime classifier has been built yet; only this offline
training/export scaffolding exists.
