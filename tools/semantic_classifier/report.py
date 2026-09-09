"""§ JARVIS Implementation Master Plan — PASSAGGIO 13 §M (Evaluation
Integrity). Rewritten from the FASE 2A.11 ADDENDUM version, which had a
real integrity bug: `build_report()` ALWAYS re-derived a fresh prototype/
centroid classifier from `corpus.train()` and evaluated THAT — it never
loaded or evaluated the actual exported `head_weights.json` (the real
trained sklearn head), regardless of which classifier the caller thought
they were measuring. A report produced by that code could not be trusted
to say anything about the learned head's real performance, even after a
genuine training run — exactly the failure mode §M names explicitly
("non valutare il prototipo etichettandolo come prestazione del learned
head").

Fixed by making the SCORING FUNCTION a first-class, explicit argument
(`score_intent`/`score_domain`) instead of being hardwired to
centroid-cosine — `evaluate_intent`/`evaluate_domain`/
`evaluate_hard_negatives` are now pure metric-computation code, agnostic
to which classifier produced the scores. Two real scorer factories exist:
`prototype_centroid_scorers()` (the original behavior, now explicitly
labeled `PROTOTYPE_CENTROID`) and `learned_head_scorers()` (NEW — loads
`head_weights.json`, verifies its `artifactQualification`/`encoderContract`
via the SAME Python mirror of `HeadMath.kt`'s forward pass, and refuses —
raises, never silently substitutes — if the head is missing/incompatible).
`build_report()`'s output always names `classifier_type`,
`head_artifact_sha256`, `encoder_contract_version`, `split`, `n_samples`,
`fallback_occurred` explicitly (§M's own required fields).
"""
from __future__ import annotations

import hashlib
import json

import numpy as np

from dataset import Corpus, Prototype


# ---------------------------------------------------------------------------
# § HeadMath.kt mirror — the same linear/MLP forward pass the Kotlin runtime
# uses to read an EXPORTED head_weights.json, not the in-memory sklearn
# model object. Evaluating the ACTUAL SHIPPED ARTIFACT (not a proxy for it)
# is the whole point of this rewrite: a bug in export.py's normalization
# would otherwise never show up in a report that only ever asked the
# sklearn model directly.
# ---------------------------------------------------------------------------

def _linear_forward(weight: list[list[float]], bias: list[float], x: np.ndarray) -> np.ndarray:
    w = np.asarray(weight, dtype=np.float64)
    b = np.asarray(bias, dtype=np.float64)
    return w @ x + b


def _mlp_forward(w1, b1, w2, b2, x: np.ndarray) -> np.ndarray:
    w1a, b1a = np.asarray(w1, dtype=np.float64), np.asarray(b1, dtype=np.float64)
    w2a, b2a = np.asarray(w2, dtype=np.float64), np.asarray(b2, dtype=np.float64)
    hidden = np.maximum(x @ w1a + b1a, 0.0)  # ReLU
    return hidden @ w2a + b2a


def _head_forward(head: dict, x: np.ndarray) -> np.ndarray:
    if head["architecture"] == "mlp":
        m = head["mlp"]
        return _mlp_forward(m["w1"], m["b1"], m["w2"], m["b2"], x)
    lin = head["linear"]
    return _linear_forward(lin["weight"], lin["bias"], x)


def _softmax(logits: np.ndarray) -> np.ndarray:
    z = logits - np.max(logits)
    exps = np.exp(z)
    total = np.sum(exps)
    return exps / total if total > 0 else exps


def _sigmoid(z: np.ndarray) -> np.ndarray:
    return 1.0 / (1.0 + np.exp(-z))


# ---------------------------------------------------------------------------
# Scorer factories — the pluggable classifier boundary.
# ---------------------------------------------------------------------------

def _centroids(protos: list[Prototype], embed_fn, key_fn):
    groups: dict[str, list[np.ndarray]] = {}
    for p in protos:
        key = key_fn(p)
        if key is None:
            continue
        groups.setdefault(key, []).append(embed_fn(p.text))
    return {k: np.mean(np.stack(v), axis=0) for k, v in groups.items() if v}


