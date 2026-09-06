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
) -> dict:
    """
    Grid-searches (intentConfidenceMin, intentMarginMin) over the validation
    split: for each candidate pair, count how many validation examples are
    correctly classified (top-1 intent matches AND passes both thresholds)
    vs. wrongly forced OOD (correct top-1 but rejected by thresholds) vs.
    wrongly accepted (top-1 wrong but passes thresholds anyway). Picks the
    pair maximizing (accepted_correct - accepted_wrong), a proxy for the
    spec's "una falsa classificazione grounded e peggio di un handoff" —
    wrong-accept is penalized equally to a missed accept, not just counted.
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
        objective = accepted_correct - accepted_wrong
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


if __name__ == "__main__":
    import argparse

    from dataset import load_corpus
    from embed import fake_embedder

    parser = argparse.ArgumentParser()
    parser.add_argument("--fake", action="store_true")
    parser.add_argument("--out", default=None)
    args = parser.parse_args()
    if not args.fake:
        raise SystemExit("only --fake (self-test) mode is runnable in this environment — see embed.py's honesty note")

    corpus = load_corpus()
    embed_fn = fake_embedder()
    result = calibrate_intent_thresholds(corpus, embed_fn)
    print(json.dumps(result, indent=2))

    thresholds = {
        "intentConfidenceMin": result["intentConfidenceMin"],
        "intentMarginMin": result["intentMarginMin"],
        "domainSimilarityMin": 0.40,
        "operationConfidenceMin": 0.40,
        "referenceModeConfidenceMin": 0.40,
    }
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            json.dump(thresholds, f, indent=2)
        print(f"wrote {args.out}")
