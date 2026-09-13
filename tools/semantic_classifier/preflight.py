"""§ JARVIS Implementation Master Plan — PASSAGGIO 14B §10. FAIL-CLOSED
PREFLIGHT — every check §10 lists, run BEFORE any expensive real embedding
generation or training starts. A single failure anywhere stops the whole
run (never a partial/soft-continue) — `run_real_training.ps1` calls this
script first and aborts on a non-zero exit code.

Deliberately a thin orchestrator over checks that mostly already exist
elsewhere in this package (`artifact_manifest.verify_manifest_matches_files`,
`dataset.dataset_revision`, `production_gate.assert_production_ready`'s
spirit) — this module does not reimplement them, it sequences them and adds
the few checks (disk space, dependency presence, output path writability)
that don't have a home yet.
"""
from __future__ import annotations

import importlib.util
import json
import os
import shutil
import sys

from artifact_manifest import ManifestMismatchError, load_manifest, verify_manifest_matches_files
from dataset import TRAINING_CORPUS_PATH, dataset_revision, load_corpus

REQUIRED_FREE_DISK_BYTES = 2 * 1024 * 1024 * 1024  # 2 GB — conservative headroom for embedding chunks + head export on an 8 GB laptop
REQUIRED_MODULES = ("numpy", "sklearn", "sentencepiece", "ai_edge_litert")


class PreflightError(RuntimeError):
    """Raised on ANY failed check — never caught and downgraded to a warning."""


def check_dataset(expected_revision: str | None = None) -> dict:
    if not os.path.isfile(TRAINING_CORPUS_PATH):
        raise PreflightError(f"training_corpus.json not found at {TRAINING_CORPUS_PATH}")
    corpus = load_corpus(TRAINING_CORPUS_PATH)
    revision = dataset_revision(TRAINING_CORPUS_PATH)
    if expected_revision is not None and revision != expected_revision:
        raise PreflightError(f"dataset revision mismatch: found {revision}, expected {expected_revision}")
    if not corpus.train() or not corpus.validation() or not corpus.test():
        raise PreflightError("dataset is missing a required split (train/validation/test)")
    return {
        "datasetRevision": revision,
        "trainCount": len(corpus.train()),
        "validationCount": len(corpus.validation()),
        "testCount": len(corpus.test()),
        "blindCount": len(corpus.blind()),
    }


def check_manifest(manifest_path: str) -> dict:
    if not os.path.isfile(manifest_path):
        raise PreflightError(f"artifact manifest not found at {manifest_path} — run artifact_manifest.py first")
    manifest = load_manifest(manifest_path)
    try:
        verify_manifest_matches_files(manifest)
    except ManifestMismatchError as e:
        raise PreflightError(str(e)) from e
    if not manifest.officialSource or manifest.officialSource.strip() == "":
        raise PreflightError("manifest.officialSource is empty — §7 requires recording where the artifact came from")
    return {"modelSha256": manifest.modelSha256, "tokenizerSha256": manifest.tokenizerSha256, "officialSource": manifest.officialSource}


def check_qualification_reports(tokenizer_report_path: str, encoder_report_path: str) -> dict:
    for path, gate_name in ((tokenizer_report_path, "TOKENIZER_QUALIFICATION"), (encoder_report_path, "ENCODER_QUALIFICATION")):
        if not os.path.isfile(path):
            raise PreflightError(f"{gate_name} report not found at {path} — run the corresponding qualification gate first")
        with open(path, encoding="utf-8") as f:
            report = json.load(f)
        if report.get("gate") != gate_name or report.get("status") != "PASS":
            raise PreflightError(f"{gate_name} report at {path} does not show status=PASS")
    return {"tokenizerGate": "PASS", "encoderGate": "PASS"}


def check_dependencies() -> dict:
    missing = [m for m in REQUIRED_MODULES if importlib.util.find_spec(m) is None]
    if missing:
        raise PreflightError(f"missing required Python packages: {missing} — `pip install {' '.join(missing)}`")
    return {"modules": list(REQUIRED_MODULES)}


def check_disk_space(path: str) -> dict:
    usage = shutil.disk_usage(os.path.dirname(os.path.abspath(path)) or ".")
    if usage.free < REQUIRED_FREE_DISK_BYTES:
        raise PreflightError(
            f"only {usage.free / (1 << 30):.2f} GB free at {path!r}, need at least "
            f"{REQUIRED_FREE_DISK_BYTES / (1 << 30):.0f} GB",
        )
    return {"freeBytes": usage.free}


def check_writable(path: str) -> dict:
    os.makedirs(path, exist_ok=True)
    probe = os.path.join(path, ".preflight_write_probe")
    try:
        with open(probe, "w", encoding="utf-8") as f:
            f.write("ok")
    except OSError as e:
        raise PreflightError(f"output/cache path {path!r} is not writable: {e}") from e
    finally:
        if os.path.exists(probe):
            os.remove(probe)
    return {"path": os.path.abspath(path)}


def check_no_synthetic_leakage(embedding_cache_path: str | None) -> dict:
    """§ §10 'synthetic embedder disabled / prototype fallback disabled' — if
    an embedding cache already exists at the target path, it must be tagged
    REAL (never SYNTHETIC_SELFTEST/UNKNOWN), or a real run would silently
    reuse fake vectors. `production_gate.assert_cache_production_ready`
    (already strict) is reused here, not reimplemented."""
    if embedding_cache_path is None or not os.path.isfile(embedding_cache_path):
        return {"cache": "none (will be created fresh)"}
    from embed import CONTRACT_VERSION, EmbeddingCache
    from production_gate import assert_cache_production_ready

    cache = EmbeddingCache(embedding_cache_path, model_id="__preflight_probe__", contract_version=CONTRACT_VERSION)
    if len(cache) > 0:
        assert_cache_production_ready(cache)  # raises ProductionGateError (a RuntimeError) on any mismatch
    return {"cache": embedding_cache_path, "qualification": cache.qualification, "entries": len(cache)}


def run_preflight(
    manifest_path: str, tokenizer_report_path: str, encoder_report_path: str,
    output_dir: str, embedding_cache_path: str | None = None,
) -> dict:
    results = {
        "dataset": check_dataset(),
        "manifest": check_manifest(manifest_path),
        "qualification": check_qualification_reports(tokenizer_report_path, encoder_report_path),
        "dependencies": check_dependencies(),
        "diskSpace": check_disk_space(output_dir),
        "writable": check_writable(output_dir),
        "syntheticLeakage": check_no_synthetic_leakage(embedding_cache_path),
    }
    results["status"] = "PASS"
    return results


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", default="artifact_manifest.json")
    parser.add_argument("--tokenizer-report", default="tokenizer_qualification_report.json")
    parser.add_argument("--encoder-report", default="encoder_qualification_report.json")
    parser.add_argument("--output-dir", default="real_run_output")
    parser.add_argument("--embedding-cache", default=None)
    args = parser.parse_args()

    try:
        results = run_preflight(
            args.manifest, args.tokenizer_report, args.encoder_report,
            args.output_dir, args.embedding_cache,
        )
        print("PREFLIGHT = PASS")
        print(json.dumps(results, indent=2))
    except PreflightError as e:
        print(f"PREFLIGHT = FAIL: {e}", file=sys.stderr)
        raise SystemExit(1)
