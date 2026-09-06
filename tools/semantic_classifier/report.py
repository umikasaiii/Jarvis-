"""§ FASE 2A.11 §13 step 6 — produces the accuracy/macro-F1/confusion-matrix/
OOD-false-positive-rate report the spec asks for, evaluated on the corpus's
held-out TEST split (never train/validation) using whichever embed_fn is
injected — real or the synthetic self-test embedder, same report code either
way.
"""
from __future__ import annotations

import numpy as np

from dataset import Corpus, Prototype


def _centroids(protos: list[Prototype], embed_fn, key_fn):
    groups: dict[str, list[np.ndarray]] = {}
    for p in protos:
        key = key_fn(p)
        if key is None:
            continue
        groups.setdefault(key, []).append(embed_fn(p.text))
    return {k: np.mean(np.stack(v), axis=0) for k, v in groups.items() if v}


def _classify(query: np.ndarray, centroids: dict) -> tuple[str | None, float, float]:
    scored = []
    for label, c in centroids.items():
        sim = float(np.dot(query, c) / (np.linalg.norm(query) * np.linalg.norm(c) + 1e-9))
        scored.append((label, sim))
    scored.sort(key=lambda t: t[1], reverse=True)
    if not scored:
        return None, 0.0, 0.0
    best_label, best_score = scored[0]
    second_score = scored[1][1] if len(scored) > 1 else 0.0
    return best_label, best_score, second_score


def build_report(corpus: Corpus, embed_fn, thresholds: dict) -> dict:
    train_protos = corpus.train()
    test_protos = corpus.test()
    centroids = _centroids(train_protos, embed_fn, lambda p: p.intent)

    labels = sorted(centroids.keys())
    confusion = {a: {b: 0 for b in labels + ["OOD"]} for a in labels}
    correct = 0
    ood_count = 0
    ood_false_positive = 0  # classified OOD but a same-intent train example exists (should have been confident)
    per_class_tp = {l: 0 for l in labels}
    per_class_fp = {l: 0 for l in labels}
    per_class_fn = {l: 0 for l in labels}

    for p in test_protos:
        query = embed_fn(p.text)
        best_label, best_score, second_score = _classify(query, centroids)
        margin = best_score - second_score
        is_ood = best_label is None or best_score < thresholds["intentConfidenceMin"] or margin < thresholds["intentMarginMin"]

        if is_ood:
            ood_count += 1
            confusion.setdefault(p.intent, {}).setdefault("OOD", 0)
            confusion[p.intent]["OOD"] += 1
            per_class_fn[p.intent] = per_class_fn.get(p.intent, 0) + 1
            ood_false_positive += 1  # test example is in-corpus by construction, so any OOD call here is a false positive
            continue

        confusion[p.intent][best_label] += 1
        if best_label == p.intent:
            correct += 1
            per_class_tp[p.intent] = per_class_tp.get(p.intent, 0) + 1
        else:
            per_class_fp[best_label] = per_class_fp.get(best_label, 0) + 1
            per_class_fn[p.intent] = per_class_fn.get(p.intent, 0) + 1

    n = len(test_protos)
    accuracy = correct / n if n else 0.0

    per_class_f1 = {}
    for l in labels:
        tp, fp, fn = per_class_tp.get(l, 0), per_class_fp.get(l, 0), per_class_fn.get(l, 0)
        precision = tp / (tp + fp) if (tp + fp) else 0.0
        recall = tp / (tp + fn) if (tp + fn) else 0.0
        f1 = 2 * precision * recall / (precision + recall) if (precision + recall) else 0.0
        per_class_f1[l] = {"precision": precision, "recall": recall, "f1": f1, "support": tp + fn}

    macro_f1 = sum(v["f1"] for v in per_class_f1.values()) / len(labels) if labels else 0.0
    ood_false_positive_rate = ood_false_positive / n if n else 0.0

    return {
        "test_set_size": n,
        "accuracy": accuracy,
        "macro_f1": macro_f1,
        "ood_false_positive_rate": ood_false_positive_rate,
        "per_class": per_class_f1,
        "confusion_matrix": confusion,
    }


if __name__ == "__main__":
    import argparse
    import json

    from dataset import load_corpus
    from embed import fake_embedder

    parser = argparse.ArgumentParser()
    parser.add_argument("--fake", action="store_true")
    parser.add_argument("--thresholds", default=None, help="path to thresholds.json from export.py")
    args = parser.parse_args()
    if not args.fake:
        raise SystemExit("only --fake (self-test) mode is runnable in this environment — see embed.py's honesty note")

    corpus = load_corpus()
    embed_fn = fake_embedder()
    if args.thresholds:
        with open(args.thresholds, encoding="utf-8") as f:
            thresholds = json.load(f)
    else:
        thresholds = {"intentConfidenceMin": 0.45, "intentMarginMin": 0.05}

    report = build_report(corpus, embed_fn, thresholds)
    print(json.dumps({k: v for k, v in report.items() if k != "confusion_matrix"}, indent=2))
