"""§ JARVIS Implementation Master Plan — PASSAGGIO 14 §Y. Real, runnable
`unittest` tests (stdlib only — no new dependency) for `production_gate.py`
— the refusal logic itself is deterministic and testable WITHOUT any real
EmbeddingGemma artifact, exactly per §D's own instruction that deterministic
tooling/tests may still be improved while REAL_ARTIFACT_GATE is PENDING.

Run: `python3 -m unittest test_production_gate.py -v`
"""
from __future__ import annotations

import os
import tempfile
import unittest

from embed import (
    CONTRACT_VERSION,
    QUALIFICATION_REAL,
    QUALIFICATION_SYNTHETIC,
    EmbeddingCache,
    cached_embedder,
    fake_embedder,
)
from production_gate import (
    ProductionGateError,
    assert_cache_production_ready,
    assert_embedder_production_ready,
    assert_production_ready,
)


def _fake_real_embedder():
    """A stand-in embedder tagged REAL for testing the GATE logic only —
    never used to produce a shipped artifact, never exported, never saved
    under a real model_id. Its numbers are meaningless; only its
    `.qualification` tag matters for these tests."""

    def embed(text: str):
        import numpy as np
        return np.ones(4)

    embed.qualification = QUALIFICATION_REAL
    return embed


class EmbedderGateTest(unittest.TestCase):
    def test_synthetic_embedder_is_refused(self):
        with self.assertRaises(ProductionGateError):
            assert_embedder_production_ready(fake_embedder())

    def test_untagged_embedder_is_refused(self):
        def untagged(text: str):
            return None

        with self.assertRaises(ProductionGateError):
            assert_embedder_production_ready(untagged)

    def test_real_tagged_embedder_passes(self):
        assert_embedder_production_ready(_fake_real_embedder())  # must not raise


class CacheGateTest(unittest.TestCase):
    def setUp(self):
        self.path = tempfile.mktemp(suffix=".json")

    def tearDown(self):
        if os.path.exists(self.path):
            os.remove(self.path)

    def test_fresh_synthetic_cache_is_refused(self):
        cache = EmbeddingCache(self.path, model_id="m")
        embed = cached_embedder(fake_embedder(), cache)
        embed("ciao")
        with self.assertRaises(ProductionGateError):
            assert_cache_production_ready(cache)

    def test_fresh_real_tagged_cache_passes(self):
        cache = EmbeddingCache(self.path, model_id="m")
        embed = cached_embedder(_fake_real_embedder(), cache)
        embed("ciao")
        assert_cache_production_ready(cache)  # must not raise

    def test_legacy_flat_format_cache_is_refused_even_with_real_data_inside(self):
        import json

        with open(self.path, "w", encoding="utf-8") as f:
            json.dump({"somehash": [0.1, 0.2, 0.3]}, f)
        cache = EmbeddingCache(self.path, model_id="m", qualification=QUALIFICATION_REAL)
        self.assertEqual(cache.qualification, "UNKNOWN")
        with self.assertRaises(ProductionGateError):
            assert_cache_production_ready(cache)

    def test_old_contract_version_cache_is_refused_even_if_tagged_real(self):
        cache = EmbeddingCache(self.path, model_id="m", contract_version=CONTRACT_VERSION - 1)
        embed = cached_embedder(_fake_real_embedder(), cache)
        embed("ciao")
        self.assertEqual(cache.qualification, QUALIFICATION_REAL)
        with self.assertRaises(ProductionGateError):
            assert_cache_production_ready(cache)

    def test_hash_mismatched_entry_is_a_guaranteed_miss_regardless_of_gate(self):
        """§ Y item 5/34 — even a fully production-ready cache never returns
        a stale entry for a text whose canonicalized key doesn't match
        (defense already in the key itself, per §O) — the gate above is
        additive, not a replacement for this."""
        cache = EmbeddingCache(self.path, model_id="model-a")
        embed = cached_embedder(_fake_real_embedder(), cache)
        embed("ciao mondo")
        other_model_cache = EmbeddingCache(self.path + ".other", model_id="model-b")
        self.assertIsNone(other_model_cache.get("ciao mondo"))


class CombinedGateTest(unittest.TestCase):
    def test_combined_gate_requires_both_embedder_and_cache_real(self):
        path = tempfile.mktemp(suffix=".json")
        try:
            cache = EmbeddingCache(path, model_id="m")
            with self.assertRaises(ProductionGateError):
                assert_production_ready(fake_embedder(), cache)
            # a fresh real embedder + no cache at all must pass
            assert_production_ready(_fake_real_embedder(), None)
        finally:
            if os.path.exists(path):
                os.remove(path)


if __name__ == "__main__":
    unittest.main()
