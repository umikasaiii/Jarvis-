"""§ JARVIS Implementation Master Plan — PASSAGGIO 14 §M/§Y (test item 27).
Real, runnable `unittest` (stdlib only) proving the training PROCEDURE
itself is deterministic on a fixed seed against a deterministic fixture —
`embed.fake_embedder()` is itself deterministic (a seeded hash-based
projection per word, see its own doc comment) and `train_heads.TRAINING_SEED`
fixes every `MLPClassifier`'s `random_state` — so running `train_heads.train()`
twice against the SAME corpus/embedder must produce bit-identical exported
weights. This property is about the training procedure's own determinism,
independent of which embedder eventually supplies real vs. synthetic
numbers — genuinely provable without any real EmbeddingGemma artifact,
exactly the kind of deterministic tooling §D says may still be improved
while REAL_ARTIFACT_GATE is PENDING.

Run: `python3 -m unittest test_training_determinism.py -v`
"""
from __future__ import annotations

import unittest

from dataset import TRAINING_CORPUS_PATH, load_corpus
from embed import fake_embedder
from export import export_multiclass_head
from train_heads import train


class TrainingDeterminismTest(unittest.TestCase):
    def test_same_corpus_and_embedder_produce_identical_exported_weights(self):
        corpus = load_corpus(TRAINING_CORPUS_PATH)

        heads_a = train(corpus, fake_embedder())
        heads_b = train(corpus, fake_embedder())  # a FRESH embedder instance each time — fake_embedder() itself is deterministic per-word (seeded hash), not stateful across instances

        for name, labels in (
            ("intent", heads_a.intent_labels),
            ("operation", heads_a.operation_labels),
            ("referenceMode", heads_a.reference_mode_labels),
        ):
            result_a = getattr(heads_a, "intent" if name == "intent" else ("operation" if name == "operation" else "reference_mode"))
            result_b = getattr(heads_b, "intent" if name == "intent" else ("operation" if name == "operation" else "reference_mode"))
            if result_a is None:
                self.assertIsNone(result_b, f"{name}: A had no head but B did")
                continue
            self.assertEqual(result_a.architecture, result_b.architecture, f"{name}: chosen architecture differs")
            exported_a = export_multiclass_head(result_a.model, labels)
            exported_b = export_multiclass_head(result_b.model, labels)
            self.assertEqual(exported_a, exported_b, f"{name}: exported weights differ between two runs")

        # domain head is multi-label (OneVsRestClassifier or MLP) — compare via the SAME
        # export_domain_head() path report.py/export.py both actually use in production.
        from export import export_domain_head
        from train_heads import ALL_DOMAINS
        embed_dim_a = len(fake_embedder()("ciao"))
        exported_domain_a = export_domain_head(heads_a.domain.model, ALL_DOMAINS, embed_dim_a)
        exported_domain_b = export_domain_head(heads_b.domain.model, ALL_DOMAINS, embed_dim_a)
        self.assertEqual(heads_a.domain.architecture, heads_b.domain.architecture, "domain: chosen architecture differs")
        self.assertEqual(exported_domain_a, exported_domain_b, "domain: exported weights differ between two runs")


if __name__ == "__main__":
    unittest.main()
