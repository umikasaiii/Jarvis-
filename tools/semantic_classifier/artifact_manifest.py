"""§ JARVIS Implementation Master Plan — PASSAGGIO 14B §9/§21. Machine-
readable provenance for the REAL EmbeddingGemma artifact (model + tokenizer)
the user acquires on their own PC — the single source of truth every later
gate (tokenizer qualification, encoder qualification, preflight, export)
reads instead of re-deriving or re-guessing the same facts.

Never committed with real file paths/hashes from a user's machine — this
module only reads/writes a manifest JSON that the user's own run produces
locally (see `.gitignore`'s new `tools/semantic_classifier/models/` rule).
"""
from __future__ import annotations

import hashlib
import json
import os
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone

MANIFEST_SCHEMA_VERSION = 1


def sha256_of_file(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


@dataclass
class ArtifactManifest:
    """§ §9 required fields, verbatim. [source] must name an official,
    publisher-controlled origin (§7) — never a mirror/reupload — recorded as
    free text by whoever ran acquisition, not validated against an allow-list
    here (this module has no network access to check a URL is really
    official; the human running the Windows script is the one who must have
    verified it, per §7's own instruction: 'do not use unknown mirrors')."""

    schemaVersion: int = MANIFEST_SCHEMA_VERSION
    modelName: str = ""
    officialSource: str = ""
    revision: str | None = None
    acquisitionIdentifier: str = ""
    artifactFormat: str = ""
    modelPath: str = ""
    tokenizerPath: str = ""
    modelSha256: str = ""
    tokenizerSha256: str = ""
    acquisitionTimestampIso: str = ""
    toolRuntimeVersion: str = ""
    encoderContractIdentity: dict = field(default_factory=dict)
    expectedEmbeddingDimension: int | None = None
    poolingMode: str = "MEAN_MASKED"
    normalization: str = "L2"
    taskPrefixRules: str | None = None

    def to_json(self) -> str:
        return json.dumps(asdict(self), indent=2, sort_keys=True)

    @staticmethod
    def from_json(text: str) -> "ArtifactManifest":
        raw = json.loads(text)
        return ArtifactManifest(**{k: raw.get(k, getattr(ArtifactManifest(), k)) for k in ArtifactManifest().__dict__})


def build_manifest(
    model_name: str,
    official_source: str,
    model_path: str,
    tokenizer_path: str,
    acquisition_identifier: str = "",
    revision: str | None = None,
    artifact_format: str = "tflite+sentencepiece",
    tool_runtime_version: str = "",
    expected_embedding_dimension: int | None = None,
) -> ArtifactManifest:
    """Hashes the REAL files on disk right now — never accepts a hash as an
    argument that wasn't just computed from the actual bytes (§K: 'model
    SHA-256' / 'tokenizer SHA-256' must always be real, never typed by
    hand)."""
    return ArtifactManifest(
        modelName=model_name,
        officialSource=official_source,
        revision=revision,
        acquisitionIdentifier=acquisition_identifier,
        artifactFormat=artifact_format,
        modelPath=os.path.abspath(model_path),
        tokenizerPath=os.path.abspath(tokenizer_path),
        modelSha256=sha256_of_file(model_path),
        tokenizerSha256=sha256_of_file(tokenizer_path),
        acquisitionTimestampIso=datetime.now(timezone.utc).isoformat(),
        toolRuntimeVersion=tool_runtime_version,
        expectedEmbeddingDimension=expected_embedding_dimension,
    )


def save_manifest(manifest: ArtifactManifest, path: str) -> None:
    with open(path, "w", encoding="utf-8") as f:
        f.write(manifest.to_json())


def load_manifest(path: str) -> ArtifactManifest:
    with open(path, encoding="utf-8") as f:
        return ArtifactManifest.from_json(f.read())


class ManifestMismatchError(RuntimeError):
    """§ §10 — raised (never silently tolerated) when the manifest's recorded
    hashes no longer match the real bytes on disk (file replaced/corrupted
    since acquisition)."""


def verify_manifest_matches_files(manifest: ArtifactManifest) -> None:
    if not os.path.isfile(manifest.modelPath):
        raise ManifestMismatchError(f"manifest model file missing: {manifest.modelPath}")
    if not os.path.isfile(manifest.tokenizerPath):
        raise ManifestMismatchError(f"manifest tokenizer file missing: {manifest.tokenizerPath}")
    real_model_sha = sha256_of_file(manifest.modelPath)
    if real_model_sha != manifest.modelSha256:
        raise ManifestMismatchError(
            f"model file at {manifest.modelPath} no longer matches manifest "
            f"(manifest={manifest.modelSha256}, real={real_model_sha}) — file changed since acquisition",
        )
    real_tokenizer_sha = sha256_of_file(manifest.tokenizerPath)
    if real_tokenizer_sha != manifest.tokenizerSha256:
        raise ManifestMismatchError(
            f"tokenizer file at {manifest.tokenizerPath} no longer matches manifest "
            f"(manifest={manifest.tokenizerSha256}, real={real_tokenizer_sha}) — file changed since acquisition",
        )


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="Build/verify an ArtifactManifest for a locally acquired EmbeddingGemma artifact.")
    parser.add_argument("--model-path", required=True)
    parser.add_argument("--tokenizer-path", required=True)
    parser.add_argument("--model-name", default="embeddinggemma-300m")
    parser.add_argument("--official-source", required=True, help="e.g. https://huggingface.co/litert-community/embeddinggemma-300m — must be the real page you downloaded from")
    parser.add_argument("--revision", default=None)
    parser.add_argument("--acquisition-identifier", default="")
    parser.add_argument("--out", default="artifact_manifest.json")
    parser.add_argument("--verify-only", action="store_true", help="verify an existing manifest at --out instead of building a new one")
    args = parser.parse_args()

    if args.verify_only:
        m = load_manifest(args.out)
        verify_manifest_matches_files(m)
        print(f"OK: manifest at {args.out} matches real files on disk")
    else:
        m = build_manifest(
            model_name=args.model_name, official_source=args.official_source,
            model_path=args.model_path, tokenizer_path=args.tokenizer_path,
            acquisition_identifier=args.acquisition_identifier, revision=args.revision,
        )
        save_manifest(m, args.out)
        print(f"wrote {args.out}")
        print(f"  modelSha256={m.modelSha256}")
        print(f"  tokenizerSha256={m.tokenizerSha256}")
