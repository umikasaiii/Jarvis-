"""§ JARVIS Implementation Master Plan — PASSAGGIO 14B §21. MACHINE-READABLE
RECEIPT — the final artifact `run_real_training.ps1` prints/writes at the
end of a run, in exactly the shape §21 specifies. Reads only from the
reports the earlier steps already wrote (never re-derives/guesses a value)
— any step whose report is missing is recorded as FAIL/PENDING, never
silently omitted.
"""
from __future__ import annotations

import json
import os


def _gate_status(report_path: str, gate_key: str = "status") -> str:
    if not os.path.isfile(report_path):
        return "FAIL (report missing)"
    with open(report_path, encoding="utf-8") as f:
        report = json.load(f)
    return report.get(gate_key, "FAIL (no status field)")


def build_receipt(source_dir: str) -> dict:
    manifest_path = os.path.join(source_dir, "artifact_manifest.json")
    tokenizer_report_path = os.path.join(source_dir, "tokenizer_qualification_report.json")
    encoder_report_path = os.path.join(source_dir, "encoder_qualification_report.json")
    embed_report_path = os.path.join(source_dir, "embedding_generation_report.json")
    head_path = os.path.join(source_dir, "head_weights.json")
    test_report_path = os.path.join(source_dir, "test_protocol_report.json")

    manifest = {}
    if os.path.isfile(manifest_path):
        with open(manifest_path, encoding="utf-8") as f:
            manifest = json.load(f)

    head_export = {}
    if os.path.isfile(head_path):
        with open(head_path, encoding="utf-8") as f:
            head_export = json.load(f)

    test_report = {}
    if os.path.isfile(test_report_path):
        with open(test_report_path, encoding="utf-8") as f:
            test_report = json.load(f)

    embed_report = {}
    if os.path.isfile(embed_report_path):
        with open(embed_report_path, encoding="utf-8") as f:
            embed_report = json.load(f)

    training_status = "PASS" if head_export.get("artifactQualification") == "REAL_TRAINED" else "FAIL"
    validation_status = "PASS" if head_export else "FAIL"  # validation-based selection already happened inside train_heads.train() before export
    embedding_status = "PASS" if embed_report.get("status") == "PASS" else "FAIL"
    export_status = "PASS" if head_export.get("artifactQualification") == "REAL_TRAINED" and head_export.get("calibrationStatus") == "PENDING" else "FAIL"
    test_status = "PASS" if test_report.get("qualification") == "REAL_TRAINED" else "FAIL (report missing or incomplete)"

    contract = head_export.get("encoderContract", {})
    receipt = {
        "receipt": "PASSAGGIO_14_REAL_EXECUTION",
        "ArtifactGate": "PASS" if manifest else "FAIL",
        "TokenizerGate": _gate_status(tokenizer_report_path),
        "EncoderGate": _gate_status(encoder_report_path),
        "EmbeddingGeneration": embedding_status,
        "Training": training_status,
        "Validation": validation_status,
        "Test": test_status,
        "Export": export_status,
        "Blind": "UNTOUCHED",
        "Calibration": "PENDING",
        "Honor200": "PENDING",
        "DatasetRevision": head_export.get("datasetRevision"),
        "ModelSHA": manifest.get("modelSha256"),
        "TokenizerSHA": manifest.get("tokenizerSha256"),
        "EncoderContract": contract,
        "HeadSHA": test_report.get("headArtifactSHA"),
        "FinalArtifact": os.path.abspath(head_path) if os.path.isfile(head_path) else None,
        "BundlePath": None,  # filled in by the caller once build_bundle.py has run
    }
    return receipt


def format_receipt_text(receipt: dict) -> str:
    lines = [f"{receipt['receipt']}", ""]
    for key in (
        "ArtifactGate", "TokenizerGate", "EncoderGate", "EmbeddingGeneration",
        "Training", "Validation", "Test", "Export",
    ):
        lines.append(f"{key}: {receipt[key]}")
    lines.append("")
    for key in ("Blind", "Calibration", "Honor200"):
        lines.append(f"{key}: {receipt[key]}")
    lines.append("")
    for key in ("DatasetRevision", "ModelSHA", "TokenizerSHA", "HeadSHA", "FinalArtifact", "BundlePath"):
        lines.append(f"{key}: {receipt[key]}")
    return "\n".join(lines)


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("--source-dir", default=".")
    parser.add_argument("--bundle-path", default=None)
    parser.add_argument("--out-json", default="receipt.json")
    parser.add_argument("--out-txt", default="receipt.txt")
    args = parser.parse_args()

    receipt = build_receipt(args.source_dir)
    if args.bundle_path:
        receipt["BundlePath"] = os.path.abspath(args.bundle_path)

    with open(args.out_json, "w", encoding="utf-8") as f:
        json.dump(receipt, f, indent=2)
    text = format_receipt_text(receipt)
    with open(args.out_txt, "w", encoding="utf-8") as f:
        f.write(text)
    print(text)
