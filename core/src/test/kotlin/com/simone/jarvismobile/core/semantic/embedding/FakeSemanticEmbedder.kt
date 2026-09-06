package com.simone.jarvismobile.core.semantic.embedding

/**
 * § FASE 2A.11 §19 — a deterministic STAND-IN for the real EmbeddingGemma
 * model, used ONLY by tests in this package. This project has no network
 * access to fetch the real model and no Android runtime to execute a
 * `.tflite` graph in this JVM-only environment (see `CLAUDE.md`'s
 * "Environment note"), so every invariant test below proves
 * [PrototypeSemanticClassifierEngine]'s ALGORITHM (cosine similarity +
 * centroids + margin/threshold OOD gating + multi-label domains) is correct
 * against a substitute embedding space this test file fully controls and
 * understands — exactly the same testing strategy `SemanticFrameMergerTest`/
 * `SemanticRouterTest` already use against hand-built [SemanticFrame]s
 * instead of real model output.
 *
 * This is NOT a keyword matcher standing in for the production classifier —
 * it is a coarse concept-bucket bag-of-words embedding space, used only to
 * give the test suite something with realistic clustering properties
 * (unseen synonyms of a concept land near its prototypes; unrelated concepts
 * land far apart) without claiming any resemblance to what EmbeddingGemma
 * itself would actually produce. [dimension] is deliberately smaller than
 * EmbeddingGemma's real 768 — irrelevant to the algorithm, which works over
 * any fixed dimension.
 */
object FakeSemanticEmbedder {

    /** Each concept gets its own basis dimension; every word below is one of its "synonyms" for this fake space. */
    private val conceptGroups: List<Pair<String, Set<String>>> = listOf(
        "weather" to setOf(
            "meteo", "tempo", "pioggia", "piove", "piovera", "piovuto", "sole", "sereno", "nuvoloso", "nuvole",
            "caldo", "freddo", "gradi", "temperatura", "vento", "ombrello", "temporale", "suda", "suderà",
            "giacca", "cappotto", "previsioni", "afa", "nevica",
        ),
        // § merged from two separate dimensions (health_sleep/health_heart):
        // both are the SAME `ToolFamily.HEALTH` domain in the real corpus, but
        // keeping them as orthogonal dimensions meant a heart-only query
        // (no sleep words) barely aligned with the HEALTH centroid at all,
        // since that centroid is built mostly from sleep-flavored examples —
        // found by inspecting real classification scores (a heart-only test
        // sentence scored ~0.06 against HEALTH), not assumed.
        "health" to setOf(
            "dormito", "dormire", "sonno", "riposato", "riposo", "notte", "sveglio", "dormo", "riposare", "notturno",
            "battito", "cuore", "cardiaco", "bpm", "cardiaca", "frequenza",
        ),
        "agenda" to setOf(
            "impegni", "impegno", "appuntamento", "appuntamenti", "riunione", "agenda", "programma", "calendario",
            "promemoria", "attivita", "libero", "libera", "segnato", "cancellare", "cancella", "sposta", "rinomina",
            "completata", "programmato", "dentista", "fare", "devo",
        ),
        "device_info" to setOf(
            "ram", "spazio", "archiviazione", "modello", "versione", "android", "telefono", "dispositivo", "memoria",
            "interna", "vram", "monta",
        ),
        // § "cos" (not "cose"): `normalize()` strips the apostrophe in
        // "cos'è"/"cos'e" as punctuation, tokenizing it to "cos"+"e" — the
        // dictionary entry must match the token this scheme ACTUALLY
        // produces, found by inspecting a real classification run where a
        // literal corpus example scored near-zero on this dimension despite
        // clearly being a "che cos'è" definitional question.
        "knowledge" to setOf(
            "perche", "come", "differenza", "funziona", "spiega", "spiegami", "cos", "parlami", "secondo",
            "conviene", "senso", "qual", "chi", "quali", "vantaggi", "causa", "dipende", "aerei", "volare",
        ),
        "greeting" to setOf(
            "ciao", "buongiorno", "buonasera", "buonanotte", "grazie", "stai", "stanco", "chiacchiere", "divertente",
            "morale", "complimenti", "disturbo",
        ),
        "clarify" to setOf(
            "intendi", "capito", "ripetere", "preciso", "riferisci", "spiegarti", "riformula", "senso", "quale",
        ),
        "device_control" to setOf(
            "accendi", "spegni", "luce", "musica", "pausa", "riproduci", "chiama", "chiami", "batteria", "carica",
            "caricando", "torcia",
        ),
        "archive_memory" to setOf(
            "appunti", "nota", "note", "lista", "spesa", "vedere", "cercato", "salvato", "ricordami", "annota",
            "mutuo", "regalo",
        ),
        "multi_connective" to setOf(
            "considerando", "tenendo", "vista", "guardando", "sapendo", "conto", "conviene", "meglio", "rimandare",
        ),
        "communication" to setOf("scritto", "chiamato", "rispondi", "messaggio", "notifiche"),
        "driving_media" to setOf("portami", "traffico", "canzone", "ascoltando", "strada"),
        "utility_time" to setOf("calcola", "diviso", "percento", "sveglia", "timer", "manca"),
        // § the two axes that separate a QUESTION about a topic from a
        // COMMAND about the same topic — my earlier bag-of-topic-words-only
        // scheme could not tell "Che impegni ho domani?" (a query) from
        // "Ricordami di comprare il latte" (a command) apart, since both
        // share the AGENDA vocabulary; a real embedding model captures verb
        // mood/sentence structure natively, this toy space needs an explicit
        // proxy for it.
        // § deliberately narrow: only wh-words rare enough in commands to
        // carry real signal. Near-universal function words ("e", "sono",
        // "ho", "come", "fa", "che") were tried and REMOVED — they appear in
        // almost every sentence regardless of intent, so putting them in one
        // shared bucket collapsed the margin between every intent instead of
        // separating them (found by inspecting real classification scores,
        // not assumed).
        "interrogative_marker" to setOf(
            "quanto", "quanti", "quanta", "quante", "quando", "dove", "chi", "quale", "quali",
        ),
        "imperative_marker" to setOf(
            "aggiungi", "cancella", "sposta", "rinomina", "segna", "metti", "crea", "elimina", "imposta", "dammi",
            "dimmi",
        ),
        // § a bare temporal reference is a strong, shared signal across every
        // CAPABILITY_QUERY subgroup (including a domain-less ellipsis
        // follow-up like "E dopodomani?") without itself picking a domain —
        // exactly the structural property `ReferenceMode`/§11 describe.
        "temporal_marker" to setOf(
            "oggi", "domani", "dopodomani", "ieri", "stanotte", "settimana", "venerdi", "lunedi", "martedi",
            "mercoledi", "giovedi", "sabato", "domenica", "weekend", "sera", "mattina", "pomeriggio", "mese",
            "prossima", "scorsa", "invece",
        ),
    )

