"""§ JARVIS Implementation Master Plan — PASSAGGIO 14B §6/§12. ENCODER
QUALIFICATION GATE — loads the REAL EmbeddingGemma `.tflite` artifact via
the LiteRT Python runtime (`ai-edge-litert`, the same LiteRT family as the
Android app's `com.google.ai.edge.litert:litert` dependency — see
`app/llm/EmbeddingGemmaEngine.kt`) and proves, against the fixed
[GOLDEN_TEXTS] corpus + the REAL tokenizer already validated by
`tokenizer_qualification.py`, that the encoder produces sane, deterministic,
correctly-shaped output.

Backend choice, and why: `ai-edge-litert` is the exact runtime family
Android uses (`org.tensorflow.lite.Interpreter`'s Python-side LiteRT
sibling), so a `.tflite` file hashed once (`modelSha256`) and qualified here
is the SAME artifact identity Android will load — never a different
PyTorch/`sentence-transformers` checkpoint that could silently diverge
numerically (different precision/graph) from what actually runs on-device.
`embed.py`'s `real_embedder()` implements the identical tokenize -> pad/mask
-> forward -> pool -> normalize pipeline [EmbeddingGemmaEngine.kt] runs, so
the SAME function this module qualifies is the one `generate_embeddings.py`
uses for real training embeddings.

**Onestà**: `ai-edge-litert`'s public API (`Interpreter`, `get_input_details`/
`get_output_details`/`allocate_tensors`/`set_tensor`/`invoke`/`get_tensor`)
was installed and inspected for real in this session (pip reachable even
though huggingface.co/dl.google.com are not) and mirrors the long-stable
standard TFLite Python interpreter interface — but it has never been run
against a real `.tflite` model file here (none available, network-blocked).
First real execution is the user's own PC run.

ANDROID CROSS-RUNTIME ENCODER PARITY = PENDING, always, from this module —
this environment has no Android device/emulator to compare against, and this
gate proves only that the Python-side LiteRT forward pass is sane, not that
it numerically matches [EmbeddingGemmaEngine.kt]'s own forward pass bit-for-
bit (both use the SAME model+tokenizer file and the SAME LiteRT family, so
divergence would indicate a real bug, but this module cannot itself execute
on Android to prove equality — never faked as PASS here).
"""
from __future__ import annotations

import json

import numpy as np

from artifact_manifest import ArtifactManifest, verify_manifest_matches_files
from golden_qualification_corpus import GOLDEN_TEXTS, MAX_SEQUENCE_LENGTH


class EncoderGateError(RuntimeError):
    """§ §12 — raised (never downgraded to a warning) on ANY qualification failure."""


def _load_tflite_interpreter(model_path: str):
    try:
        from ai_edge_litert.interpreter import Interpreter
    except ImportError as e:
        raise EncoderGateError(
            "ai-edge-litert is not installed — install it with `pip install ai-edge-litert` "
            "before running the encoder qualification gate.",
        ) from e
    interpreter = Interpreter(model_path=model_path, num_threads=2)
    interpreter.allocate_tensors()
    return interpreter


def _validate_tensor_contract(interpreter) -> None:
    """Mirrors [EmbeddingGemmaEngine.kt]'s `validateTensorContract` exactly —
    the SAME shape assumptions, checked here on the Python side before any
    inference is attempted, so a bad/unexpected graph fails the gate instead
    of producing silently-wrong embeddings."""
    inputs = interpreter.get_input_details()
    if len(inputs) < 2:
        raise EncoderGateError(f"input_count={len(inputs)} expected>=2")
    for i in range(2):
        shape = inputs[i]["shape"]
        if len(shape) != 2:
            raise EncoderGateError(f"input[{i}]_rank={len(shape)} expected=2")
        dtype = inputs[i]["dtype"]
        if dtype != np.int32:
            raise EncoderGateError(f"input[{i}]_dtype={dtype} expected=int32")
    outputs = interpreter.get_output_details()
    if len(outputs) < 1:
        raise EncoderGateError(f"output_count={len(outputs)} expected>=1")
    out_shape = outputs[0]["shape"]
    if len(out_shape) not in (2, 3):
        raise EncoderGateError(f"output_rank={len(out_shape)} expected=2or3")
    if outputs[0]["dtype"] != np.float32:
        raise EncoderGateError(f"output_dtype={outputs[0]['dtype']} expected=float32")
    hidden = int(out_shape[-1])
    if hidden <= 0:
        raise EncoderGateError(f"output_hidden_dim={hidden} expected>0")


def _pool_and_normalize(raw_output: np.ndarray, attention_mask: np.ndarray) -> np.ndarray:
    """§ SemanticEncoderContract.CURRENT — MEAN_MASKED pooling (rank-3
    output) or model-native (rank-2, used as-is), then L2 normalization —
    the EXACT same two operations [EmbeddingGemmaEngine.kt]'s `pool()`/
    `EmbeddingMath.l2Normalize()` perform."""
    if raw_output.ndim == 2:
        pooled = raw_output[0]
    else:
        mask = attention_mask[0].astype(bool)
        real_tokens = raw_output[0][mask]
        pooled = real_tokens.mean(axis=0) if len(real_tokens) > 0 else np.zeros(raw_output.shape[-1], dtype=np.float32)
    norm = np.linalg.norm(pooled)
    return pooled / norm if norm > 0 else pooled


