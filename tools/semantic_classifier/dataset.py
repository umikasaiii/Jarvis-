"""§ FASE 2A.11 §13 — loads the SAME corpus JSON the Kotlin app ships
(`core/src/main/resources/semantic/prototypes.json`, mirrored as an Android
asset at `app/src/main/assets/semantic/prototypes.json`) and exposes it as
plain Python dicts, split by the corpus's own `split` field (train/
validation/test) — never re-splitting here, so the SAME held-out examples
the Kotlin test suite already uses for generalization stay held out here too.

Also implements the real leakage check the spec asks for (§12: "split...
deve impedire leakage di parafrasi quasi uguali") — an embedding-similarity
check, not just the exact-text check `PrototypeCorpus.assertNoExactDuplicateAcrossSplits()`
already does in Kotlin (that one is cheap and exact-match only; this one
catches NEAR-duplicates once real embeddings are available).
"""
from __future__ import annotations

import json
import os
from dataclasses import dataclass, field

CORPUS_PATH = os.path.join(
    os.path.dirname(__file__), "..", "..", "core", "src", "main", "resources", "semantic", "prototypes.json",
)

# § FASE 2A.11 ADDENDUM — the large (3000-5000 example) offline-only training
# corpus for the Learned Head (see generate_corpus.py/split_corpus.py),
# DISTINCT from CORPUS_PATH above (the small ~176-example corpus the
# PROTOTYPE/CENTROID classifier ships with in the app).
TRAINING_CORPUS_PATH = os.path.join(os.path.dirname(__file__), "training_corpus.json")


@dataclass
class Prototype:
    text: str
    intent: str
    domains: list[str]
    operation: str = "UNKNOWN"
    reference_mode: str = "NONE"
    split: str = "train"
    category: str = ""
    hard_negative: bool = False


@dataclass
class Corpus:
    prototypes: list[Prototype] = field(default_factory=list)

    def by_split(self, split: str) -> list[Prototype]:
        return [p for p in self.prototypes if p.split == split]

    def train(self) -> list[Prototype]:
        return self.by_split("train")

    def validation(self) -> list[Prototype]:
        return self.by_split("validation")

    def test(self) -> list[Prototype]:
        return self.by_split("test")

    def blind(self) -> list[Prototype]:
        """§ ADDENDUM §12 — the blind holdout: never touched by training,
        calibration, or hyperparameter selection. Empty for the small
        prototypes.json corpus (no `blind` split there), populated for
        `training_corpus.json`."""
        return self.by_split("blind")


def load_corpus(path: str = CORPUS_PATH) -> Corpus:
    with open(path, encoding="utf-8") as f:
        raw = json.load(f)
    prototypes = [
        Prototype(
            text=p["text"],
            intent=p["intent"],
            domains=list(p.get("domains", [])),
            operation=p.get("operation", "UNKNOWN"),
            reference_mode=p.get("referenceMode", "NONE"),
            split=p.get("split", "train"),
            category=p.get("category", ""),
            hard_negative=p.get("hardNegative", False),
        )
        for p in raw["prototypes"]
    ]
    return Corpus(prototypes)


def check_near_duplicate_leakage(corpus: Corpus, embed_fn, threshold: float = 0.97) -> list[tuple[str, str, float]]:
    """
    § FASE 2A.11 §12 — pairs of prototypes in DIFFERENT splits whose cosine
    similarity exceeds [threshold] (near-identical paraphrases, not just
    exact-text duplicates already caught in Kotlin). [embed_fn] is injected
    so this works with either the real EmbeddingGemma embedder or the
    synthetic self-test one — same function, same check either way.
    """
    import numpy as np

    splits = {
        "train": corpus.train(), "validation": corpus.validation(), "test": corpus.test(),
        "blind": corpus.blind(),
    }
    embedded = {name: [(p.text, embed_fn(p.text)) for p in protos] for name, protos in splits.items() if protos}
    names = list(embedded.keys())
    pairs = [(names[i], names[j]) for i in range(len(names)) for j in range(i + 1, len(names))]
    leaks: list[tuple[str, str, float]] = []
    for a, b in pairs:
        for text_a, emb_a in embedded[a]:
            for text_b, emb_b in embedded[b]:
                sim = float(np.dot(emb_a, emb_b) / (np.linalg.norm(emb_a) * np.linalg.norm(emb_b) + 1e-9))
                if sim >= threshold:
                    leaks.append((text_a, text_b, sim))
    return leaks


if __name__ == "__main__":
    corpus = load_corpus()
    print(f"total prototypes: {len(corpus.prototypes)}")
    print(f"train={len(corpus.train())} validation={len(corpus.validation())} test={len(corpus.test())}")
