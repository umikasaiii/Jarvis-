"""§ FASE 2A.11 §13 step 1-2 — loads EmbeddingGemma and embeds the corpus.

**Onestà**: questo ambiente non ha accesso di rete a huggingface.co né a
dl.google.com (verificato con una richiesta reale, 403 dal proxy — stesso
limite già documentato in CLAUDE.md per altre integrazioni), quindi il vero
`litert-community/embeddinggemma-300m` non può essere scaricato né eseguito
qui. La funzione `real_embedder()` sotto è scritta contro l'API pubblica più
plausibile (sentence-transformers / transformers, se disponibili in un
ambiente con accesso di rete) ma NON è mai stata eseguita con pesi reali in
questa sessione — dichiarato esplicitamente, non nascosto. `fake_embedder()`
è un embedder sintetico e deterministico (proiezione casuale seedata di un
bag-of-words) usato SOLO per il self-test end-to-end della pipeline
(`run_selftest.py`) — dimostra che lo script gira, non che i numeri prodotti
abbiano significato semantico reale.
"""
from __future__ import annotations

import hashlib
import json
import os
import numpy as np

FAKE_EMBEDDING_DIM = 64


class EmbeddingCache:
    """
    § FASE 2A.11 ADDENDUM §5 — on-disk embedding cache so `dataset ->
    embedding cache -> train heads` never re-invokes the (real, slow, and in
    a real environment GPU/CPU-bound) EmbeddingGemma model once per training
    epoch — embeddings are computed once per (text, model_id) pair and
    reused by every downstream script (train_heads.py/calibrate.py/
    report.py/export.py).

    Keyed by sha256(model_id + "\\0" + text) so switching model/tokenizer
    versions never silently reuses a stale embedding from a different model.
    Stored as one JSON file mapping key -> list[float] — simple and
    inspectable; not optimized for millions of rows, which this project's
    corpus size (thousands, not millions) never approaches.
    """

    def __init__(self, path: str, model_id: str):
        self.path = path
        self.model_id = model_id
        self._data: dict[str, list[float]] = {}
        if os.path.exists(path):
            with open(path, encoding="utf-8") as f:
                self._data = json.load(f)
        self._dirty = False

    def _key(self, text: str) -> str:
        return hashlib.sha256(f"{self.model_id}\0{text}".encode("utf-8")).hexdigest()

    def get(self, text: str) -> np.ndarray | None:
        row = self._data.get(self._key(text))
        return np.asarray(row) if row is not None else None

    def put(self, text: str, vec: np.ndarray) -> None:
        self._data[self._key(text)] = vec.tolist()
        self._dirty = True

    def save(self) -> None:
        if not self._dirty:
            return
        with open(self.path, "w", encoding="utf-8") as f:
            json.dump(self._data, f)
        self._dirty = False

    def __len__(self) -> int:
        return len(self._data)


def cached_embedder(embed_fn, cache: EmbeddingCache):
    """Wraps any embed_fn (fake or real) with the on-disk cache above —
    same call signature (`text -> np.ndarray`), transparent to callers."""

    def embed(text: str) -> np.ndarray:
        cached = cache.get(text)
        if cached is not None:
            return cached
        vec = embed_fn(text)
        cache.put(text, vec)
        return vec

    return embed


def fake_embedder():
    """
    Deterministic synthetic embedder for pipeline self-testing ONLY — a
    seeded random projection per word, summed and L2-normalized. Gives
    real clustering behavior (shared words → similar vectors) without
    claiming any resemblance to EmbeddingGemma's real output, so the
    training/calibration/export/report code can be exercised for real in
    this environment.
    """
    rng_cache: dict[str, np.ndarray] = {}

    def word_vector(word: str) -> np.ndarray:
        if word not in rng_cache:
            seed = int(hashlib.sha256(word.encode("utf-8")).hexdigest(), 16) % (2**32)
            rng = np.random.default_rng(seed)
            rng_cache[word] = rng.normal(size=FAKE_EMBEDDING_DIM)
        return rng_cache[word]

    def embed(text: str) -> np.ndarray:
        words = text.lower().split()
        if not words:
            return np.zeros(FAKE_EMBEDDING_DIM)
        vec = sum(word_vector(w) for w in words)
        norm = np.linalg.norm(vec)
        return vec / norm if norm > 0 else vec

    return embed


def real_embedder(model_dir: str):
    """
    § scritta contro l'API pubblica più plausibile per EmbeddingGemma
    (litert-community/embeddinggemma-300m) via `sentence-transformers` (se
    il modello è distribuito anche in quel formato) — MAI eseguita con pesi
    reali in questo ambiente (rete bloccata). Chi riprende questo lavoro con
    accesso di rete deve verificare/aggiustare questa funzione contro il
    vero repository prima di fidarsene.
    """
    try:
        from sentence_transformers import SentenceTransformer
    except ImportError as e:
        raise RuntimeError(
            "sentence-transformers non installato in questo ambiente — "
            "necessario solo per un vero embedding, non per il self-test (--fake).",
        ) from e
    model = SentenceTransformer(model_dir)

    def embed(text: str) -> np.ndarray:
        return np.asarray(model.encode(text, normalize_embeddings=True))

    return embed
