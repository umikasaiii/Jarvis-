"""§ FASE 2A.11 §13 step 5 — exports only the small artifacts the app needs at
runtime: calibrated thresholds (JSON, same shape as Kotlin's
`ClassifierThresholds`) and, optionally, the trained head weights (small
linear models — a logistic regression matrix is a few KB, not a model
checkpoint). The prototype embeddings themselves are NOT exported here — the
app computes them itself at first load from the shipped `prototypes.json`
asset plus the real on-device EmbeddingGemma engine (see
`EmbeddingSemanticClassifier.ensureWarmEngine()`), so this script only needs
to ship the numbers that require a training/calibration corpus to produce.
"""
from __future__ import annotations

import json

import numpy as np


def export_thresholds(thresholds: dict, out_path: str) -> None:
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(thresholds, f, indent=2)


def export_head_weights(heads, intent_labels: list[str], domain_labels: list[str], out_path: str) -> None:
    """
    Dumps the trained heads' weight matrices as plain JSON arrays — no
    pickle, no framework-specific serialization the Kotlin runtime couldn't
    read anyway. This is prepared for a FUTURE `LearnedHeadClassifier`
    (§13's own requirement: same interface as `PrototypeClassifier`, not yet
    wired into the app since no real EmbeddingGemma embeddings exist in this
    environment to train against).
    """
    data = {
        "intentLabels": intent_labels,
        "domainLabels": domain_labels,
        "intentWeights": heads.intent_head.coef_.tolist(),
        "intentIntercept": heads.intent_head.intercept_.tolist(),
        "domainWeights": [est.coef_.tolist()[0] for est in heads.domain_head.estimators_],
        "domainIntercept": [float(est.intercept_[0]) for est in heads.domain_head.estimators_],
    }
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(data, f)


if __name__ == "__main__":
    import argparse

    from calibrate import calibrate_intent_thresholds
    from dataset import load_corpus
    from embed import fake_embedder
    from train_heads import ALL_DOMAINS, train

    parser = argparse.ArgumentParser()
    parser.add_argument("--fake", action="store_true")
    parser.add_argument("--out-dir", default=".")
    args = parser.parse_args()
    if not args.fake:
        raise SystemExit("only --fake (self-test) mode is runnable in this environment — see embed.py's honesty note")

    corpus = load_corpus()
    embed_fn = fake_embedder()

    calib = calibrate_intent_thresholds(corpus, embed_fn)
    thresholds = {
        "intentConfidenceMin": calib["intentConfidenceMin"],
        "intentMarginMin": calib["intentMarginMin"],
        "domainSimilarityMin": 0.40,
        "operationConfidenceMin": 0.40,
        "referenceModeConfidenceMin": 0.40,
    }
    export_thresholds(thresholds, f"{args.out_dir}/thresholds.json")
    print(f"wrote {args.out_dir}/thresholds.json: {thresholds}")

    heads = train(corpus, embed_fn)
    export_head_weights(heads, heads.intent_labels, ALL_DOMAINS, f"{args.out_dir}/head_weights.json")
    print(f"wrote {args.out_dir}/head_weights.json")
