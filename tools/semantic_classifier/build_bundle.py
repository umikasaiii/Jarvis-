"""§ JARVIS Implementation Master Plan — PASSAGGIO 14B §19. VERIFICATION
BUNDLE — gathers every small report/metadata artifact this pipeline
produces into one directory + a manifest listing what's inside, so the
whole real-execution run can be inspected (or handed to someone else) as a
single unit. Deliberately excludes the real model `.tflite`/tokenizer files
themselves (large, and already provenance-recorded by SHA-256 in the
manifest — re-copying them would just duplicate gigabytes for no benefit);
`head_weights.json` IS included (it is small — a few KB to low MB depending
on hidden layer size — and it is the actual deliverable of this whole pass).
"""
from __future__ import annotations

import json
import os
import shutil

BUNDLE_FILES = [
    "artifact_manifest.json",
    "tokenizer_qualification_report.json",
    "encoder_qualification_report.json",
    "dataset_report.json",
    "embedding_generation_report.json",
    "thresholds.json",
    "head_weights.json",
    "test_protocol_report.json",
]


def build_bundle(source_dir: str, bundle_dir: str, log=print) -> dict:
    os.makedirs(bundle_dir, exist_ok=True)
    included, missing = [], []
    for name in BUNDLE_FILES:
        src = os.path.join(source_dir, name)
        if os.path.isfile(src):
            shutil.copy2(src, os.path.join(bundle_dir, name))
            included.append(name)
            log(f"  bundled {name}")
        else:
            missing.append(name)
            log(f"  MISSING (not bundled): {name}")

    manifest = {
        "bundle": "PASSAGGIO_14B_VERIFICATION_BUNDLE",
        "includedFiles": included,
        "missingFiles": missing,
        "note": "Real model/tokenizer artifact files are NOT included here — see artifact_manifest.json's "
                "recorded SHA-256 hashes for provenance instead of re-copying large binaries.",
    }
    with open(os.path.join(bundle_dir, "bundle_manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2)
    return manifest


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("--source-dir", default=".")
    parser.add_argument("--bundle-dir", default="verification_bundle")
    args = parser.parse_args()

    manifest = build_bundle(args.source_dir, args.bundle_dir)
    print(f"wrote {args.bundle_dir}/bundle_manifest.json")
    if manifest["missingFiles"]:
        print(f"WARNING: {len(manifest['missingFiles'])} expected file(s) were missing: {manifest['missingFiles']}")
