"""§ FASE 2A.11 ADDENDUM §4 — splits the generated corpus into train/
validation/test/blind by TEMPLATE FAMILY (never by individual example), so a
near-duplicate paraphrase of a training sentence can never leak into test
just because it happens to differ in one slot value — the whole family
(all its slot/perturbation variants) always lands in the SAME split.

`blind_only` families (see `generate_corpus.py`) are always assigned to the
`blind` split regardless of anything below — they represent held-out
categories/phrasings never touched by training, validation, or threshold
calibration, used only for the final blind accuracy numbers (§12).

Splitting is STRATIFIED PER CATEGORY (`Family.category`, one of the nine
builder functions in `generate_corpus.py`) rather than over the flat family
list — a purely global 70/15/15 hash split can (and, measured, did) leave a
whole category like DEVICE_INFO or MULTI_SOURCE_REASONING completely absent
from the test split just by bad luck of which few families landed where.
Stratifying per category guarantees every category contributes to every
split whenever it has at least 3 non-blind families (true for all nine
categories here) — real per-category/per-domain test metrics require this,
not just a large enough total.
"""
from __future__ import annotations

import hashlib
import json

from generate_corpus import Example, build_all_families, expand_family, rng

TRAIN_RATIO = 70
VALIDATION_RATIO = 15
# remaining 15 -> test


def _family_order_key(family_id: str) -> str:
    return hashlib.sha256(family_id.encode("utf-8")).hexdigest()


def assign_family_splits(non_blind_family_ids_by_category: dict[str, list[str]]) -> dict[str, str]:
    """
    Deterministic, coverage-guaranteed split assignment. For each category's
    family id list (already sorted deterministically by hash), families are
    dealt round-robin against a repeating pattern — for >=7 families per
    category a 7-slot pattern (5 train / 1 validation / 1 test, ~71/14/14,
    close to the requested 70/15/15) guarantees all three splits appear; for
    3-6 families a plain train/validation/test round robin is used instead
    (further from the ratio, but the alternative — a ratio-weighted slice —
    can leave a whole split with zero families when the category is this
    small, which is worse: no families is not "a smaller test set", it's a
    missing category in that split's metrics).
    """
    assignment: dict[str, str] = {}
    for category, family_ids in non_blind_family_ids_by_category.items():
        ordered = sorted(family_ids, key=_family_order_key)
        n = len(ordered)
        if n == 0:
            continue
        if n >= 7:
            pattern = ["train", "train", "train", "train", "train", "validation", "test"]
        elif n >= 3:
            pattern = ["train", "validation", "test"]
        elif n == 2:
            pattern = ["train", "validation"]
        else:
            pattern = ["train"]
        for i, fam_id in enumerate(ordered):
            assignment[fam_id] = pattern[i % len(pattern)]
    return assignment


def build_corpus_json() -> tuple[dict, int]:
    """Returns (corpus_json, exact_duplicates_removed) — the latter counts
    text collisions ACROSS THE WHOLE generated pool (not just within one
    family, which `expand_family` already dedupes internally), keeping the
    first occurrence and dropping the rest so the materialized dataset never
    ships the same sentence twice under two different labels."""
    families = build_all_families()
    rand = rng()
    examples: list[Example] = []
    for fam in families:
        examples.extend(expand_family(fam, rand))

    seen_text: set[str] = set()
    deduped: list[Example] = []
    duplicates_removed = 0
    for e in examples:
        if e.text in seen_text:
            duplicates_removed += 1
            continue
        seen_text.add(e.text)
        deduped.append(e)

    non_blind_by_category: dict[str, list[str]] = {}
    for fam in families:
        if fam.blind_only:
            continue
        non_blind_by_category.setdefault(fam.category, []).append(fam.id)

    family_split = assign_family_splits(non_blind_by_category)

    prototypes = []
    for e in deduped:
        split = "blind" if e.blind_only else family_split[e.template_family]
        prototypes.append({
            "text": e.text,
            "intent": e.intent,
            "domains": e.domains,
            "operation": e.operation,
            "referenceMode": e.reference_mode,
            "split": split,
            "templateFamily": e.template_family,
            "category": e.category,
            "hardNegative": e.hard_negative,
        })
    return {"prototypes": prototypes}, duplicates_removed