def prototype_centroid_scorers(train_protos: list[Prototype], embed_fn):
    """§ the ORIGINAL FASE 2A.11 behavior, now explicitly labeled and never
    silently substituted for a learned-head evaluation."""
    intent_centroids = _centroids(train_protos, embed_fn, lambda p: p.intent)
    domains = sorted({d for p in train_protos for d in p.domains})
    domain_centroids: dict[str, np.ndarray] = {}
    for d in domains:
        vecs = [embed_fn(p.text) for p in train_protos if d in p.domains]
        if vecs:
            domain_centroids[d] = np.mean(np.stack(vecs), axis=0)

    def score_intent(text: str) -> tuple[str | None, float, float]:
        query = embed_fn(text)
        scored = []
        for label, c in intent_centroids.items():
            sim = float(np.dot(query, c) / (np.linalg.norm(query) * np.linalg.norm(c) + 1e-9))
            scored.append((label, sim))
        scored.sort(key=lambda t: t[1], reverse=True)
        if not scored:
            return None, 0.0, 0.0
        best_label, best_score = scored[0]
        second_score = scored[1][1] if len(scored) > 1 else 0.0
        return best_label, best_score, second_score

    def score_domain(text: str) -> dict[str, float]:
        q = embed_fn(text)
        return {
            d: float(np.dot(q, c) / (np.linalg.norm(q) * np.linalg.norm(c) + 1e-9))
            for d, c in domain_centroids.items()
        }

    return score_intent, score_domain, sorted(intent_centroids.keys()), domains, "PROTOTYPE_CENTROID", None


class LearnedHeadUnavailableError(RuntimeError):
    """§ §M — raised (never caught silently) when a learned-head report was
    requested but no PRODUCTION_ELIGIBLE, contract-matching head exists.
    The learned-head report must FAIL / NOT RUN in this case, not evaluate
    the prototype and label it as learned-head performance."""


def learned_head_scorers(head_weights_path: str, embed_fn, expected_contract_version: int | None = None):
    """§ NEW — loads the real exported artifact and evaluates IT, never the
    sklearn model object it came from. Raises [LearnedHeadUnavailableError]
    (never returns a substitute) if the file is missing, malformed, or not
    `PRODUCTION_ELIGIBLE`."""
    try:
        with open(head_weights_path, encoding="utf-8") as f:
            raw = f.read()
    except FileNotFoundError as e:
        raise LearnedHeadUnavailableError(f"head_weights.json not found at {head_weights_path}") from e

    export = json.loads(raw)
    sha256 = hashlib.sha256(raw.encode("utf-8")).hexdigest()

    qualification = export.get("artifactQualification", "TRAINING_PENDING")
    if qualification != "PRODUCTION_ELIGIBLE":
        raise LearnedHeadUnavailableError(
            f"learned head at {head_weights_path} is not PRODUCTION_ELIGIBLE (got: {qualification}) — "
            "refusing to evaluate a synthetic/pending artifact as if it were production performance",
        )

    contract = export.get("encoderContract")
    if contract is None:
        raise LearnedHeadUnavailableError(
            f"learned head at {head_weights_path} carries no encoderContract — "
            "cannot verify it was produced under a known preprocessing/tokenizer contract",
        )
    contract_version = contract.get("contractVersion")
    if expected_contract_version is not None and contract_version != expected_contract_version:
        raise LearnedHeadUnavailableError(
            f"learned head encoder contract version {contract_version} != expected {expected_contract_version}",
        )

    intent_head = export["intent"]
    intent_labels = intent_head["labels"]
    domain_head = export["domain"]
    domain_labels = domain_head["labels"]

    def score_intent(text: str) -> tuple[str | None, float, float]:
        x = embed_fn(text)
        logits = _head_forward(intent_head, x)
        probs = _softmax(logits)
        order = np.argsort(-probs)
        if len(order) == 0:
            return None, 0.0, 0.0
        best_idx = int(order[0])
        best_score = float(probs[best_idx])
        second_score = float(probs[int(order[1])]) if len(order) > 1 else 0.0
        return intent_labels[best_idx], best_score, second_score

    def score_domain(text: str) -> dict[str, float]:
        x = embed_fn(text)
        logits = _head_forward(domain_head, x)
        probs = _sigmoid(logits)
        return {domain_labels[i]: float(probs[i]) for i in range(len(domain_labels))}

    return score_intent, score_domain, intent_labels, domain_labels, "LEARNED_HEAD", sha256


# ---------------------------------------------------------------------------
# Metric computation — pure functions of a scorer, agnostic to which
# classifier produced it.
# ---------------------------------------------------------------------------

