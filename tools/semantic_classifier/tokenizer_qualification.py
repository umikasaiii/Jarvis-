"""§ JARVIS Implementation Master Plan — PASSAGGIO 14B §6/§11. TOKENIZER
QUALIFICATION GATE — loads the REAL tokenizer artifact the user acquired
(recorded in an `ArtifactManifest`) and proves, against the fixed
[GOLDEN_TEXTS] corpus, exactly what it does: real token ids, real special
tokens, real determinism — never assumed.

§6 is explicit: "Do not assume SentencePiece merely because an old commit
message mentioned it." This module never hardcodes an expected tokenizer
format — it INTROSPECTS the real loaded artifact (via the `sentencepiece`
library's own `ModelProto`, which reports its own `trainer_spec.model_type`)
and reports whatever that value actually is. `TOKENIZER_GATE = FAIL` (never
a guessed PASS) if the file cannot be loaded, or if determinism fails.

Dependency: `sentencepiece` (pure C++ extension, pip-installable, no GPU/
Conda/network needed beyond the initial `pip install` — this project has
never had network access to actually run `pip install sentencepiece` or
load a real `.model` file in this session; the API used below
(`sentencepiece.SentencePieceProcessor`) is long-stable, widely-published
public API, but has not been executed against a real artifact here. First
real execution is the user's own PC run.
"""
from __future__ import annotations

import json

from artifact_manifest import ArtifactManifest, verify_manifest_matches_files
from golden_qualification_corpus import GOLDEN_TEXTS, MAX_SEQUENCE_LENGTH


class TokenizerGateError(RuntimeError):
    """§ §11 — raised (never downgraded to a warning) on ANY mismatch. The
    caller (preflight.py / run_real_training.ps1) must stop before training."""


def _load_sentencepiece(tokenizer_path: str):
    try:
        import sentencepiece as spm
    except ImportError as e:
        raise TokenizerGateError(
            "sentencepiece is not installed — install it with `pip install sentencepiece` "
            "before running the tokenizer qualification gate. (Never silently falls back to "
            "a substitute tokenizer.)",
        ) from e
    sp = spm.SentencePieceProcessor()
    ok = sp.Load(tokenizer_path)
    if not ok:
        raise TokenizerGateError(f"sentencepiece failed to load tokenizer file at {tokenizer_path!r}")
    return sp


def _introspect_model_type(sp) -> str:
    """Reads the REAL `trainer_spec.model_type` out of the loaded proto —
    never assumed. `sentencepiece`'s `SentencePieceProcessor` does not
    expose the proto directly in its stable Python API in every version; if
    it cannot be read, this returns the honest string 'UNKNOWN_UNINTROSPECTABLE'
    rather than guessing 'unigram'/'bpe'."""
    try:
        raw = sp.serialized_model_proto()
        import sentencepiece.sentencepiece_model_pb2 as model_pb2

        proto = model_pb2.ModelProto()
        proto.ParseFromString(raw)
        model_type = model_pb2.TrainerSpec.ModelType.Name(proto.trainer_spec.model_type)
        return model_type  # e.g. "UNIGRAM", "BPE", "WORD", "CHAR"
    except Exception as e:
        return f"UNKNOWN_UNINTROSPECTABLE({e.__class__.__name__})"


def _special_ids(sp) -> dict:
    def _safe(fn):
        try:
            v = fn()
            return int(v) if v is not None and v >= 0 else None
        except Exception:
            return None

    return {
        "bosTokenId": _safe(sp.bos_id),
        "eosTokenId": _safe(sp.eos_id),
        "padTokenId": _safe(sp.pad_id),
        "unkTokenId": _safe(sp.unk_id),
        "vocabSize": _safe(sp.get_piece_size),
    }


def _encode_padded(sp, text: str, max_len: int) -> tuple[list[int], list[int]]:
    """Mirrors the padding/truncation policy [SemanticEncoderContract.CURRENT]
    declares (RIGHT truncation, RIGHT padding, pad id 0 if the tokenizer's
    real pad id is unknown/absent) — the SAME shape [EmbeddingGemmaEngine.kt]
    expects (`IntArray(SEQUENCE_LENGTH)`, attention mask derived from
    non-zero ids)."""
    ids = sp.EncodeAsIds(text)
    pad_id = sp.pad_id() if sp.pad_id() >= 0 else 0
    if len(ids) > max_len:
        ids = ids[:max_len]
    mask = [1] * len(ids) + [0] * (max_len - len(ids))
    ids = ids + [pad_id] * (max_len - len(ids))
    return ids, mask


def run_tokenizer_qualification(manifest: ArtifactManifest, out_report_path: str) -> dict:
    """Runs the full gate. Returns the report dict (also written to
    [out_report_path]) and raises [TokenizerGateError] on any hard failure —
    never returns a report claiming PASS while an actual mismatch occurred.
    """
    verify_manifest_matches_files(manifest)
    sp = _load_sentencepiece(manifest.tokenizerPath)

    model_type = _introspect_model_type(sp)
    special_ids = _special_ids(sp)

    rows = []
    for text in GOLDEN_TEXTS:
        ids_1, mask_1 = _encode_padded(sp, text, MAX_SEQUENCE_LENGTH)
        ids_2, mask_2 = _encode_padded(sp, text, MAX_SEQUENCE_LENGTH)
        deterministic = ids_1 == ids_2 and mask_1 == mask_2
        if not deterministic:
            raise TokenizerGateError(
                f"tokenizer produced DIFFERENT ids for the same input text on two consecutive "
                f"calls — determinism check failed for text={text!r}. TOKENIZER_GATE = FAIL.",
            )
        rows.append({
            "text": text,
            "tokenIds": ids_1,
            "attentionMask": mask_1,
            "realTokenCount": sum(mask_1),
            "truncated": len(sp.EncodeAsIds(text)) > MAX_SEQUENCE_LENGTH,
        })

    report = {
        "gate": "TOKENIZER_QUALIFICATION",
        "status": "PASS",
        "tokenizerSha256": manifest.tokenizerSha256,
        # § §6 — REPORTED, never assumed: whatever the real artifact's own proto says.
        "introspectedModelType": model_type,
        "specialTokenIds": special_ids,
        "maxSequenceLength": MAX_SEQUENCE_LENGTH,
        "paddingSide": "RIGHT",
        "truncationSide": "RIGHT",
        "determinismVerified": True,
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
    parser.add_argument("--out", default="tokenizer_qualification_report.json")
    args = parser.parse_args()

    m = load_manifest(args.manifest)
    try:
        report = run_tokenizer_qualification(m, args.out)
        print(f"TOKENIZER_GATE = PASS — wrote {args.out}")
        print(f"  introspectedModelType={report['introspectedModelType']}")
        print(f"  specialTokenIds={report['specialTokenIds']}")
    except TokenizerGateError as e:
        print(f"TOKENIZER_GATE = FAIL: {e}")
        raise SystemExit(1)
