"""§ FASE 2A.11 ADDENDUM §10 — exports the trained heads to the SAME small,
framework-free JSON matrix format the Kotlin `LearnedHeadClassifierEngine`
reads (`core/semantic/embedding/LearnedHeadClassifierEngine.kt`) — plain
weight/bias arrays, never a pickle/PyTorch/TFLite blob. A linear head is one
matmul; a small one-hidden-layer MLP is two matmuls with a ReLU between —
both trivial to execute as plain Kotlin `FloatArray`/`Array<FloatArray>`
operations, so there is no reason to pull in a second ML runtime (LiteRT/
ONNX) just to run a classifier this small. See `docs/` for the size/latency
comparison once real numbers exist.

## Binary-classifier normalization (important, easy to get wrong)

Both `sklearn.linear_model.LogisticRegression` and `sklearn.neural_network.
MLPClassifier` collapse a 2-class problem to a SINGLE output unit (sigmoid),
not the usual N-row/N-column softmax form — a real shape quirk that would
silently produce wrong probabilities if the Kotlin side assumed every
multiclass head has one row/column per class. This module normalizes every
multiclass head (intent/operation/referenceMode — never the domain head,
which is genuinely per-label independent sigmoid, not softmax) to always
carry >= 2 output rows/columns before export: for a binary head, class 0
gets an all-zero row/column (logit 0) and class 1 keeps the real learned
row/column — `softmax([0, z]) == [1-sigmoid(z), sigmoid(z)]`, so this is
exactly equivalent to the original sigmoid, not an approximation. The
Kotlin runtime therefore only ever needs ONE forward-pass implementation
(argmax over softmax(logits)), never a binary special case.
"""
from __future__ import annotations

import json
import os

import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.multiclass import OneVsRestClassifier
from sklearn.neural_network import MLPClassifier

from train_heads import ALL_DOMAINS

EXPORT_SCHEMA_VERSION = 1


def _normalize_multiclass_linear(model: LogisticRegression, n_classes: int) -> dict:
    coef, intercept = model.coef_, model.intercept_
    if n_classes == 2 and coef.shape[0] == 1:
        weight = np.vstack([np.zeros_like(coef[0]), coef[0]])
        bias = np.array([0.0, float(intercept[0])])
    else:
        weight, bias = coef, intercept
    return {"weight": weight.tolist(), "bias": bias.tolist()}


def _normalize_multiclass_mlp(model: MLPClassifier, n_classes: int) -> dict:
    w1, b1 = model.coefs_[0], model.intercepts_[0]
    w2, b2 = model.coefs_[-1], model.intercepts_[-1]
    if n_classes == 2 and w2.shape[1] == 1:
        w2 = np.concatenate([np.zeros((w2.shape[0], 1)), w2], axis=1)
        b2 = np.array([0.0, float(b2[0])])
    return {"w1": w1.tolist(), "b1": b1.tolist(), "w2": w2.tolist(), "b2": b2.tolist(), "activation": "relu"}


def export_multiclass_head(model, labels: list[str]) -> dict:
    n_classes = len(labels)
    if isinstance(model, LogisticRegression):
        return {"architecture": "linear", "labels": labels, "linear": _normalize_multiclass_linear(model, n_classes)}
    if isinstance(model, MLPClassifier):
        return {"architecture": "mlp", "labels": labels, "mlp": _normalize_multiclass_mlp(model, n_classes)}
    raise ValueError(f"unsupported multiclass model type {type(model)}")


def export_domain_head(model, labels: list[str], embed_dim: int) -> dict:
    """Domain classification is genuinely multi-label (§5 of the corpus
    spec) — each label gets its OWN independent sigmoid, never a softmax
    over labels, so there is no binary-collapse quirk to normalize here."""
    if isinstance(model, OneVsRestClassifier):
        weight, bias = [], []
        for est in model.estimators_:
            if hasattr(est, "coef_"):
                weight.append(est.coef_[0].tolist())
                bias.append(float(est.intercept_[0]))
            else:
                # a domain never positive in ANY training example fits
                # sklearn's _ConstantPredictor — exported as an always-off
                # unit (strongly negative bias, zero weight), never invented
                # as "always on".
                weight.append([0.0] * embed_dim)
                bias.append(-10.0)
        return {"architecture": "linear", "labels": labels, "linear": {"weight": weight, "bias": bias}}
    if isinstance(model, MLPClassifier):
        w1, b1 = model.coefs_[0], model.intercepts_[0]
        w2, b2 = model.coefs_[-1], model.intercepts_[-1]
        return {
            "architecture": "mlp", "labels": labels,
            "mlp": {"w1": w1.tolist(), "b1": b1.tolist(), "w2": w2.tolist(), "b2": b2.tolist(), "activation": "relu"},
        }
    raise ValueError(f"unsupported domain model type {type(model)}")


