"""§ JARVIS Implementation Master Plan — PASSAGGIO 14 §C/§J/§Y. The gate a
REAL training run must pass through before touching a single production
embedding — refuses (raises [ProductionGateError], never a silent
downgrade) a synthetic embedder, a synthetic/legacy-tagged embedding cache,
or a contract-version mismatch. Defense in depth alongside
`embed.py`'s own cache-key contract versioning (a version mismatch inside
the key is already a guaranteed cache MISS on lookup) — this additionally
refuses to even ATTEMPT reusing a cache, or embedding a single example,
with something that isn't tagged for real production use at all, before any
lookup happens.

Deliberately tiny and dependency-free (stdlib only, plus the two functions
it imports from `embed.py`) — this is a gate, not a framework.
"""
from __future__ import annotations

from embed import CONTRACT_VERSION, QUALIFICATION_REAL, EmbeddingCache


class ProductionGateError(RuntimeError):
    """§ Y items 1-5. Raised when a real-training precondition is not met.
    Never caught and silently downgraded to a synthetic/fallback run
    anywhere in this package — the caller must fix the real cause (a real
    embedder, a real cache) or stay in `--fake` self-test mode."""


def assert_embedder_production_ready(embed_fn) -> None:
    """§ Y item 1 — refuses [embed_fn] unless it is tagged
    [QUALIFICATION_REAL] (set only by `embed.real_embedder()` once a real
    model has actually loaded, per its own doc comment). `embed.fake_embedder()`
    always fails this check by construction (tagged `QUALIFICATION_SYNTHETIC`)."""
    qualification = getattr(embed_fn, "qualification", None)
    if qualification != QUALIFICATION_REAL:
        raise ProductionGateError(
            f"embedder is not production-qualified (qualification={qualification!r}, "
            f"required={QUALIFICATION_REAL!r}) — refusing to use it to produce a "
            "REAL_TRAINED artifact. A fake/synthetic embedder may only run with --fake "
            "self-test mode, never against the production export path.",
        )


def assert_cache_production_ready(cache: EmbeddingCache) -> None:
    """§ Y item 2/5 — refuses [cache] unless it is BOTH tagged
    [QUALIFICATION_REAL] (never [QUALIFICATION_SYNTHETIC] nor the
    [QUALIFICATION_UNKNOWN] every pre-PASSAGGIO-14/legacy cache file loads
    as) AND built under the CURRENT [CONTRACT_VERSION] — an old-contract
    cache is already a guaranteed miss on lookup by construction (§O), but
    this refuses to even proceed with one as the active cache for a real
    training run, so a stale cache can never silently sit alongside real
    entries under a mismatched identity."""
    if cache.qualification != QUALIFICATION_REAL:
        raise ProductionGateError(
            f"embedding cache at {cache.path!r} is not production-qualified "
            f"(qualification={cache.qualification!r}) — refusing to reuse it for "
            "REAL_TRAINED training. Old/synthetic/legacy caches must be rebuilt from "
            "scratch with a real, tagged embedder before they can be used here.",
        )
    if cache.contract_version != CONTRACT_VERSION:
        raise ProductionGateError(
            f"embedding cache at {cache.path!r} has contract_version="
            f"{cache.contract_version}, but the current CONTRACT_VERSION is "
            f"{CONTRACT_VERSION} — refusing to reuse it for REAL_TRAINED training.",
        )


def assert_production_ready(embed_fn, cache: EmbeddingCache | None = None) -> None:
    """Convenience wrapper — both checks, in order, for the common call site
    (a training/export script about to start a REAL run)."""
    assert_embedder_production_ready(embed_fn)
    if cache is not None:
        assert_cache_production_ready(cache)
