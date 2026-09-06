"""§ FASE 2A.11 §13 step 3 — trains small classification heads on top of
sentence embeddings: intent (softmax, multi-class) and domains (independent
per-family sigmoid, multi-label — §5). Deliberately small linear models
(logistic regression) — the spec asks for "piccoli classification heads",
not a second deep network; the embedding model already does the heavy
semantic lifting, these heads only need to draw the decision boundaries in
that space.
"""
from __future__ import annotations

import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.multiclass import OneVsRestClassifier

from dataset import Corpus, Prototype

ALL_DOMAINS = [
    "TIME", "DEVICE", "AGENDA", "MEMORY", "KNOWLEDGE", "ARCHIVE", "SYSTEM_APP",
    "COMMUNICATION", "MEDIA", "DRIVING", "UTILITY", "WEATHER", "HEALTH", "DEVICE_INFO",
]


def embed_prototypes(prototypes: list[Prototype], embed_fn) -> np.ndarray:
    return np.stack([embed_fn(p.text) for p in prototypes])


class TrainedHeads:
    def __init__(self, intent_head: LogisticRegression, domain_head: OneVsRestClassifier, intent_labels: list[str]):
        self.intent_head = intent_head
        self.domain_head = domain_head
        self.intent_labels = intent_labels


def train(corpus: Corpus, embed_fn) -> TrainedHeads:
    train_protos = corpus.train()
    X = embed_prototypes(train_protos, embed_fn)

    intent_labels = sorted({p.intent for p in train_protos})
    y_intent = np.array([intent_labels.index(p.intent) for p in train_protos])
    intent_head = LogisticRegression(max_iter=2000)
    intent_head.fit(X, y_intent)

    y_domains = np.array([[1 if d in p.domains else 0 for d in ALL_DOMAINS] for p in train_protos])
    domain_head = OneVsRestClassifier(LogisticRegression(max_iter=2000))
    domain_head.fit(X, y_domains)

    return TrainedHeads(intent_head, domain_head, intent_labels)


if __name__ == "__main__":
    import argparse
    from dataset import load_corpus
    from embed import fake_embedder

    parser = argparse.ArgumentParser()
    parser.add_argument("--fake", action="store_true", help="use the synthetic self-test embedder")
    args = parser.parse_args()
    if not args.fake:
        raise SystemExit("only --fake (self-test) mode is runnable in this environment — see embed.py's honesty note")

    corpus = load_corpus()
    embed_fn = fake_embedder()
    heads = train(corpus, embed_fn)
    print(f"trained intent head: {len(heads.intent_labels)} classes -> {heads.intent_labels}")
    print(f"trained domain head: {len(ALL_DOMAINS)} independent binary classifiers")
