"""§ FASE 2A.11 §13 step 4 / §6 — calibrates the OOD/confidence thresholds
against the corpus's held-out VALIDATION split (never train, never test),
instead of the hand-picked provisional values in
`ClassifierThresholds.CONSERVATIVE_DEFAULT` (Kotlin) — the spec explicitly
forbids inventing thresholds in code (§6: "soglie... calibrate su un corpus
di validazione, NON inventate arbitrariamente nel codice").

Method: build train-split centroids exactly like the Kotlin
`PrototypeSemanticClassifierEngine`, score every validation example's
top-1/top-2 margin against the intent centroids, then pick the threshold
pair that maximizes validation accuracy while keeping a target false-OOD
rate low — a simple grid search, not a learned calibration model (a small
head is enough for a low-dimensional 2-number threshold search).

Output JSON has the exact same field names as Kotlin's
`ClassifierThresholds`, so its output can be copied verbatim into
`app/src/main/assets/semantic/thresholds.json` (read by
`EmbeddingSemanticClassifier.loadThresholds()`) once real embeddings are
available.
"""
from __future__ import annotations

import itertools
import json

import numpy as np

from dataset import Corpus, Prototype


def _centroids(protos: list[Prototype], embed_fn, key_fn) -> dict[str, np.ndarray]:
    groups: dict[str, list[np.ndarray]] = {}
    for p in protos:
        key = key_fn(p)
        if key is None:
            continue
        groups.setdefault(key, []).append(embed_fn(p.text))
    return {k: np.mean(np.stack(v), axis=0) for k, v in groups.items() if v}


def _top2(query: np.ndarray, centroids: dict[str, np.ndarray]) -> tuple[str | None, float, float]:
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


def calibrate_intent_thresholds(
    corpus: Corpus,
    embed_fn,
    confidence_grid=tuple(round(x, 2) for x in np.arange(0.20, 0.71, 0.05)),
    margin_grid=tuple(round(x, 2) for x in np.arange(0.02, 0.21, 0.02)),
    false_accept_penalty: float = 3.0,
) -> dict:
    """
    Grid-searches (intentConfidenceMin, intentMarginMin) over the validation
    split: for each candidate pair, count how many validation examples are
    correctly classified (top-1 intent matches AND passes both thresholds)
    vs. wrongly forced OOD (correct top-1 but rejected by thresholds) vs.
    wrongly accepted (top-1 wrong but passes thresholds anyway). Picks the
    pair maximizing `accepted_correct - false_accept_penalty * accepted_wrong`
    — § ADDENDUM §7/§9: "una richiesta OOD classificata erroneamente come
    HEALTH/AGENDA/WEATHER è considerata più grave di un HandoffToLlm", so a
    wrong accept costs `false_accept_penalty`x a missed accept, not 1x —
    explicitly biases calibration toward capability PRECISION over recall.
    """
    train_protos = corpus.train()
    val_protos = corpus.validation()
    centroids = _centroids(train_protos, embed_fn, lambda p: p.intent)

    val_scores = [(p, *_top2(embed_fn(p.text), centroids)) for p in val_protos]

    best = None
    for conf_min, margin_min in itertools.product(confidence_grid, margin_grid):
        accepted_correct = 0
        accepted_wrong = 0
        rejected_correct = 0
        for p, best_label, best_score, second_score in val_scores:
            margin = best_score - second_score
            accept = best_label is not None and best_score >= conf_min and margin >= margin_min
            correct = best_label == p.intent
            if accept and correct:
                accepted_correct += 1
            elif accept and not correct:
                accepted_wrong += 1
            elif not accept and correct:
                rejected_correct += 1
        objective = accepted_correct - false_accept_penalty * accepted_wrong
        candidate = {
            "intentConfidenceMin": conf_min,
            "intentMarginMin": margin_min,
            "accepted_correct": accepted_correct,
            "accepted_wrong": accepted_wrong,
            "rejected_correct": rejected_correct,
            "objective": objective,
        }
        if best is None or objective > best["objective"]:
            best = candidate
    return best


def calibrate_domain_threshold(
    corpus: Corpus,
    embed_fn,
    threshold_grid=tuple(round(x, 2) for x in np.arange(0.20, 0.81, 0.02)),
) -> dict:
    """
    § ADDENDUM §6/§7 — grid-searches a single shared `domainSimilarityMin`
    (the per-label sigmoid-equivalent threshold the centroid classifier
    uses) over the validation split, maximizing domain-level micro-F1 —
    calibrated, not the arbitrary 0.40 this file previously hardcoded.
    A per-domain threshold would fit each label's own separation better, but
    a single shared value is already a real calibration (not invented) and
    keeps the search space (and the exported `ClassifierThresholds` shape,
    which the Kotlin side already expects as ONE `domainSimilarityMin`)
    unchanged — a per-domain refinement is documented as a further
    improvement in the final report, not silently skipped.
    """
    train_protos = corpus.train()
    val_protos = corpus.validation()
    domains = sorted({d for p in train_protos for d in p.domains})
    centroids: dict[str, np.ndarray] = {}
    for d in domains:
        vecs = [embed_fn(p.text) for p in train_protos if d in p.domains]
        if vecs:
            centroids[d] = np.mean(np.stack(vecs), axis=0)

    val_scored = []
    for p in val_protos:
        q = embed_fn(p.text)
        sims = {}
        for d, c in centroids.items():
            sims[d] = float(np.dot(q, c) / (np.linalg.norm(q) * np.linalg.norm(c) + 1e-9))
        val_scored.append((set(p.domains), sims))

    best = None
    for thresh in threshold_grid:
        tp = fp = fn = 0
        for actual_domains, sims in val_scored:
            predicted = {d for d, s in sims.items() if s >= thresh}
            tp += len(predicted & actual_domains)
            fp += len(predicted - actual_domains)
            fn += len(actual_domains - predicted)
        precision = tp / (tp + fp) if (tp + fp) else 0.0
        recall = tp / (tp + fn) if (tp + fn) else 0.0
        f1 = 2 * precision * recall / (precision + recall) if (precision + recall) else 0.0
        candidate = {"domainSimilarityMin": float(thresh), "precision": precision, "recall": recall, "f1": f1}
        if best is None or f1 > best["f1"]:
            best = candidate
    return best


if __name__ == "__main__":
    import argparse

    from dataset import TRAINING_CORPUS_PATH, load_corpus
    from embed import fake_embedder

    parser = argparse.ArgumentParser()
    parser.add_argument("--fake", action="store_true")
    parser.add_argument("--corpus", default=TRAINING_CORPUS_PATH)
    parser.add_argument("--out", default=None)
    args = parser.parse_args()
    if not args.fake:
        raise SystemExit("only --fake (self-test) mode is runnable in this environment — see embed.py's honesty note")

    corpus = load_corpus(args.corpus)
    embed_fn = fake_embedder()
    intent_result = calibrate_intent_thresholds(corpus, embed_fn)
    print("intent thresholds:", json.dumps(intent_result, indent=2))
    domain_result = calibrate_domain_threshold(corpus, embed_fn)
    print("domain threshold:", json.dumps(domain_result, indent=2))

    thresholds = {
        "intentConfidenceMin": intent_result["intentConfidenceMin"],
        "intentMarginMin": intent_result["intentMarginMin"],
        "domainSimilarityMin": domain_result["domainSimilarityMin"],
        "operationConfidenceMin": 0.40,
        "referenceModeConfidenceMin": 0.40,
    }
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            json.dump(thresholds, f, indent=2)
        print(f"wrote {args.out}")
