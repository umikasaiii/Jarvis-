"""§ FASE 2A.11 ADDENDUM §8/§12 — produces the full metrics report the
addendum requires, evaluated on the corpus's held-out TEST split AND
separately on the BLIND holdout (never touched by training, validation, or
threshold calibration) — using whichever embed_fn is injected (real or the
synthetic self-test embedder), same report code either way.
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


def evaluate_intent(protos: list[Prototype], centroids: dict, embed_fn, thresholds: dict) -> dict:
    labels = sorted(centroids.keys())
    per_class_tp = {l: 0 for l in labels}
    per_class_fp = {l: 0 for l in labels}
    per_class_fn = {l: 0 for l in labels}
    confusion: dict[str, dict[str, int]] = {a: {b: 0 for b in labels + ["OOD"]} for a in labels}

    correct = 0
    ood_count = 0
    capability_domains = {"WEATHER", "HEALTH", "AGENDA", "DEVICE_INFO"}

    # § capability false-positive rate: examples whose TRUE intent is
    # CONVERSATION/CLARIFICATION/UNKNOWN (i.e. should never resolve to a
    # grounded capability domain) that get classified as CAPABILITY_QUERY
    # with high confidence anyway — the exact failure mode the addendum
    # calls "più grave di un HandoffToLlm".
    non_capability_true = [p for p in protos if p.intent in ("CONVERSATION", "CLARIFICATION", "UNKNOWN")]
    capability_false_positives = 0

    for p in protos:
        query = embed_fn(p.text)
        best_label, best_score, second_score = _classify(query, centroids)
        margin = best_score - second_score
        is_ood = best_label is None or best_score < thresholds["intentConfidenceMin"] or margin < thresholds["intentMarginMin"]

        if is_ood:
            ood_count += 1
            if p.intent in confusion:
                confusion[p.intent]["OOD"] += 1
                per_class_fn[p.intent] = per_class_fn.get(p.intent, 0) + 1
            continue

        if p.intent in confusion:
            confusion[p.intent][best_label] += 1
        if best_label == p.intent:
            correct += 1
            per_class_tp[p.intent] = per_class_tp.get(p.intent, 0) + 1
        else:
            per_class_fp[best_label] = per_class_fp.get(best_label, 0) + 1
            if p.intent in per_class_fn:
                per_class_fn[p.intent] = per_class_fn.get(p.intent, 0) + 1
            if p.intent in ("CONVERSATION", "CLARIFICATION", "UNKNOWN") and best_label == "CAPABILITY_QUERY":
                capability_false_positives += 1

    n = len(protos)
    accuracy = correct / n if n else 0.0

    per_class_f1 = {}
    for l in labels:
        tp, fp, fn = per_class_tp.get(l, 0), per_class_fp.get(l, 0), per_class_fn.get(l, 0)
        precision = tp / (tp + fp) if (tp + fp) else 0.0
        recall = tp / (tp + fn) if (tp + fn) else 0.0
        f1 = 2 * precision * recall / (precision + recall) if (precision + recall) else 0.0
        per_class_f1[l] = {"precision": precision, "recall": recall, "f1": f1, "support": tp + fn}

    macro_f1 = sum(v["f1"] for v in per_class_f1.values()) / len(labels) if labels else 0.0
    ood_true_negative_rate = (
        sum(1 for p in non_capability_true if _classify(embed_fn(p.text), centroids)[0] is None
            or _classify(embed_fn(p.text), centroids)[1] < thresholds["intentConfidenceMin"])
        / len(non_capability_true) if non_capability_true else 0.0
    )
    capability_fp_rate = capability_false_positives / len(non_capability_true) if non_capability_true else 0.0

    return {
        "n": n,
        "accuracy": accuracy,
        "macro_f1": macro_f1,
        "ood_count": ood_count,
        "ood_false_positive_rate": ood_count / n if n else 0.0,
        "capability_false_positive_rate": capability_fp_rate,
        "per_class": per_class_f1,
        "confusion_matrix": confusion,
    }


def evaluate_domain(protos: list[Prototype], domain_centroids: dict, embed_fn, threshold: float) -> dict:
    tp = fp = fn = 0
    per_domain = {d: {"tp": 0, "fp": 0, "fn": 0} for d in domain_centroids}
    exact_set_correct = 0
    multi_source_protos = [p for p in protos if len(p.domains) >= 2]
    multi_source_exact_correct = 0

    for p in protos:
        q = embed_fn(p.text)
        sims = {d: float(np.dot(q, c) / (np.linalg.norm(q) * np.linalg.norm(c) + 1e-9)) for d, c in domain_centroids.items()}
        predicted = {d for d, s in sims.items() if s >= threshold}
        actual = set(p.domains)
        tp += len(predicted & actual)
        fp += len(predicted - actual)
        fn += len(actual - predicted)
        for d in domain_centroids:
            if d in predicted and d in actual:
                per_domain[d]["tp"] += 1
            elif d in predicted and d not in actual:
                per_domain[d]["fp"] += 1
            elif d not in predicted and d in actual:
                per_domain[d]["fn"] += 1
        if predicted == actual:
            exact_set_correct += 1
            if len(actual) >= 2:
                multi_source_exact_correct += 1

    micro_precision = tp / (tp + fp) if (tp + fp) else 0.0
    micro_recall = tp / (tp + fn) if (tp + fn) else 0.0
    micro_f1 = 2 * micro_precision * micro_recall / (micro_precision + micro_recall) if (micro_precision + micro_recall) else 0.0

    per_domain_metrics = {}
    macro_f1_sum = 0.0
    for d, counts in per_domain.items():
        precision = counts["tp"] / (counts["tp"] + counts["fp"]) if (counts["tp"] + counts["fp"]) else 0.0
        recall = counts["tp"] / (counts["tp"] + counts["fn"]) if (counts["tp"] + counts["fn"]) else 0.0
        f1 = 2 * precision * recall / (precision + recall) if (precision + recall) else 0.0
        per_domain_metrics[d] = {"precision": precision, "recall": recall, "f1": f1}
        macro_f1_sum += f1
    macro_f1 = macro_f1_sum / len(per_domain) if per_domain else 0.0

    return {
        "n": len(protos),
        "micro_f1": micro_f1,
        "macro_f1": macro_f1,
        "exact_set_accuracy": exact_set_correct / len(protos) if protos else 0.0,
        "multi_source_exact_set_accuracy": (
            multi_source_exact_correct / len(multi_source_protos) if multi_source_protos else None
        ),
        "per_domain": per_domain_metrics,
    }


def evaluate_hard_negatives(protos: list[Prototype], centroids: dict, embed_fn, thresholds: dict) -> dict:
    hard_negative_protos = [p for p in protos if p.hard_negative]
    if not hard_negative_protos:
        return {"n": 0, "accuracy": None}
    correct = 0
    for p in hard_negative_protos:
        query = embed_fn(p.text)
        best_label, best_score, second_score = _classify(query, centroids)
        margin = best_score - second_score
        is_ood = best_label is None or best_score < thresholds["intentConfidenceMin"] or margin < thresholds["intentMarginMin"]
        if not is_ood and best_label == p.intent:
            correct += 1
    return {"n": len(hard_negative_protos), "accuracy": correct / len(hard_negative_protos)}


def build_report(corpus: Corpus, embed_fn, thresholds: dict, split: str = "test") -> dict:
    train_protos = corpus.train()
    protos = corpus.by_split(split)
    intent_centroids = _centroids(train_protos, embed_fn, lambda p: p.intent)
    domains = sorted({d for p in train_protos for d in p.domains})
    domain_centroids = {}
    for d in domains:
        vecs = [embed_fn(p.text) for p in train_protos if d in p.domains]
        if vecs:
            domain_centroids[d] = np.mean(np.stack(vecs), axis=0)

    return {
        "split": split,
        "intent": evaluate_intent(protos, intent_centroids, embed_fn, thresholds),
        "domain": evaluate_domain(protos, domain_centroids, embed_fn, thresholds["domainSimilarityMin"]),
        "hard_negative": evaluate_hard_negatives(protos, intent_centroids, embed_fn, thresholds),
    }


if __name__ == "__main__":
    import argparse
    import json

    from dataset import TRAINING_CORPUS_PATH, load_corpus
    from embed import fake_embedder

    parser = argparse.ArgumentParser()
    parser.add_argument("--fake", action="store_true")
    parser.add_argument("--corpus", default=TRAINING_CORPUS_PATH)
    parser.add_argument("--thresholds", default=None, help="path to thresholds.json from export.py")
    args = parser.parse_args()
    if not args.fake:
        raise SystemExit("only --fake (self-test) mode is runnable in this environment — see embed.py's honesty note")

    corpus = load_corpus(args.corpus)
    embed_fn = fake_embedder()
    if args.thresholds:
        with open(args.thresholds, encoding="utf-8") as f:
            thresholds = json.load(f)
    else:
        thresholds = {"intentConfidenceMin": 0.45, "intentMarginMin": 0.05, "domainSimilarityMin": 0.40}

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
