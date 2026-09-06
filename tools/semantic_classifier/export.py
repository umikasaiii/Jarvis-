"""§ FASE 2A.11 ADDENDUM §10 — exports the trained heads to the SAME small,
framework-free JSON matrix format the Kotlin `LearnedHeadClassifierEngine`
reads (`core/semantic/embedding/LearnedHeadClassifierEngine.kt`) — plain
weight/bias arrays, never a pickle/PyTorch/TFLite blob. A linear head is one
matmul; a small one-hidden-layer MLP is two matmuls with a ReLU between —
both trivial to execute as plain Kotlin `FloatArray`/`Array<FloatArray>`
operations, so there is no reason to pull in a second ML runtime (LiteRT/
ONNX) just to run a classifier this small. See `docs/` for the size/latency
comparison once real numbers exist.

## Binary-classifier normalization (important, easy to get wrong)

Both `sklearn.linear_model.LogisticRegression` and `sklearn.neural_network.
MLPClassifier` collapse a 2-class problem to a SINGLE output unit (sigmoid),
not the usual N-row/N-column softmax form — a real shape quirk that would
silently produce wrong probabilities if the Kotlin side assumed every
multiclass head has one row/column per class. This module normalizes every
multiclass head (intent/operation/referenceMode — never the domain head,
which is genuinely per-label independent sigmoid, not softmax) to always
carry >= 2 output rows/columns before export: for a binary head, class 0
gets an all-zero row/column (logit 0) and class 1 keeps the real learned
row/column — `softmax([0, z]) == [1-sigmoid(z), sigmoid(z)]`, so this is
exactly equivalent to the original sigmoid, not an approximation. The
Kotlin runtime therefore only ever needs ONE forward-pass implementation
(argmax over softmax(logits)), never a binary special case.
"""
from __future__ import annotations

import json

import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.multiclass import OneVsRestClassifier
from sklearn.neural_network import MLPClassifier

from train_heads import ALL_DOMAINS

EXPORT_SCHEMA_VERSION = 1


def _normalize_multiclass_linear(model: LogisticRegression, n_classes: int) -> dict:
    coef, intercept = model.coef_, model.intercept_
    if n_classes == 2 and coef.shape[0] == 1:
        weight = np.vstack([np.zeros_like(coef[0]), coef[0]])
        bias = np.array([0.0, float(intercept[0])])
    else:
        weight, bias = coef, intercept
    return {"weight": weight.tolist(), "bias": bias.tolist()}


def _normalize_multiclass_mlp(model: MLPClassifier, n_classes: int) -> dict:
    w1, b1 = model.coefs_[0], model.intercepts_[0]
    w2, b2 = model.coefs_[-1], model.intercepts_[-1]
    if n_classes == 2 and w2.shape[1] == 1:
        w2 = np.concatenate([np.zeros((w2.shape[0], 1)), w2], axis=1)
        b2 = np.array([0.0, float(b2[0])])
    return {"w1": w1.tolist(), "b1": b1.tolist(), "w2": w2.tolist(), "b2": b2.tolist(), "activation": "relu"}


def export_multiclass_head(model, labels: list[str]) -> dict:
    n_classes = len(labels)
    if isinstance(model, LogisticRegression):
        return {"architecture": "linear", "labels": labels, "linear": _normalize_multiclass_linear(model, n_classes)}
    if isinstance(model, MLPClassifier):
        return {"architecture": "mlp", "labels": labels, "mlp": _normalize_multiclass_mlp(model, n_classes)}
    raise ValueError(f"unsupported multiclass model type {type(model)}")


def export_domain_head(model, labels: list[str], embed_dim: int) -> dict:
    """Domain classification is genuinely multi-label (§5 of the corpus
    spec) — each label gets its OWN independent sigmoid, never a softmax
    over labels, so there is no binary-collapse quirk to normalize here."""
    if isinstance(model, OneVsRestClassifier):
        weight, bias = [], []
        for est in model.estimators_:
            if hasattr(est, "coef_"):
                weight.append(est.coef_[0].tolist())
                bias.append(float(est.intercept_[0]))
            else:
                # a domain never positive in ANY training example fits
                # sklearn's _ConstantPredictor — exported as an always-off
                # unit (strongly negative bias, zero weight), never invented
                # as "always on".
                weight.append([0.0] * embed_dim)
                bias.append(-10.0)
        return {"architecture": "linear", "labels": labels, "linear": {"weight": weight, "bias": bias}}
    if isinstance(model, MLPClassifier):
        w1, b1 = model.coefs_[0], model.intercepts_[0]
        w2, b2 = model.coefs_[-1], model.intercepts_[-1]
        return {
            "architecture": "mlp", "labels": labels,
            "mlp": {"w1": w1.tolist(), "b1": b1.tolist(), "w2": w2.tolist(), "b2": b2.tolist(), "activation": "relu"},
        }
    raise ValueError(f"unsupported domain model type {type(model)}")


def export_thresholds(thresholds: dict, out_path: str) -> None:
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(thresholds, f, indent=2)


def export_head_weights(heads, embed_dim: int, out_path: str) -> dict:
    """Exports ALL trained heads (intent/domain/operation/referenceMode) in
    one file, `head_weights.json` — the format
    `core/semantic/embedding/LearnedHeadClassifierEngine.kt` loads."""
    payload: dict = {
        "schemaVersion": EXPORT_SCHEMA_VERSION,
        "embeddingDim": embed_dim,
        "intent": export_multiclass_head(heads.intent.model, heads.intent_labels),
        "domain": export_domain_head(heads.domain.model, ALL_DOMAINS, embed_dim),
        "operation": (
            export_multiclass_head(heads.operation.model, heads.operation_labels)
            if heads.operation is not None else None
        ),
        "referenceMode": (
            export_multiclass_head(heads.reference_mode.model, heads.reference_mode_labels)
            if heads.reference_mode is not None else None
        ),
    }
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(payload, f)
    return payload


if __name__ == "__main__":
    import argparse

    from calibrate import calibrate_domain_threshold, calibrate_intent_thresholds
    from dataset import TRAINING_CORPUS_PATH, load_corpus
    from embed import fake_embedder
    from train_heads import train

    parser = argparse.ArgumentParser()
    parser.add_argument("--fake", action="store_true")
    parser.add_argument("--corpus", default=TRAINING_CORPUS_PATH)
    parser.add_argument("--out-dir", default=".")
    args = parser.parse_args()
    if not args.fake:
        raise SystemExit("only --fake (self-test) mode is runnable in this environment — see embed.py's honesty note")

    corpus = load_corpus(args.corpus)
    embed_fn = fake_embedder()

    calib = calibrate_intent_thresholds(corpus, embed_fn)
    domain_calib = calibrate_domain_threshold(corpus, embed_fn)
    thresholds = {
        "intentConfidenceMin": calib["intentConfidenceMin"],
        "intentMarginMin": calib["intentMarginMin"],
        "domainSimilarityMin": domain_calib["domainSimilarityMin"],
        "operationConfidenceMin": 0.40,
        "referenceModeConfidenceMin": 0.40,
    }
    export_thresholds(thresholds, f"{args.out_dir}/thresholds.json")
    print(f"wrote {args.out_dir}/thresholds.json: {thresholds}")

    heads = train(corpus, embed_fn)
    from embed import FAKE_EMBEDDING_DIM
    export_head_weights(heads, FAKE_EMBEDDING_DIM, f"{args.out_dir}/head_weights.json")
    print(f"wrote {args.out_dir}/head_weights.json")
