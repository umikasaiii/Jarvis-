"""§ FASE 2A.11 ADDENDUM §6 — trains small classification heads on top of
FROZEN sentence embeddings (EmbeddingGemma itself is never fine-tuned here,
per the addendum's explicit instruction):

- intent (softmax, multi-class)
- domains (independent per-family sigmoid, multi-label — §5/§3 of the corpus)
- operation (softmax, multi-class, trained only on examples with a known
  operation — mirrors the Kotlin `PrototypeSemanticClassifierEngine`, which
  only builds an operation centroid from prototypes that declare one)
- referenceMode (softmax over NONE/PARTITIVE/ELLIPSIS)

For each head, compares a LINEAR classifier (LogisticRegression / a
OneVsRestClassifier of LogisticRegression for the multi-label domain head)
against a SMALL MLP (one hidden layer, capped width) on the VALIDATION
split, and picks the simplest architecture that reaches adequate performance
— the MLP only wins if it beats the linear head by a real margin (>=3
points), never "because it's fancier". This is the explicit comparison the
addendum requires (§6: "confrontare almeno: linear classifier; piccolo MLP.
Scegliere il più semplice che raggiunge prestazioni adeguate.").
"""
from __future__ import annotations

import json
import sys

import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.multiclass import OneVsRestClassifier
from sklearn.neural_network import MLPClassifier

from dataset import Corpus, Prototype

ALL_DOMAINS = [
    "TIME", "DEVICE", "AGENDA", "MEMORY", "KNOWLEDGE", "ARCHIVE", "SYSTEM_APP",
    "COMMUNICATION", "MEDIA", "DRIVING", "UTILITY", "WEATHER", "HEALTH", "DEVICE_INFO",
]
MLP_HIDDEN_UNITS = 24
MLP_WIN_MARGIN = 0.03  # the MLP must beat linear by at least 3 points to be chosen


def embed_prototypes(prototypes: list[Prototype], embed_fn) -> np.ndarray:
    return np.stack([embed_fn(p.text) for p in prototypes])


class HeadResult:
    def __init__(self, architecture: str, model, val_accuracy: float, linear_accuracy: float, mlp_accuracy: float):
        self.architecture = architecture
        self.model = model
        self.val_accuracy = val_accuracy
        self.linear_accuracy = linear_accuracy
        self.mlp_accuracy = mlp_accuracy


class TrainedHeads:
    def __init__(self, intent: HeadResult, domain: HeadResult, operation: HeadResult | None,
                 reference_mode: HeadResult | None, intent_labels: list[str],
                 operation_labels: list[str], reference_mode_labels: list[str]):
        self.intent = intent
        self.domain = domain
        self.operation = operation
        self.reference_mode = reference_mode
        self.intent_labels = intent_labels
        self.operation_labels = operation_labels
        self.reference_mode_labels = reference_mode_labels

    # kept for backward compatibility with export.py's earlier call shape
    @property
    def intent_head(self):
        return self.intent.model

    @property
    def domain_head(self):
        return self.domain.model


def _fit_multiclass(X: np.ndarray, y: np.ndarray, X_val: np.ndarray, y_val: np.ndarray) -> HeadResult:
    linear = LogisticRegression(max_iter=2000)
    linear.fit(X, y)
    linear_acc = float(linear.score(X_val, y_val)) if len(X_val) else float(linear.score(X, y))

    mlp = MLPClassifier(hidden_layer_sizes=(MLP_HIDDEN_UNITS,), max_iter=800, random_state=0)
    mlp.fit(X, y)
    mlp_acc = float(mlp.score(X_val, y_val)) if len(X_val) else float(mlp.score(X, y))

    if mlp_acc >= linear_acc + MLP_WIN_MARGIN:
        return HeadResult("mlp", mlp, mlp_acc, linear_acc, mlp_acc)
    return HeadResult("linear", linear, linear_acc, linear_acc, mlp_acc)


def _fit_multilabel(X: np.ndarray, y: np.ndarray, X_val: np.ndarray, y_val: np.ndarray) -> HeadResult:
    linear = OneVsRestClassifier(LogisticRegression(max_iter=2000))
    linear.fit(X, y)
    linear_pred = linear.predict(X_val) if len(X_val) else linear.predict(X)
    linear_acc = float(np.mean(linear_pred == (y_val if len(X_val) else y)))

    mlp = MLPClassifier(hidden_layer_sizes=(MLP_HIDDEN_UNITS,), max_iter=800, random_state=0)
    mlp.fit(X, y)
    mlp_pred = mlp.predict(X_val) if len(X_val) else mlp.predict(X)
    mlp_acc = float(np.mean(mlp_pred == (y_val if len(X_val) else y)))

    if mlp_acc >= linear_acc + MLP_WIN_MARGIN:
        return HeadResult("mlp", mlp, mlp_acc, linear_acc, mlp_acc)
    return HeadResult("linear", linear, linear_acc, linear_acc, mlp_acc)


