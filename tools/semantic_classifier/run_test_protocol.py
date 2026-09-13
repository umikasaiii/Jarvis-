"""§ JARVIS Implementation Master Plan — PASSAGGIO 14B §17. THE ONE-TIME TEST
PROTOCOL — evaluates a frozen `head_weights.json` candidate (already trained
by `export.py --real`, model-selected on VALIDATION only) against the TEST
split EXACTLY ONCE.

§17 is explicit: "No tuning after inspecting TEST results. If results are
unacceptable: report FAIL. Do not quietly adjust and rerun TEST until it
looks better." This script enforces that mechanically, not just by
instruction: it REFUSES to overwrite an existing TEST report unless
`--force` is passed (and even then prints a loud warning naming the
previous run's timestamp) — a human re-running TEST after a bad result has
to make that choice explicit and visible, never accidental.

Required fields (§17, verbatim) are ALL present in the output report:
classifier, qualification, fallbackUsed, syntheticEmbedder, realCache,
datasetRevision, encoderIdentity, tokenizerIdentity, headArtifactSHA,
testExampleCount.
"""
from __future__ import annotations

import hashlib
import json
import os
import sys
from datetime import datetime, timezone

from artifact_manifest import load_manifest, verify_manifest_matches_files
from dataset import dataset_revision, load_corpus
from embed import CONTRACT_VERSION, EmbeddingCache, cached_embedder, real_embedder
from production_gate import assert_cache_production_ready, assert_embedder_production_ready
from report import LearnedHeadUnavailableError, build_report


def run(
    manifest_path: str, corpus_path: str, cache_path: str, thresholds_path: str,
    head_weights_path: str, out_report_path: str, force: bool = False,
) -> dict:
    if os.path.isfile(out_report_path) and not force:
        with open(out_report_path, encoding="utf-8") as f:
            previous = json.load(f)
        raise RuntimeError(
            f"a TEST report already exists at {out_report_path!r} (produced {previous.get('evaluatedAtIso', 'unknown time')}) "
            "— §17 forbids re-running TEST after inspecting a result. Pass force=True only if you are "
            "deliberately re-evaluating a DIFFERENT frozen candidate (e.g. after fixing a real bug and "
            "re-training), never to retry the same candidate hoping for a better number.",
        )

    manifest = load_manifest(manifest_path)
    verify_manifest_matches_files(manifest)
    corpus = load_corpus(corpus_path)
    dataset_rev = dataset_revision(corpus_path)

    with open(head_weights_path, encoding="utf-8") as f:
        head_raw = f.read()
    head_export = json.loads(head_raw)
    head_sha = hashlib.sha256(head_raw.encode("utf-8")).hexdigest()

    with open(thresholds_path, encoding="utf-8") as f:
        thresholds = json.load(f)

    raw_embed_fn = real_embedder(manifest)
    assert_embedder_production_ready(raw_embed_fn)  # never SYNTHETIC_SELFTEST reaching TEST
    model_id = f"{manifest.modelName}:{manifest.modelSha256[:16]}:{manifest.tokenizerSha256[:16]}"
    cache = EmbeddingCache(cache_path, model_id=model_id, contract_version=CONTRACT_VERSION)
    real_cache = len(cache) > 0 and cache.qualification == "REAL"
    if len(cache) > 0:
        assert_cache_production_ready(cache)  # raises if the cache is stale/synthetic — never silently reused
    embed_fn = cached_embedder(raw_embed_fn, cache)

    try:
        metrics = build_report(
            corpus, "learned_head", embed_fn, thresholds, split="test",
            head_weights_path=head_weights_path,
            expected_contract_version=head_export.get("encoderContract", {}).get("contractVersion"),
            allowed_qualifications=("REAL_TRAINED",),
        )
    except LearnedHeadUnavailableError as e:
        raise RuntimeError(f"TEST protocol cannot run: {e}") from e
    finally:
        cache.save()

    contract = head_export.get("encoderContract") or {}
    report = {
        "step": "ONE_TIME_TEST_PROTOCOL",
        "evaluatedAtIso": datetime.now(timezone.utc).isoformat(),
        "classifier": metrics["classifier_type"],
        "qualification": head_export.get("artifactQualification"),
        "fallbackUsed": metrics["fallback_occurred"],
        "syntheticEmbedder": raw_embed_fn.qualification != "REAL",
        "realCache": real_cache,
        "datasetRevision": dataset_rev,
        "encoderIdentity": contract.get("modelSha256"),
        "tokenizerIdentity": contract.get("tokenizerSha256"),
        "headArtifactSHA": head_sha,
        "testExampleCount": metrics["n_samples"],
        "metrics": metrics,
    }
    with open(out_report_path, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2)
    return report


if __name__ == "__main__":
    import argparse

    from dataset import TRAINING_CORPUS_PATH

    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", default="artifact_manifest.json")
    parser.add_argument("--corpus", default=TRAINING_CORPUS_PATH)
    parser.add_argument("--cache", default="embedding_cache.real.json")
    parser.add_argument("--thresholds", default="thresholds.json")
    parser.add_argument("--head-weights", default="head_weights.json")
    parser.add_argument("--out", default="test_protocol_report.json")
    parser.add_argument("--force", action="store_true", help="re-run TEST on a DIFFERENT candidate only — never to retry the same one")
    args = parser.parse_args()

    try:
        report = run(args.manifest, args.corpus, args.cache, args.thresholds, args.head_weights, args.out, force=args.force)
        print(f"TEST protocol complete — wrote {args.out}")
        print(json.dumps({k: v for k, v in report.items() if k != "metrics"}, indent=2))
    except (RuntimeError, FileNotFoundError) as e:
        print(f"TEST protocol FAILED: {e}", file=sys.stderr)
        raise SystemExit(1)
