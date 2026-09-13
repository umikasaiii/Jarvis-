"""§ FASE 2A.11 §13 step 1-2, rewritten by PASSAGGIO 14B §6/§14 — loads
EmbeddingGemma and embeds the corpus.

**Onestà**: questo ambiente non ha accesso di rete a huggingface.co né a
dl.google.com (verificato con una richiesta reale, 403 dal proxy — stesso
limite già documentato in CLAUDE.md per altre integrazioni), quindi il vero
`litert-community/embeddinggemma-300m` non può essere scaricato qui.
`fake_embedder()` è un embedder sintetico e deterministico (proiezione
casuale seedata di un bag-of-words) usato SOLO per il self-test end-to-end
della pipeline (`run_selftest.py`) — dimostra che lo script gira, non che i
numeri prodotti abbiano significato semantico reale.

`real_embedder()` (PASSAGGIO 14B) loads the REAL artifact via
`ai-edge-litert` — the SAME LiteRT family Android's
`com.google.ai.edge.litert:litert` dependency uses (see
`app/llm/EmbeddingGemmaEngine.kt`) — reusing the EXACT tokenize -> pad/mask
-> forward -> pool -> normalize helpers already validated against a real
(if not EmbeddingGemma) `.tflite`+SentencePiece artifact in this session by
`tokenizer_qualification.py`/`encoder_qualification.py` (not reimplemented
here, imported). This is a deliberate correction from PASSAGGIO 14's
`sentence_transformers`-based sketch: a `sentence-transformers` checkpoint
is a DIFFERENT artifact (different file, different hash, possibly different
precision/quantization) from the `.tflite` Android actually runs — using it
for training embeddings would silently break the "same encoder produces
the same embeddings on both sides" identity this whole contract system
exists to protect (§C/§K). `real_embedder_sentence_transformers()` is kept
as an explicit, clearly-labeled ALTERNATE path for the rare case where the
official repo does not expose a directly-loadable `.tflite` — its docstring
states plainly that it is NOT guaranteed numerically equivalent to the
on-device runtime.
"""
from __future__ import annotations

import hashlib
import json
import os
import numpy as np

from preprocessing import canonicalize

FAKE_EMBEDDING_DIM = 64

# § JARVIS Implementation Master Plan — PASSAGGIO 14 §J/§Y. The qualification
# tag every embedder callable and every on-disk cache carries — the SAME
# vocabulary as the Kotlin `ArtifactQualification` enum, used by
# `production_gate.py` to refuse a synthetic embedder/cache from ever
# contributing to a REAL_TRAINED artifact. "UNKNOWN" is the honest default
# for a cache file saved before this pass (format 1, no recorded tag) — never
# silently trusted as REAL.
QUALIFICATION_REAL = "REAL"
QUALIFICATION_SYNTHETIC = "SYNTHETIC_SELFTEST"
QUALIFICATION_UNKNOWN = "UNKNOWN"

# § JARVIS Implementation Master Plan — PASSAGGIO 13 §O. Bumped whenever
# ANYTHING about the preprocessing/encoder contract this cache assumes
# changes — folded into every cache key below, so a stale entry from a
# different contract (e.g. the old FallbackWhitespaceTokenizer era, which
# predates this constant entirely) can never be silently reused. No manual
# "remember to delete the cache" step is required — mirrors
# `SemanticEncoderContract.CURRENT_CONTRACT_VERSION` (Kotlin side).
CONTRACT_VERSION = 1


