"""§ JARVIS Implementation Master Plan — PASSAGGIO 14B §20. IMPORT /
CANDIDATE VERIFIER — the last check before a `head_weights.json` candidate
is copied anywhere near the app (e.g. `app/src/main/assets/semantic/` for a
future shadow/debug comparison — never as PRIMARY, see below). Rejects the
candidate if ANY of the identity fields §20 lists differ from what the
caller expects: dataset revision, model SHA, tokenizer SHA, contract
identity, embedding dim, label layout, artifact SHA, schema/version.

**§20's other requirement, already true by construction, not re-implemented
here**: "Importing a candidate must NOT automatically make it authoritative
production semantic behavior." `ArtifactQualification.REAL_TRAINED` (the
only qualification this whole PASSAGGIO 14B pipeline can ever produce) is
never `.productionEligible` in the Kotlin runtime
(`LearnedHeadExport.isCompatibleWithRuntime()`, PASSAGGIO 13/14) — a
candidate that passes THIS verifier is still non-authoritative the moment
Android loads it, with no code change needed here or there. This script
only prevents copying a WRONG or MISMATCHED file in the first place.
"""
from __future__ import annotations

import hashlib
import json


class CandidateVerificationError(RuntimeError):
    """Raised (never downgraded to a warning) when the candidate does not
    match the expected identity — the caller must not import/copy the file."""


def verify_candidate(head_weights_path: str, expected: dict) -> dict:
    """[expected] may carry any subset of: `datasetRevision`, `modelSha256`,
    `tokenizerSha256`, `contractVersion`, `embeddingDim`, `intentLabels`,
    `domainLabels`, `schemaVersion`, `artifactSha256` (the SHA-256 of the
    `head_weights.json` file itself, if the caller wants to pin an EXACT
    byte-identical artifact rather than just its declared identity fields).
    Any key present in [expected] but absent/different in the real file
    raises [CandidateVerificationError] — a key simply not present in
    [expected] is not checked (the caller decides how strict to be).

    Returns the real, freshly-read facts about the candidate on success —
    never a placeholder — so the caller has a fully-verified record to log.
    """
    with open(head_weights_path, encoding="utf-8") as f:
        raw = f.read()
    export = json.loads(raw)
    real_sha256 = hashlib.sha256(raw.encode("utf-8")).hexdigest()
    contract = export.get("encoderContract") or {}

    real_facts = {
        "artifactSha256": real_sha256,
        "schemaVersion": export.get("schemaVersion"),
        "artifactQualification": export.get("artifactQualification"),
        "calibrationStatus": export.get("calibrationStatus"),
        "datasetRevision": export.get("datasetRevision"),
        "modelSha256": contract.get("modelSha256"),
        "tokenizerSha256": contract.get("tokenizerSha256"),
        "contractVersion": contract.get("contractVersion"),
        "embeddingDim": export.get("embeddingDim"),
        "intentLabels": export.get("intent", {}).get("labels"),
        "domainLabels": export.get("domain", {}).get("labels"),
    }

    mismatches = []
    for key, expected_value in expected.items():
        real_value = real_facts.get(key)
        if real_value != expected_value:
            mismatches.append(f"{key}: expected={expected_value!r} real={real_value!r}")

    if mismatches:
        raise CandidateVerificationError(
            f"candidate at {head_weights_path!r} does not match the expected identity — refusing to import:\n"
            + "\n".join(f"  - {m}" for m in mismatches),
        )

    return real_facts


def expected_from_manifest_and_dataset(manifest, tokenizer_report_path: str, corpus_path: str) -> dict:
    """Convenience builder — §20's most common use case: verify a just-
    produced candidate against the SAME manifest/dataset it claims to have
    been trained under (self-consistency, catches "copied the wrong file"
    human error), not against some other external reference."""
    from dataset import dataset_revision

    with open(tokenizer_report_path, encoding="utf-8") as f:
        tokenizer_report = json.load(f)
    return {
        "modelSha256": manifest.modelSha256,
        "tokenizerSha256": manifest.tokenizerSha256,
        "datasetRevision": dataset_revision(corpus_path),
        "contractVersion": tokenizer_report.get("contractVersion", 1) if "contractVersion" in tokenizer_report else 1,
    }


if __name__ == "__main__":
    import argparse

    from artifact_manifest import load_manifest
    from dataset import TRAINING_CORPUS_PATH

    parser = argparse.ArgumentParser()
    parser.add_argument("--head-weights", required=True)
    parser.add_argument("--manifest", default="artifact_manifest.json")
    parser.add_argument("--tokenizer-report", default="tokenizer_qualification_report.json")
    parser.add_argument("--corpus", default=TRAINING_CORPUS_PATH)
    args = parser.parse_args()

    m = load_manifest(args.manifest)
    expected = expected_from_manifest_and_dataset(m, args.tokenizer_report, args.corpus)
    try:
        facts = verify_candidate(args.head_weights, expected)
        print("CANDIDATE VERIFICATION = PASS")
        print(json.dumps(facts, indent=2))
        print(
            f"\nNOTE: artifactQualification={facts['artifactQualification']!r} — per §20/§N (Kotlin "
            "LearnedHeadExport.isCompatibleWithRuntime), this candidate remains NON-AUTHORITATIVE "
            "in the Android runtime regardless of this verification, until a future PASSAGGIO 15 "
            "calibration pass re-exports it as PRODUCTION_ELIGIBLE.",
        )
    except CandidateVerificationError as e:
        print(f"CANDIDATE VERIFICATION = FAIL: {e}")
        raise SystemExit(1)