def export_thresholds(thresholds: dict, out_path: str) -> None:
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(thresholds, f, indent=2)


# § JARVIS Implementation Master Plan — PASSAGGIO 14B §6, tightening
# PASSAGGIO 13 §K/§N. Plain-dict mirror of `SemanticEncoderContract.kt`'s
# JSON shape — SAME field names, so `LearnedHeadExport.parseOrNull()`
# (Kotlin) decodes this `encoderContract` block directly.
#
# § §6's explicit instruction ("Do not assume SentencePiece merely because
# an old commit message mentioned it — tokenizerFormat remains UNVERIFIED
# until authoritative artifact metadata proves it") means this function
# must never HARDCODE "SENTENCEPIECE_UNIGRAM" for a real run the way
# PASSAGGIO 14 originally did — it now reads the REAL introspected value
# from `tokenizer_qualification.py`'s report (`introspectedModelType`,
# read directly off the loaded artifact's own proto, never guessed) and
# maps ONLY a genuinely-confirmed "UNIGRAM" to the matching Kotlin enum
# value. Any other real value (BPE/WORD/CHAR/an introspection failure) maps
# to `UNVERIFIED` — honest and conservative, since `SemanticEncoderContract`
# (Kotlin) has no enum value for those today; extending it is future work,
# not silently worked around here by mislabeling the real tokenizer.
_KNOWN_TOKENIZER_FORMAT_MAP = {"UNIGRAM": "SENTENCEPIECE_UNIGRAM"}


def build_encoder_contract(
    mode: str, embed_dim: int, model_sha256: str | None = None, tokenizer_sha256: str | None = None,
    tokenizer_report: dict | None = None, encoder_report: dict | None = None,
) -> dict:
    if mode == "fake" or tokenizer_report is None:
        return {
            "contractVersion": 1,
            "tokenizerFormat": "UNVERIFIED",
            "tokenizerSha256": tokenizer_sha256,
            "modelSha256": model_sha256,
            "unicodeNormalizationForm": "NFC",
            "collapseWhitespace": True,
            "trimText": True,
            "casingPolicy": "PRESERVE",
            "taskPrefix": None,
            "taskPrefixVerified": False,
            "bosTokenId": None,
            "eosTokenId": None,
            "padTokenId": None,
            "specialTokensVerified": False,
            "maxSequenceLength": 256,
            "paddingSide": "RIGHT",
            "truncationSide": "RIGHT",
            "poolingMode": "MEAN_MASKED",
            "embeddingNormalization": "L2",
            "embeddingDimension": embed_dim,
        }

    # § §6/§12 — real mode, sourced from the two qualification gate reports
    # that must already have PASSed (preflight.py enforces this before this
    # function is ever reached in a real run).
    introspected = tokenizer_report["introspectedModelType"]
    tokenizer_format = _KNOWN_TOKENIZER_FORMAT_MAP.get(introspected, "UNVERIFIED")
    special_ids = tokenizer_report["specialTokenIds"]
    return {
        "contractVersion": 1,
        "tokenizerFormat": tokenizer_format,
        "tokenizerSha256": tokenizer_sha256,
        "modelSha256": model_sha256,
        "unicodeNormalizationForm": "NFC",
        "collapseWhitespace": True,
        "trimText": True,
        "casingPolicy": "PRESERVE",
        "taskPrefix": None,
        "taskPrefixVerified": False,  # § still unverified — no real task-prefix requirement was inspected, even though the tokenizer itself now is
        "bosTokenId": special_ids.get("bosTokenId"),
        "eosTokenId": special_ids.get("eosTokenId"),
        "padTokenId": special_ids.get("padTokenId"),
        "specialTokensVerified": True,  # § read directly off the real loaded artifact by tokenizer_qualification.py
        "maxSequenceLength": tokenizer_report.get("maxSequenceLength", 256),
        "paddingSide": tokenizer_report.get("paddingSide", "RIGHT"),
        "truncationSide": tokenizer_report.get("truncationSide", "RIGHT"),
        "poolingMode": (encoder_report or {}).get("poolingMode", "MEAN_MASKED"),
        "embeddingNormalization": (encoder_report or {}).get("embeddingNormalization", "L2"),
        "embeddingDimension": (encoder_report or {}).get("embeddingDimension", embed_dim),
    }