def assert_family_never_split_across_splits(corpus: dict) -> None:
    family_splits: dict[str, set[str]] = {}
    for p in corpus["prototypes"]:
        family_splits.setdefault(p["templateFamily"], set()).add(p["split"])
    offenders = {fam: splits for fam, splits in family_splits.items() if len(splits) > 1}
    if offenders:
        raise AssertionError(f"families spanning multiple splits (should be impossible by construction): {offenders}")


def assert_every_category_covers_train_val_test(corpus: dict) -> None:
    by_cat: dict[str, set[str]] = {}
    for p in corpus["prototypes"]:
        if p["split"] == "blind":
            continue
        by_cat.setdefault(p["category"], set()).add(p["split"])
    gaps = {cat: {"train", "validation", "test"} - splits for cat, splits in by_cat.items()
            if {"train", "validation", "test"} - splits}
    if gaps:
        raise AssertionError(f"categories missing a split (real test-set coverage gap): {gaps}")


def materialize_dataset_files(corpus: dict, out_dir: str) -> dict:
    """§ ADDENDUM (concrete-dataset requirement) — physically writes one
    JSON file PER SPLIT to `out_dir`, in addition to the combined
    `training_corpus.json` the pipeline scripts read from — so the dataset
    exists on disk as real, inspectable files, not only as something a
    script could regenerate."""
    import os

    os.makedirs(out_dir, exist_ok=True)
    paths: dict[str, str] = {}
    for split_name in ("train", "validation", "test", "blind"):
        rows = [p for p in corpus["prototypes"] if p["split"] == split_name]
        path = os.path.join(out_dir, f"{split_name}.json")
        with open(path, "w", encoding="utf-8") as f:
            json.dump({"prototypes": rows}, f, ensure_ascii=False, indent=1)
        paths[split_name] = path
    return paths


def dataset_stats(corpus: dict, duplicates_removed: int) -> dict:
    from collections import Counter

    protos = corpus["prototypes"]
    by_split: dict[str, list[dict]] = {}
    for p in protos:
        by_split.setdefault(p["split"], []).append(p)

    intent_counts = Counter(p["intent"] for p in protos)
    domain_counts = Counter(d for p in protos for d in p["domains"])
    families = sorted({p["templateFamily"] for p in protos})
    hard_negative_n = sum(1 for p in protos if p["hardNegative"])

    return {
        "total_examples": len(protos),
        "duplicates_removed": duplicates_removed,
        "distinct_template_families": len(families),
        "hard_negative_count": hard_negative_n,
        "hard_negative_percentage": round(100 * hard_negative_n / len(protos), 2) if protos else 0.0,
        "rows_per_split": {k: len(v) for k, v in by_split.items()},
        "intent_distribution": dict(intent_counts),
        "domain_distribution": dict(domain_counts),
    }


if __name__ == "__main__":
    corpus, duplicates_removed = build_corpus_json()
    assert_family_never_split_across_splits(corpus)
    assert_every_category_covers_train_val_test(corpus)

    counts: dict[str, int] = {}
    for p in corpus["prototypes"]:
        counts[p["split"]] = counts.get(p["split"], 0) + 1
    print(f"total: {len(corpus['prototypes'])} (duplicates removed: {duplicates_removed})")
    for split_name in ("train", "validation", "test", "blind"):
        print(f"  {split_name}: {counts.get(split_name, 0)}")

    out_path = "training_corpus.json"
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(corpus, f, ensure_ascii=False, indent=1)
    print(f"wrote {out_path}")

    split_paths = materialize_dataset_files(corpus, "dataset")
    for split_name, path in split_paths.items():
        print(f"wrote {path}")

    stats = dataset_stats(corpus, duplicates_removed)
    with open("dataset_report.json", "w", encoding="utf-8") as f:
        json.dump(stats, f, ensure_ascii=False, indent=2)
    print("wrote dataset_report.json")
    print(json.dumps(stats, indent=2, ensure_ascii=False))