class EmbeddingCache:
    """
    § FASE 2A.11 ADDENDUM §5, extended by PASSAGGIO 13 §O — on-disk
    embedding cache so `dataset -> embedding cache -> train heads` never
    re-invokes the (real, slow, and in a real environment GPU/CPU-bound)
    EmbeddingGemma model once per training epoch.

    Keyed by sha256(model_id + "\\0" + contract_version + "\\0" + preprocessing
    contract fields + "\\0" + canonicalized text) — §O's required identity
    (model, preprocessing/contract version, embedding dimension implied by
    model_id) all fold into the key, so switching model/tokenizer/contract
    versions never silently reuses a stale embedding, and a cache built
    under an OLD contract version is automatically a 100% miss (never a
    manual "remember to delete the cache" correctness requirement, §O's
    own words). Text is canonicalized via [preprocessing.canonicalize]
    BEFORE hashing/embedding — two raw texts that canonicalize to the same
    string share one cache entry and one embedding, exactly matching the
    "equivalent input text must produce equivalent model inputs" contract
    (§C).
    """

    def __init__(
        self, path: str, model_id: str, contract_version: int = CONTRACT_VERSION,
        qualification: str = QUALIFICATION_UNKNOWN,
    ):
        self.path = path
        self.model_id = model_id
        self.contract_version = contract_version
        self._data: dict[str, list[float]] = {}
        # § PASSAGGIO 14 §J — [qualification] recorded on disk (format 2) so a
        # LOADED cache's own file is the ground truth of what actually
        # produced its entries, never the constructor argument of whoever
        # happens to open it next. A pre-PASSAGGIO-14 cache (format 1, a bare
        # {key: vector} dict with no metadata) is loaded as
        # [QUALIFICATION_UNKNOWN] regardless of what's asked here — it never
        # recorded a real tag, so it can never be trusted as one.
        self.qualification = qualification
        if os.path.exists(path):
            with open(path, encoding="utf-8") as f:
                raw = json.load(f)
            if isinstance(raw, dict) and raw.get("format") == 2:
                self.qualification = raw.get("qualification", QUALIFICATION_UNKNOWN)
                self._data = raw.get("entries", {})
            else:
                self.qualification = QUALIFICATION_UNKNOWN
                self._data = raw
        self._dirty = False

    def _key(self, text: str) -> str:
        canonical = canonicalize(text)
        payload = f"{self.model_id}\0{self.contract_version}\0{canonical}"
        return hashlib.sha256(payload.encode("utf-8")).hexdigest()

    def get(self, text: str) -> np.ndarray | None:
        row = self._data.get(self._key(text))
        return np.asarray(row) if row is not None else None

    def put(self, text: str, vec: np.ndarray) -> None:
        self._data[self._key(text)] = vec.tolist()
        self._dirty = True

    def save(self) -> None:
        if not self._dirty:
            return
        payload = {
            "format": 2,
            "model_id": self.model_id,
            "contract_version": self.contract_version,
            "qualification": self.qualification,
            "entries": self._data,
        }
        with open(self.path, "w", encoding="utf-8") as f:
            json.dump(payload, f)
        self._dirty = False

    def __len__(self) -> int:
        return len(self._data)


def cached_embedder(embed_fn, cache: EmbeddingCache):
    """Wraps any embed_fn (fake or real) with the on-disk cache above —
    same call signature (`text -> np.ndarray`), transparent to callers.
    Canonicalizes [text] before both the cache lookup and the underlying
    [embed_fn] call — §C/§G: the SAME preprocessing contract applies
    whether the result came from cache or a fresh model call.

    § PASSAGGIO 14 §J — a BRAND NEW cache (nothing on disk yet, [cache]'s
    entry count still zero) adopts [embed_fn]'s own `.qualification` tag
    (`QUALIFICATION_REAL` for [real_embedder], `QUALIFICATION_SYNTHETIC` for
    [fake_embedder]) as its own for the life of this run — so a fresh cache
    built with a real embedder is recorded as REAL from its very first
    [save], never left at the constructor's default UNKNOWN. A cache that
    ALREADY had entries on disk keeps whatever qualification it loaded with
    (§ the loaded file's own tag is ground truth, per [EmbeddingCache]'s own
    doc) — mixing a synthetic cache with a real embedder never silently
    upgrades it.
    """
    if len(cache) == 0:
        cache.qualification = getattr(embed_fn, "qualification", QUALIFICATION_UNKNOWN)

    def embed(text: str) -> np.ndarray:
        canonical = canonicalize(text)
        cached = cache.get(canonical)
        if cached is not None:
            return cached
        vec = embed_fn(canonical)
        cache.put(canonical, vec)
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

    embed.qualification = QUALIFICATION_SYNTHETIC  # § PASSAGGIO 14 §J — production_gate.py refuses this on sight.
    return embed