def export_head_weights(
    heads, embed_dim: int, out_path: str, mode: str = "fake",
    model_sha256: str | None = None, tokenizer_sha256: str | None = None,
    dataset_revision: str | None = None, training_seed: int | None = None,
    trained_at_iso: str | None = None,
    tokenizer_report: dict | None = None, encoder_report: dict | None = None,
) -> dict:
    """Exports ALL trained heads (intent/domain/operation/referenceMode) in
    one file, `head_weights.json` — the format
    `core/semantic/embedding/LearnedHeadClassifierEngine.kt` loads.

    § PASSAGGIO 13 §N, tightened by PASSAGGIO 14 §R/§S — [mode] must be
    `"fake"` (self-test, the only mode this environment can run) or
    `"real"` (a genuine, frozen-encoder EmbeddingGemma run — the CALLER,
    never this function, is responsible for having already passed
    [production_gate.assert_production_ready] before reaching this point).
    `"fake"` always exports `artifactQualification="SYNTHETIC_SELFTEST"`.
    `"real"` exports `artifactQualification="REAL_TRAINED"` — deliberately
    NEVER `"PRODUCTION_ELIGIBLE"` here: a real, genuinely trained head still
    has `calibrationStatus="PENDING"` until PASSAGGIO 15's calibration gate
    re-exports it as `PRODUCTION_ELIGIBLE`/`CALIBRATED` — this function must
    never claim full production authority on its own. Both
    `LearnedHeadExport.isCompatibleWithRuntime()` (Kotlin) and `report.py`'s
    `learned_head_scorers()` refuse anything short of `PRODUCTION_ELIGIBLE`
    for authoritative use — this project has never shipped either a `"real"`
    OR a `PRODUCTION_ELIGIBLE` export as of PASSAGGIO 14.
    """
    qualification = "SYNTHETIC_SELFTEST" if mode == "fake" else "REAL_TRAINED"
    payload: dict = {
        "schemaVersion": EXPORT_SCHEMA_VERSION,
        "embeddingDim": embed_dim,
        "intent": export_multiclass_head(heads.intent.model, heads.intent_labels),
        "domain": export_domain_head(heads.domain.model, ALL_DOMAINS, embed_dim),
        "operation": (
            export_multiclass_head(heads.operation.model, heads.operation_labels)
            if heads.operation is not None else None
        ),
        "referenceMode": (
            export_multiclass_head(heads.reference_mode.model, heads.reference_mode_labels)
            if heads.reference_mode is not None else None
        ),
        "encoderContract": build_encoder_contract(
            mode, embed_dim, model_sha256, tokenizer_sha256,
            tokenizer_report=tokenizer_report, encoder_report=encoder_report,
        ),
        "artifactQualification": qualification,
        # § PASSAGGIO 14 §Q/§R (test items 12, 13-18) — always explicit,
        # never inferred/omitted; PENDING for every artifact this pass can
        # produce, real or synthetic, since calibration is PASSAGGIO 15's
        # scope entirely.
        "calibrationStatus": "PENDING",
        "datasetRevision": dataset_revision,
        "trainingSeed": training_seed,
        "trainedAtIso": trained_at_iso,
    }
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(payload, f)
    return payload


