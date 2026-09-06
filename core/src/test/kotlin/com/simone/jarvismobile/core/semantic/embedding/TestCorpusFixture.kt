package com.simone.jarvismobile.core.semantic.embedding

/** Loads the real shipped corpus (`core/src/main/resources/semantic/prototypes.json`) and embeds it with [FakeSemanticEmbedder]. */
object TestCorpusFixture {
    val corpus: PrototypeCorpus by lazy {
        val stream = checkNotNull(PrototypeCorpusCodec.javaClass.getResourceAsStream("/semantic/prototypes.json")) {
            "prototypes.json resource not found on the test classpath"
        }
        PrototypeCorpusCodec.parse(stream.bufferedReader().use { it.readText() })
    }

    /**
     * Thresholds tuned for [FakeSemanticEmbedder]'s much cruder, noisier toy
     * embedding space — NOT a claim about what the real EmbeddingGemma model
     * would need (see [ClassifierThresholds.CONSERVATIVE_DEFAULT]'s own doc
     * comment). A bag-of-concept-groups space has more incidental
     * cross-dimension noise than a real 768-dim semantic embedding, so the
     * domain threshold is raised here to compensate — this is exactly the
     * kind of empirical adjustment `tools/semantic_classifier/calibrate.py`
     * would do for the real model, just done by hand for this test fixture.
     */
    private val FAKE_EMBEDDER_THRESHOLDS = ClassifierThresholds(
        intentConfidenceMin = 0.22f,
        intentMarginMin = 0.03f,
        domainSimilarityMin = 0.58f,
        operationConfidenceMin = 0.40f,
        referenceModeConfidenceMin = 0.75f,
    )

    /** A classifier engine built from every TRAINING-split prototype, embedded via [FakeSemanticEmbedder]. */
    fun buildEngine(thresholds: ClassifierThresholds = FAKE_EMBEDDER_THRESHOLDS): PrototypeSemanticClassifierEngine {
        val embedded = corpus.trainingPrototypes().map { proto ->
            PrototypeSemanticClassifierEngine.EmbeddedPrototype(proto, FakeSemanticEmbedder.embed(proto.text))
        }
        return PrototypeSemanticClassifierEngine(embedded, thresholds)
    }
}