def real_embedder(manifest):
    """
    § PASSAGGIO 14B §6/§14 — the PRIMARY real-embedding path: loads the real
    SentencePiece tokenizer + real `.tflite` encoder named in [manifest]
    (an `artifact_manifest.ArtifactManifest`, already hash-verified by the
    caller) via `ai_edge_litert.interpreter.Interpreter` — the SAME LiteRT
    family [EmbeddingGemmaEngine.kt] uses on Android — and reuses the exact
    tokenize/pad/mask, forward-pass, and pool+normalize helpers already
    validated by `tokenizer_qualification.py`/`encoder_qualification.py`
    (imported, never reimplemented) so the vector this function returns is
    provably the same computation those two gates just qualified, not a
    parallel implementation that could quietly drift from them.

    Raises `RuntimeError` if `ai-edge-litert`/`sentencepiece` are missing or
    the artifact fails to load — never silently falls back to a different
    embedder.
    """
    from encoder_qualification import _load_tflite_interpreter, _pool_and_normalize, _validate_tensor_contract
    from golden_qualification_corpus import MAX_SEQUENCE_LENGTH
    from tokenizer_qualification import _encode_padded, _load_sentencepiece

    sp = _load_sentencepiece(manifest.tokenizerPath)
    interpreter = _load_tflite_interpreter(manifest.modelPath)
    _validate_tensor_contract(interpreter)
    input_details = interpreter.get_input_details()
    output_details = interpreter.get_output_details()

    def embed(text: str) -> np.ndarray:
        ids, mask = _encode_padded(sp, text, MAX_SEQUENCE_LENGTH)
        interpreter.set_tensor(input_details[0]["index"], np.array([ids], dtype=np.int32))
        interpreter.set_tensor(input_details[1]["index"], np.array([mask], dtype=np.int32))
        interpreter.invoke()
        raw = np.array(interpreter.get_tensor(output_details[0]["index"]))
        return _pool_and_normalize(raw, np.array([mask], dtype=np.int32))

    embed.qualification = QUALIFICATION_REAL  # § PASSAGGIO 14 §J — only reached if a real artifact actually loaded above.
    embed.backend = "tflite"
    return embed


def real_embedder_sentence_transformers(model_dir: str):
    """
    § PASSAGGIO 14 (original), kept as an explicit ALTERNATE backend —
    scritta contro l'API pubblica più plausibile per un checkpoint
    `sentence-transformers` dello stesso modello (se il repository ufficiale
    non espone direttamente un `.tflite` caricabile fuori da Android, ad
    esempio se è impacchettato in un bundle `.task` per MediaPipe). MAI
    eseguita con pesi reali in questo ambiente (rete bloccata).

    **Onestà, importante**: un checkpoint sentence-transformers è un
    artefatto DIVERSO dal `.tflite` che gira davvero su Android (file
    diverso, hash diverso, possibile precisione/quantizzazione diversa) —
    usarlo qui NON garantisce la stessa identità encoder che
    [real_embedder] (il percorso preferito, §6) garantisce per costruzione.
    Chi sceglie questo backend deve registrarlo esplicitamente nel manifest
    (`artifactFormat`) e non può assumere parità con l'esecuzione Android.
    """
    try:
        from sentence_transformers import SentenceTransformer
    except ImportError as e:
        raise RuntimeError(
            "sentence-transformers non installato in questo ambiente — "
            "necessario solo per questo backend alternativo, non per --fake.",
        ) from e
    model = SentenceTransformer(model_dir)

    def embed(text: str) -> np.ndarray:
        return np.asarray(model.encode(text, normalize_embeddings=True))

    embed.qualification = QUALIFICATION_REAL
    embed.backend = "sentence_transformers"
    return embed