if __name__ == "__main__":
    import argparse
    import hashlib
    from datetime import datetime, timezone

    from calibrate import calibrate_domain_threshold, calibrate_intent_thresholds
    from dataset import TRAINING_CORPUS_PATH, dataset_revision as compute_dataset_revision, load_corpus
    from embed import CONTRACT_VERSION, EmbeddingCache, cached_embedder, fake_embedder
    from production_gate import assert_production_ready
    from train_heads import TRAINING_SEED, train

    parser = argparse.ArgumentParser()
    mode_group = parser.add_mutually_exclusive_group(required=True)
    mode_group.add_argument("--fake", action="store_true", help="synthetic self-test embedder — the only mode runnable without a real EmbeddingGemma artifact")
    mode_group.add_argument("--real", action="store_true", help="§ PASSAGGIO 14B — a genuine frozen-encoder run; requires --manifest + both qualification reports having already PASSed")
    parser.add_argument("--corpus", default=TRAINING_CORPUS_PATH)
    parser.add_argument("--out-dir", default=".")
    parser.add_argument("--manifest", default="artifact_manifest.json", help="--real only: the ArtifactManifest built by artifact_manifest.py")
    parser.add_argument("--tokenizer-report", default="tokenizer_qualification_report.json", help="--real only: TOKENIZER_GATE report, must show status=PASS")
    parser.add_argument("--encoder-report", default="encoder_qualification_report.json", help="--real only: ENCODER_GATE report, must show status=PASS")
    parser.add_argument("--cache", default=None, help="optional on-disk embedding cache path (either mode) — pass the SAME path generate_embeddings.py used to reuse its cached real embeddings instead of re-computing them")
    args = parser.parse_args()

    corpus = load_corpus(args.corpus)
    dataset_rev = compute_dataset_revision(args.corpus) if os.path.exists(args.corpus) else None
    trained_at_iso = datetime.now(timezone.utc).isoformat()

    if args.real:
        from artifact_manifest import load_manifest, verify_manifest_matches_files
        from embed import real_embedder

        manifest = load_manifest(args.manifest)
        verify_manifest_matches_files(manifest)
        with open(args.tokenizer_report, encoding="utf-8") as f:
            tokenizer_report = json.load(f)
        with open(args.encoder_report, encoding="utf-8") as f:
            encoder_report = json.load(f)
        if tokenizer_report.get("status") != "PASS" or encoder_report.get("status") != "PASS":
            raise SystemExit("--real requires both tokenizer_qualification_report.json and encoder_qualification_report.json to show status=PASS — run those gates first")

        embed_fn = real_embedder(manifest)  # raises RuntimeError here if the artifact/library isn't actually available — never silently substituted
        assert_production_ready(embed_fn, None)
        model_id = f"{manifest.modelName}:{manifest.modelSha256[:16]}:{manifest.tokenizerSha256[:16]}"
        if args.cache:
            cache = EmbeddingCache(args.cache, model_id=model_id, contract_version=CONTRACT_VERSION)
            if len(cache) > 0:
                assert_production_ready(embed_fn, cache)
            embed_fn = cached_embedder(embed_fn, cache)
        mode = "real"
        embed_dim = encoder_report.get("embeddingDimension")
        model_sha256, tokenizer_sha256 = manifest.modelSha256, manifest.tokenizerSha256
    else:
        embed_fn = fake_embedder()
        if args.cache:
            cache = EmbeddingCache(args.cache, model_id="fake-embedder", contract_version=CONTRACT_VERSION)
            embed_fn = cached_embedder(embed_fn, cache)
        mode = "fake"
        from embed import FAKE_EMBEDDING_DIM
        embed_dim = FAKE_EMBEDDING_DIM
        model_sha256 = tokenizer_sha256 = None
        tokenizer_report = encoder_report = None

    calib = calibrate_intent_thresholds(corpus, embed_fn)
    domain_calib = calibrate_domain_threshold(corpus, embed_fn)
    thresholds = {
        "intentConfidenceMin": calib["intentConfidenceMin"],
        "intentMarginMin": calib["intentMarginMin"],
        "domainSimilarityMin": domain_calib["domainSimilarityMin"],
        "operationConfidenceMin": 0.40,
        "referenceModeConfidenceMin": 0.40,
    }
    export_thresholds(thresholds, f"{args.out_dir}/thresholds.json")
    print(f"wrote {args.out_dir}/thresholds.json: {thresholds}")

    heads = train(corpus, embed_fn)
    if args.cache:
        cache.save()
    payload = export_head_weights(
        heads, embed_dim, f"{args.out_dir}/head_weights.json", mode=mode,
        model_sha256=model_sha256, tokenizer_sha256=tokenizer_sha256,
        dataset_revision=dataset_rev, training_seed=TRAINING_SEED, trained_at_iso=trained_at_iso,
        tokenizer_report=tokenizer_report, encoder_report=encoder_report,
    )
    print(f"wrote {args.out_dir}/head_weights.json (artifactQualification={payload['artifactQualification']}, "
          f"calibrationStatus={payload['calibrationStatus']}, datasetRevision={dataset_rev})")
