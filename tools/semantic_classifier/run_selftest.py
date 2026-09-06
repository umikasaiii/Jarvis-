"""§ FASE 2A.11 §13 — orchestrates the full offline pipeline end-to-end with
the synthetic --fake embedder, to PROVE the plumbing (dataset load → leakage
check → embed → train heads → calibrate → export → report) actually runs in
this environment — not to prove real semantic accuracy, which requires the
real EmbeddingGemma model this sandbox cannot fetch (no network access to
huggingface.co, verified via curl 403 earlier this session).

Run: python3 run_selftest.py
"""
from __future__ import annotations

import json
import sys

from calibrate import calibrate_intent_thresholds
from dataset import check_near_duplicate_leakage, load_corpus
from embed import fake_embedder
from export import export_head_weights, export_thresholds
from report import build_report
from train_heads import ALL_DOMAINS, train


def main() -> int:
    print("=== FASE 2A.11 offline pipeline self-test (SYNTHETIC embeddings only) ===")
    corpus = load_corpus()
    print(f"[dataset] loaded {len(corpus.prototypes)} prototypes "
          f"(train={len(corpus.train())} validation={len(corpus.validation())} test={len(corpus.test())})")

    embed_fn = fake_embedder()

    leaks = check_near_duplicate_leakage(corpus, embed_fn, threshold=0.97)
    print(f"[leakage] near-duplicate pairs across splits (fake-embedder similarity >= 0.97): {len(leaks)}")
    for a, b, sim in leaks[:5]:
        print(f"  - sim={sim:.3f}: {a!r} <-> {b!r}")

    print("[calibrate] grid-searching intent thresholds on validation split...")
    calib = calibrate_intent_thresholds(corpus, embed_fn)
    print(f"  -> {calib}")

    thresholds = {
        "intentConfidenceMin": calib["intentConfidenceMin"],
        "intentMarginMin": calib["intentMarginMin"],
        "domainSimilarityMin": 0.40,
        "operationConfidenceMin": 0.40,
        "referenceModeConfidenceMin": 0.40,
    }
    export_thresholds(thresholds, "thresholds.selftest.json")
    print("[export] wrote thresholds.selftest.json")

    print("[train] fitting intent softmax + domain multi-label sigmoid heads...")
    heads = train(corpus, embed_fn)
    print(f"  -> intent classes: {heads.intent_labels}")
    export_head_weights(heads, heads.intent_labels, ALL_DOMAINS, "head_weights.selftest.json")
    print("[export] wrote head_weights.selftest.json")

    print("[report] evaluating on held-out test split...")
    report = build_report(corpus, embed_fn, thresholds)
    print(json.dumps({k: v for k, v in report.items() if k != "confusion_matrix"}, indent=2))

    print("=== self-test complete: pipeline runs end-to-end (synthetic embeddings, not real semantic accuracy) ===")
    return 0


if __name__ == "__main__":
    sys.exit(main())
