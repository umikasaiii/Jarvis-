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
import numpy as np

FAKE_EMBEDDING_DIM = 64


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
