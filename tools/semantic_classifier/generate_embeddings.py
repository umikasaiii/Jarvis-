"""§ JARVIS Implementation Master Plan — PASSAGGIO 14B §14/§15. Resumable
REAL embedding generation for TRAIN/VALIDATION/TEST — never BLIND (§13:
"BLIND must remain UNTOUCHED / UNINSPECTED / UNEMBEDDED").

Resource-safe for an 8 GB laptop (§15): one encoder instance (loaded once,
reused for every example — never reloaded per-batch), batch size 1 (the
underlying `.tflite` graph itself is a single-example forward pass per
[EmbeddingGemmaEngine.kt]'s own contract, so there is no batching to tune
here), periodic on-disk cache flush (bounded memory growth + crash safety:
killing this process and re-running only re-embeds whatever wasn't flushed
yet — the existing per-example [EmbeddingCache] already makes every already-
flushed example a free cache hit on resume).
"""
from __future__ import annotations

import json
import sys
import time

from artifact_manifest import ArtifactManifest, verify_manifest_matches_files
from dataset import Corpus, dataset_revision, load_corpus
from embed import CONTRACT_VERSION, EmbeddingCache, cached_embedder, real_embedder
from production_gate import assert_production_ready

FLUSH_EVERY_N_EXAMPLES = 25


def model_identity(manifest: ArtifactManifest) -> str:
    """A cache identity that changes the moment EITHER the model OR the
    tokenizer file changes — never just a human-friendly name that two
    different real artifacts could share by accident."""
    return f"{manifest.modelName}:{manifest.modelSha256[:16]}:{manifest.tokenizerSha256[:16]}"


def generate_split_embeddings(
    corpus: Corpus, split: str, embed_fn, cache: EmbeddingCache, log=print,
) -> int:
    """Embeds every example in [split] (train/validation/test — NEVER
    'blind', enforced by the caller never passing it), flushing [cache]
    every [FLUSH_EVERY_N_EXAMPLES]. Returns the count actually embedded (for
    the caller to verify against the corpus's own split count — §14 'no
    silent row skipping')."""
    if split == "blind":
        raise ValueError("generate_split_embeddings must never be called with split='blind' (§13)")
    protos = corpus.by_split(split)
    embedded_count = 0
    started_at = time.time()
    for i, proto in enumerate(protos):
        _ = embed_fn(proto.text)  # cached_embedder writes into `cache` as a side effect
        embedded_count += 1
        if (i + 1) % FLUSH_EVERY_N_EXAMPLES == 0:
            cache.save()
            elapsed = time.time() - started_at
            log(f"  [{split}] {i + 1}/{len(protos)} embedded ({elapsed:.1f}s elapsed, cache size={len(cache)})")
    cache.save()
    log(f"  [{split}] done: {embedded_count}/{len(protos)} embedded")
    if embedded_count != len(protos):
        raise RuntimeError(f"split {split!r}: embedded {embedded_count} but corpus has {len(protos)} — silent row skipping detected")
    return embedded_count


def run(manifest: ArtifactManifest, corpus_path: str, cache_path: str, out_report_path: str, log=print) -> dict:
    verify_manifest_matches_files(manifest)
    corpus = load_corpus(corpus_path)
    dataset_rev = dataset_revision(corpus_path)

    raw_embed_fn = real_embedder(manifest)
    assert_production_ready(raw_embed_fn, None)

    model_id = model_identity(manifest)
    cache = EmbeddingCache(cache_path, model_id=model_id, contract_version=CONTRACT_VERSION)
    if len(cache) > 0:
        assert_production_ready(raw_embed_fn, cache)
    embed_fn = cached_embedder(raw_embed_fn, cache)

    counts = {}
    for split in ("train", "validation", "test"):
        log(f"[generate_embeddings] embedding split={split!r} ...")
        counts[split] = generate_split_embeddings(corpus, split, embed_fn, cache, log=log)

    log(f"[generate_embeddings] BLIND split ({len(corpus.blind())} examples) — NOT embedded, per §13.")

    report = {
        "step": "REAL_EMBEDDING_GENERATION",
        "status": "PASS",
        "datasetRevision": dataset_rev,
        "modelSha256": manifest.modelSha256,
        "tokenizerSha256": manifest.tokenizerSha256,
        "backend": raw_embed_fn.backend,
        "counts": counts,
        "blindCount": len(corpus.blind()),
        "blindEmbedded": False,
        "cacheEntries": len(cache),
        "cachePath": cache_path,
    }
    with open(out_report_path, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2)
    return report


if __name__ == "__main__":
    import argparse

    from artifact_manifest import load_manifest
    from dataset import TRAINING_CORPUS_PATH

    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", default="artifact_manifest.json")
    parser.add_argument("--corpus", default=TRAINING_CORPUS_PATH)
    parser.add_argument("--cache", default="embedding_cache.real.json")
    parser.add_argument("--out", default="embedding_generation_report.json")
    args = parser.parse_args()

    m = load_manifest(args.manifest)
    try:
        report = run(m, args.corpus, args.cache, args.out)
        print(f"REAL EMBEDDING GENERATION = PASS — wrote {args.out}")
        print(json.dumps(report["counts"], indent=2))
    except Exception as e:
        print(f"REAL EMBEDDING GENERATION = FAIL: {e.__class__.__name__}: {e}", file=sys.stderr)
        raise SystemExit(1)