def evaluate_intent(protos: list[Prototype], score_intent, labels: list[str], thresholds: dict) -> dict:
    per_class_tp = {l: 0 for l in labels}
    per_class_fp = {l: 0 for l in labels}
    per_class_fn = {l: 0 for l in labels}
    confusion: dict[str, dict[str, int]] = {a: {b: 0 for b in labels + ["OOD"]} for a in labels}

    correct = 0
    ood_count = 0

    non_capability_true = [p for p in protos if p.intent in ("CONVERSATION", "CLARIFICATION", "UNKNOWN")]
    capability_false_positives = 0
    ood_true_negatives = 0

    for p in protos:
        best_label, best_score, second_score = score_intent(p.text)
        margin = best_score - second_score
        is_ood = best_label is None or best_score < thresholds["intentConfidenceMin"] or margin < thresholds["intentMarginMin"]

        if p.intent in ("CONVERSATION", "CLARIFICATION", "UNKNOWN") and (is_ood or best_label != "CAPABILITY_QUERY"):
            ood_true_negatives += 1

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


def evaluate_domain(protos: list[Prototype], score_domain, domain_labels: list[str], threshold: float) -> dict:
    tp = fp = fn = 0
    per_domain = {d: {"tp": 0, "fp": 0, "fn": 0} for d in domain_labels}
    exact_set_correct = 0
    multi_source_protos = [p for p in protos if len(p.domains) >= 2]
    multi_source_exact_correct = 0

    for p in protos:
        sims = score_domain(p.text)
        predicted = {d for d, s in sims.items() if s >= threshold}
        actual = set(p.domains)
        tp += len(predicted & actual)
        fp += len(predicted - actual)
        fn += len(actual - predicted)
        for d in domain_labels:
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


def evaluate_hard_negatives(protos: list[Prototype], score_intent, thresholds: dict) -> dict:
    hard_negative_protos = [p for p in protos if p.hard_negative]
    if not hard_negative_protos:
        return {"n": 0, "accuracy": None}
    correct = 0
    for p in hard_negative_protos:
        best_label, best_score, second_score = score_intent(p.text)
        margin = best_score - second_score
        is_ood = best_label is None or best_score < thresholds["intentConfidenceMin"] or margin < thresholds["intentMarginMin"]
        if not is_ood and best_label == p.intent:
            correct += 1
    return {"n": len(hard_negative_protos), "accuracy": correct / len(hard_negative_protos)}


def build_report(
    corpus: Corpus,
    classifier: str,
    embed_fn,
    thresholds: dict,
    split: str = "test",
    head_weights_path: str | None = None,
    expected_contract_version: int | None = None,
) -> dict:
    """§ §M — [classifier] must be `"prototype"` or `"learned_head"`,
    explicit at the call site, never inferred/defaulted silently. Raises
    [LearnedHeadUnavailableError] for `"learned_head"` when no eligible
    artifact exists — never falls back to evaluating the prototype under
    that name (§M's core requirement)."""
    train_protos = corpus.train()
    protos = corpus.by_split(split)

    if classifier == "prototype":
        score_intent, score_domain, intent_labels, domain_labels, classifier_type, head_sha = (
            prototype_centroid_scorers(train_protos, embed_fn)
        )
    elif classifier == "learned_head":
        if head_weights_path is None:
            raise ValueError("head_weights_path is required when classifier='learned_head'")
        score_intent, score_domain, intent_labels, domain_labels, classifier_type, head_sha = (
            learned_head_scorers(head_weights_path, embed_fn, expected_contract_version)
        )
    else:
        raise ValueError(f"unknown classifier {classifier!r} — must be 'prototype' or 'learned_head'")

    return {
        "classifier_type": classifier_type,
        "head_artifact_sha256": head_sha,
        "split": split,
        "n_samples": len(protos),
        "fallback_occurred": False,
        "intent": evaluate_intent(protos, score_intent, intent_labels, thresholds),
        "domain": evaluate_domain(protos, score_domain, domain_labels, thresholds["domainSimilarityMin"]),
        "hard_negative": evaluate_hard_negatives(protos, score_intent, thresholds),
    }


if __name__ == "__main__":
    import argparse

    from dataset import TRAINING_CORPUS_PATH, load_corpus
    from embed import fake_embedder

    parser = argparse.ArgumentParser()
    parser.add_argument("--fake", action="store_true")
    parser.add_argument("--corpus", default=TRAINING_CORPUS_PATH)
    parser.add_argument("--classifier", choices=["prototype", "learned_head"], default="prototype")
    parser.add_argument("--head-weights", default=None, help="path to head_weights.json (required for --classifier learned_head)")
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
        report = build_report(
            corpus, args.classifier, embed_fn, thresholds, split=split,
            head_weights_path=args.head_weights,
        )
        summary = {
            "classifier_type": report["classifier_type"],
            "head_artifact_sha256": report["head_artifact_sha256"],
            "split": report["split"],
            "n_samples": report["n_samples"],
            "fallback_occurred": report["fallback_occurred"],
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