    private val dimension = conceptGroups.size

    /**
     * Out-of-vocabulary words contribute NOTHING (not even weak noise): a
     * genuinely nonsensical/out-of-scope query, made almost entirely of
     * words outside every concept group, must land at or near the zero
     * vector — [EmbeddingMath.cosineSimilarity] already returns 0 for a zero
     * vector against anything, so this is what makes [F - out-of-distribution
     * requests][PrototypeSemanticClassifierEngineTest] reliably score near
     * zero on every centroid instead of randomly favoring one by hash
     * collision (an earlier version of this fake embedder added small noise
     * for OOV words specifically to avoid identical embeddings for distinct
     * sentences — found, by inspecting real classification scores, to
     * occasionally push a truly nonsensical query's noise vector closer to
     * one real centroid than to the others, defeating the OOD gate; removed).
     */
    fun embed(text: String): EmbeddingVector {
        val words = normalize(text).split(Regex("\\s+")).filter { it.isNotBlank() }
        val vector = FloatArray(dimension)
        for (word in words) {
            val groupIndex = conceptGroups.indexOfFirst { (_, synonyms) -> word in synonyms }
            if (groupIndex >= 0) vector[groupIndex] += 1f
        }
        return vector
    }

    private fun normalize(text: String): String = text.lowercase()
        .replace('à', 'a').replace('è', 'e').replace('é', 'e')
        .replace('ì', 'i').replace('ò', 'o').replace('ù', 'u')
        .replace(Regex("[^a-z0-9\\s]"), " ")
}
