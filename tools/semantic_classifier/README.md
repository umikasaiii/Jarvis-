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

Whoever continues this with real network access to fetch
`litert-community/embeddinggemma-300m` should:

1. Verify/adjust `real_embedder()` in `embed.py` against the real model's
   actual public API (sentence-transformers, or a raw TFLite/ONNX path).
2. Re-run every script below with the real embedder instead of `--fake`.
3. Copy `thresholds.json` from `export.py`'s output into
   `app/src/main/assets/semantic/thresholds.json` (read by
   `EmbeddingSemanticClassifier.loadThresholds()` — falls back to
   `ClassifierThresholds.CONSERVATIVE_DEFAULT` if the asset is absent).

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