def train(corpus: Corpus, embed_fn) -> TrainedHeads:
    train_protos = corpus.train()
    val_protos = corpus.validation()
    X = embed_prototypes(train_protos, embed_fn)
    X_val = embed_prototypes(val_protos, embed_fn) if val_protos else np.zeros((0, X.shape[1]))

    intent_labels = sorted({p.intent for p in train_protos})
    y_intent = np.array([intent_labels.index(p.intent) for p in train_protos])
    y_intent_val = np.array([intent_labels.index(p.intent) for p in val_protos]) if val_protos else np.array([])
    intent_result = _fit_multiclass(X, y_intent, X_val, y_intent_val)

    y_domains = np.array([[1 if d in p.domains else 0 for d in ALL_DOMAINS] for p in train_protos])
    y_domains_val = np.array([[1 if d in p.domains else 0 for d in ALL_DOMAINS] for p in val_protos]) if val_protos else np.zeros((0, len(ALL_DOMAINS)))
    domain_result = _fit_multilabel(X, y_domains, X_val, y_domains_val)

    op_train = [p for p in train_protos if p.operation != "UNKNOWN"]
    operation_result = None
    operation_labels: list[str] = []
    if len(op_train) >= 4 and len({p.operation for p in op_train}) >= 2:
        operation_labels = sorted({p.operation for p in op_train})
        # validation examples whose operation was never seen in train can't be
        # scored against a label the head was never trained on — dropped from
        # this head's validation slice, not from the corpus itself.
        op_val = [p for p in val_protos if p.operation in operation_labels]
        X_op = embed_prototypes(op_train, embed_fn)
        y_op = np.array([operation_labels.index(p.operation) for p in op_train])
        X_op_val = embed_prototypes(op_val, embed_fn) if op_val else np.zeros((0, X_op.shape[1]))
        y_op_val = np.array([operation_labels.index(p.operation) for p in op_val]) if op_val else np.array([])
        operation_result = _fit_multiclass(X_op, y_op, X_op_val, y_op_val)

    ref_train = [p for p in train_protos if p.reference_mode != "NONE"] + [
        p for p in train_protos if p.reference_mode == "NONE"
    ][: max(1, len(train_protos) // 20)]  # keep a small NONE sample so the head sees the negative class too
    reference_mode_result = None
    reference_mode_labels: list[str] = []
    if len(ref_train) >= 4 and len({p.reference_mode for p in ref_train}) >= 2:
        reference_mode_labels = sorted({p.reference_mode for p in ref_train})
        ref_val = [p for p in val_protos if p.reference_mode in reference_mode_labels]
        X_ref = embed_prototypes(ref_train, embed_fn)
        y_ref = np.array([reference_mode_labels.index(p.reference_mode) for p in ref_train])
        X_ref_val = embed_prototypes(ref_val, embed_fn) if ref_val else np.zeros((0, X_ref.shape[1]))
        y_ref_val = np.array([reference_mode_labels.index(p.reference_mode) for p in ref_val]) if ref_val else np.array([])
        reference_mode_result = _fit_multiclass(X_ref, y_ref, X_ref_val, y_ref_val)

    return TrainedHeads(
        intent_result, domain_result, operation_result, reference_mode_result,
        intent_labels, operation_labels, reference_mode_labels,
    )


def _param_count(model) -> int:
    if isinstance(model, LogisticRegression):
        return int(model.coef_.size + model.intercept_.size)
    if isinstance(model, OneVsRestClassifier):
        # a label with a single class in training (e.g. a domain absent from
        # every training example) fits sklearn's _ConstantPredictor instead
        # of a real LogisticRegression — zero learned parameters, just
        # always predicts that one class.
        return sum(int(est.coef_.size + est.intercept_.size) for est in model.estimators_ if hasattr(est, "coef_"))
    if isinstance(model, MLPClassifier):
        return sum(int(w.size) for w in model.coefs_) + sum(int(b.size) for b in model.intercepts_)
    return -1


def _disk_size_bytes(model) -> int:
    """Rough estimate: JSON-serialize the weight arrays the way export.py
    actually writes them, and measure the byte length — not a pickle size,
    which would include framework overhead never shipped to Android."""
    if isinstance(model, LogisticRegression):
        payload = {"coef": model.coef_.tolist(), "intercept": model.intercept_.tolist()}
    elif isinstance(model, OneVsRestClassifier):
        payload = {
            "coef": [est.coef_.tolist() if hasattr(est, "coef_") else None for est in model.estimators_],
            "intercept": [est.intercept_.tolist() if hasattr(est, "coef_") else None for est in model.estimators_],
        }
    elif isinstance(model, MLPClassifier):
        payload = {"coefs": [w.tolist() for w in model.coefs_], "intercepts": [b.tolist() for b in model.intercepts_]}
    else:
        return -1
    return len(json.dumps(payload).encode("utf-8"))


if __name__ == "__main__":
    import argparse

    from dataset import TRAINING_CORPUS_PATH, load_corpus
    from embed import fake_embedder

    parser = argparse.ArgumentParser()
    parser.add_argument("--fake", action="store_true")
    parser.add_argument("--corpus", default=TRAINING_CORPUS_PATH)
    args = parser.parse_args()
    if not args.fake:
        raise SystemExit("only --fake (self-test) mode is runnable in this environment — see embed.py's honesty note")

    corpus = load_corpus(args.corpus)
    embed_fn = fake_embedder()
    heads = train(corpus, embed_fn)

    for name, result, labels in (
        ("intent", heads.intent, heads.intent_labels),
        ("domain", heads.domain, ALL_DOMAINS),
        ("operation", heads.operation, heads.operation_labels),
        ("referenceMode", heads.reference_mode, heads.reference_mode_labels),
    ):
        if result is None:
            print(f"{name}: not trained (insufficient labeled data)")
            continue
        print(f"{name}: chosen={result.architecture} val_acc={result.val_accuracy:.3f} "
              f"(linear={result.linear_accuracy:.3f}, mlp={result.mlp_accuracy:.3f}) "
              f"labels={labels} params={_param_count(result.model)} "
              f"disk_bytes={_disk_size_bytes(result.model)}")
