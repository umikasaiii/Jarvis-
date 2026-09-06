"""§ FASE 2A.11 ADDENDUM — orchestrates the full offline pipeline end-to-end
with the synthetic --fake embedder, to PROVE the plumbing (generate corpus
-> split by family -> leakage check -> embed with on-disk cache -> train
heads (linear vs MLP comparison) -> calibrate thresholds -> export -> report
on test AND blind splits) actually runs in this environment — not to prove
real semantic accuracy, which requires the real EmbeddingGemma model this
sandbox cannot fetch (no network access to huggingface.co, verified via
curl 403 earlier this session).

Run: python3 run_selftest.py
"""
from __future__ import annotations

import json
import sys

from calibrate import calibrate_domain_threshold, calibrate_intent_thresholds
from dataset import TRAINING_CORPUS_PATH, check_near_duplicate_leakage, load_corpus
from embed import EmbeddingCache, cached_embedder, fake_embedder
from export import export_head_weights, export_thresholds
from report import build_report
from split_corpus import (
    assert_every_category_covers_train_val_test, assert_family_never_split_across_splits,
    build_corpus_json, dataset_stats, materialize_dataset_files,
)
from train_heads import train


def main() -> int:
    print("=== FASE 2A.11 ADDENDUM offline pipeline self-test (SYNTHETIC embeddings only) ===")

    print("[generate+split] building the large corpus from generate_corpus.py/split_corpus.py...")
    corpus_json, duplicates_removed = build_corpus_json()
    assert_family_never_split_across_splits(corpus_json)
    assert_every_category_covers_train_val_test(corpus_json)
    with open(TRAINING_CORPUS_PATH, "w", encoding="utf-8") as f:
        json.dump(corpus_json, f, ensure_ascii=False, indent=1)
    print(f"  wrote {TRAINING_CORPUS_PATH}")
    split_paths = materialize_dataset_files(corpus_json, "dataset")
    for split_name, path in split_paths.items():
        print(f"  wrote {path}")
    stats = dataset_stats(corpus_json, duplicates_removed)
    with open("dataset_report.json", "w", encoding="utf-8") as f:
        json.dump(stats, f, ensure_ascii=False, indent=2)
    print(f"  wrote dataset_report.json: {json.dumps(stats, ensure_ascii=False)}")

    corpus = load_corpus(TRAINING_CORPUS_PATH)
    print(f"[dataset] loaded {len(corpus.prototypes)} prototypes "
          f"(train={len(corpus.train())} validation={len(corpus.validation())} "
          f"test={len(corpus.test())} blind={len(corpus.blind())})")

    raw_embed_fn = fake_embedder()
    cache = EmbeddingCache("embedding_cache.selftest.json", model_id="fake-selftest-v1")
    embed_fn = cached_embedder(raw_embed_fn, cache)

    leaks = check_near_duplicate_leakage(corpus, embed_fn, threshold=0.97)
    cache.save()
    print(f"[leakage] near-duplicate pairs across splits (fake-embedder similarity >= 0.97): {len(leaks)}")
    for a, b, sim in leaks[:5]:
        print(f"  - sim={sim:.3f}: {a!r} <-> {b!r}")

    print("[calibrate] grid-searching intent + domain thresholds on validation split...")
    intent_calib = calibrate_intent_thresholds(corpus, embed_fn)
    domain_calib = calibrate_domain_threshold(corpus, embed_fn)
    cache.save()
    print(f"  -> intent: {intent_calib}")
    print(f"  -> domain: {domain_calib}")

    thresholds = {
        "intentConfidenceMin": intent_calib["intentConfidenceMin"],
        "intentMarginMin": intent_calib["intentMarginMin"],
        "domainSimilarityMin": domain_calib["domainSimilarityMin"],
        "operationConfidenceMin": 0.40,
        "referenceModeConfidenceMin": 0.40,
    }
    export_thresholds(thresholds, "thresholds.selftest.json")
    print("[export] wrote thresholds.selftest.json")

    print("[train] fitting intent/domain/operation/referenceMode heads (linear vs MLP comparison)...")
    heads = train(corpus, embed_fn)
    cache.save()
    for name, result in (("intent", heads.intent), ("domain", heads.domain),
                         ("operation", heads.operation), ("referenceMode", heads.reference_mode)):
        if result is None:
            print(f"  {name}: not trained (insufficient labeled data)")
        else:
            print(f"  {name}: chosen={result.architecture} val_acc={result.val_accuracy:.3f} "
                  f"(linear={result.linear_accuracy:.3f}, mlp={result.mlp_accuracy:.3f})")

    from embed import FAKE_EMBEDDING_DIM
    export_head_weights(heads, FAKE_EMBEDDING_DIM, "head_weights.selftest.json")
    print("[export] wrote head_weights.selftest.json")

    print("[report] evaluating on held-out TEST and BLIND splits...")
    for split in ("test", "blind"):
        report = build_report(corpus, embed_fn, thresholds, split=split)
        summary = {
            "split": split,
            "intent_accuracy": report["intent"]["accuracy"],
            "intent_macro_f1": report["intent"]["macro_f1"],
            "ood_false_positive_rate": report["intent"]["ood_false_positive_rate"],
            "capability_false_positive_rate": report["intent"]["capability_false_positive_rate"],
            "domain_micro_f1": report["domain"]["micro_f1"],
            "domain_macro_f1": report["domain"]["macro_f1"],
            "multi_source_exact_set_accuracy": report["domain"]["multi_source_exact_set_accuracy"],
            "hard_negative_accuracy": report["hard_negative"]["accuracy"],
            "hard_negative_n": report["hard_negative"]["n"],
        }
        print(json.dumps(summary, indent=2))
    cache.save()

    print("=== self-test complete: FULL pipeline runs end-to-end (synthetic embeddings, NOT real semantic accuracy) ===")
    print(f"[cache] embedding cache holds {len(cache)} entries (would avoid re-embedding on a real re-run)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