def run_encoder_qualification(
    manifest: ArtifactManifest, tokenizer_report_path: str, out_report_path: str,
) -> dict:
    verify_manifest_matches_files(manifest)
    with open(tokenizer_report_path, encoding="utf-8") as f:
        tokenizer_report = json.load(f)
    if tokenizer_report.get("status") != "PASS":
        raise EncoderGateError(
            "tokenizer_qualification_report shows status != PASS — the encoder gate refuses "
            "to run against an unqualified tokenizer (§11 must pass before §12).",
        )
    token_rows = {row["text"]: row for row in tokenizer_report["goldenVectors"]}

    interpreter = _load_tflite_interpreter(manifest.modelPath)
    _validate_tensor_contract(interpreter)

    input_details = interpreter.get_input_details()
    output_details = interpreter.get_output_details()
    embedding_dim: int | None = None
    rows = []
    for text in GOLDEN_TEXTS:
        if text not in token_rows:
            raise EncoderGateError(f"golden text {text!r} missing from tokenizer report — corpora out of sync")
        ids = np.array([token_rows[text]["tokenIds"]], dtype=np.int32)
        mask = np.array([token_rows[text]["attentionMask"]], dtype=np.int32)

        def _forward() -> np.ndarray:
            interpreter.set_tensor(input_details[0]["index"], ids)
            interpreter.set_tensor(input_details[1]["index"], mask)
            interpreter.invoke()
            return np.array(interpreter.get_tensor(output_details[0]["index"]))

        raw_1 = _forward()
        raw_2 = _forward()
        if not np.allclose(raw_1, raw_2, atol=1e-6):
            raise EncoderGateError(f"encoder produced DIFFERENT raw output for the same input on two consecutive calls (text={text!r}) — determinism check failed")

        pooled = _pool_and_normalize(raw_1, mask)
        if embedding_dim is None:
            embedding_dim = int(pooled.shape[-1])
        elif embedding_dim != int(pooled.shape[-1]):
            raise EncoderGateError(f"embedding dimension changed across golden examples: {embedding_dim} vs {pooled.shape[-1]}")

        # § Found by actually running this gate against a real (toy) .tflite
        # artifact in this session: an input with ZERO real (non-padding)
        # tokens — only possible here for "" and whitespace-only text, since
        # every other golden example is real words — has no defensible
        # non-zero pooled output under masked-mean pooling with no BOS/EOS/
        # task-prefix auto-added by the tokenizer (§H: taskPrefixVerified is
        # currently always false — the real convention is unverified). This
        # is a property of a zero-real-token INPUT, not an encoder
        # malfunction, so it must not silently pass the exact same "must be
        # non-zero" bar as real content — it is reported as
        # [structurallyEmptyInput] and EXCLUDED from the non-zero assertion,
        # while [finite] (never NaN/Inf, even for degenerate input) is still
        # enforced unconditionally.
        real_token_count = int(np.sum(mask))
        structurally_empty_input = real_token_count == 0

        finite = bool(np.all(np.isfinite(pooled)))
        nonzero = bool(np.any(pooled != 0))
        if not finite:
            raise EncoderGateError(f"embedding for text={text!r} contains NaN/Inf")
        if not nonzero and not structurally_empty_input:
            raise EncoderGateError(f"embedding for text={text!r} is entirely zero (degenerate output) despite {real_token_count} real token(s)")
        l2_norm = float(np.linalg.norm(pooled))

        rows.append({
            "text": text,
            "embeddingDimension": embedding_dim,
            "l2Norm": l2_norm,
            "finite": finite,
            "nonZero": nonzero,
            "realTokenCount": real_token_count,
            "structurallyEmptyInput": structurally_empty_input,
            "deterministic": True,
        })

    structurally_empty_count = sum(1 for r in rows if r["structurallyEmptyInput"])
    report = {
        "gate": "ENCODER_QUALIFICATION",
        "status": "PASS",
        "modelSha256": manifest.modelSha256,
        "embeddingDimension": embedding_dim,
        "poolingMode": "MEAN_MASKED" if len(output_details[0]["shape"]) == 3 else "MODEL_NATIVE",
        "embeddingNormalization": "L2",
        "determinismVerified": True,
        "structurallyEmptyInputCount": structurally_empty_count,
        "androidCrossRuntimeEncoderParity": "PENDING",
        "goldenVectors": rows,
    }
    with open(out_report_path, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2, ensure_ascii=False)
    return report


if __name__ == "__main__":
    import argparse

    from artifact_manifest import load_manifest

    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", default="artifact_manifest.json")
    parser.add_argument("--tokenizer-report", default="tokenizer_qualification_report.json")
    parser.add_argument("--out", default="encoder_qualification_report.json")
    args = parser.parse_args()

    m = load_manifest(args.manifest)
    try:
        report = run_encoder_qualification(m, args.tokenizer_report, args.out)
        print(f"ENCODER_GATE = PASS — wrote {args.out}")
        print(f"  embeddingDimension={report['embeddingDimension']}")
        print(f"  poolingMode={report['poolingMode']}")
        print("  ANDROID CROSS-RUNTIME ENCODER PARITY = PENDING (no device in this environment)")
    except EncoderGateError as e:
        print(f"ENCODER_GATE = FAIL: {e}")
        raise SystemExit(1)
