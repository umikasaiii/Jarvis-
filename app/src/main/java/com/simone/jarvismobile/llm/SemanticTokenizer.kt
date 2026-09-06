package com.simone.jarvismobile.llm

import android.util.Log
import java.io.File

/**
 * § FASE 2A.11 §1 — turns text into the token id sequence EmbeddingGemma's
 * `.tflite` graph expects, padded/truncated to a fixed sequence length (256,
 * per §1 — `embeddinggemma-300M_seq256_mixed-precision.tflite`'s own name).
 * A separate interface from [EmbeddingGemmaEngine] so a real SentencePiece
 * binding can replace [FallbackWhitespaceTokenizer] without touching the
 * model-loading/inference code at all.
 */
interface SemanticTokenizer {
    val isReady: Boolean

    /** Loads the tokenizer's own data file (`sentencepiece.model`, §1). Returns true on success. */
    fun load(tokenizerPath: String): Boolean

    /** Token ids for [text], length exactly [maxSequenceLength] (padded with [padTokenId] or truncated) — null if [isReady] is false. */
    fun encode(text: String, maxSequenceLength: Int): IntArray?
}

/**
 * **Onestà — limite dichiarato, non uno stub silenzioso**: questa NON è
 * un'implementazione reale di SentencePiece. Un vero tokenizer SentencePiece
 * (unigram/BPE) richiederebbe o un binding Java/Kotlin verificato contro
 * Maven Central (nessun accesso di rete in questo ambiente per confermarne
 * le coordinate reali — stesso limite già documentato per Health Connect/
 * TomTom/Valhalla) o una reimplementazione da zero del protobuf
 * `sentencepiece.model` e del suo algoritmo di segmentazione, un lavoro
 * sostanzioso non verificabile senza un compilatore Kotlin/Android reale a
 * disposizione. Questa classe esiste SOLO per rendere il resto della
 * pipeline (caricamento modello, inferenza, classificazione) completo e
 * collegato end-to-end fin da subito — split su spazi + hashing deterministico
 * in un intervallo di vocabolario plausibile, mai gli id reali del vocabolario
 * di EmbeddingGemma. **La qualità semantica reale dipende da un vero
 * tokenizer SentencePiece, non ancora collegato** — vedi il report finale
 * della FASE 2A.11 per il percorso consigliato (una libreria SentencePiece
 * reale, o l'API MediaPipe Tasks Text Embedder se include la tokenizzazione).
 *
 * **Scoperta in fase di audit, rilevante per chi riprenderà questo lavoro**:
 * `core/memory/WordPieceTokenizer.kt` (già in questo repository, per
 * `OnnxEmbedder`/`EmbeddingRepository` — il retrieval semantico della Memory
 * V2) dimostra che un tokenizer a sottoparole scritto a mano, puro Kotlin, è
 * già stato realizzato con successo in questo stesso progetto — quindi
 * un'implementazione reale non è fuori portata in linea di principio. NON
 * riusabile direttamente qui però: WordPiece (greedy longest-match) e
 * SentencePiece unigram (l'algoritmo con cui EmbeddingGemma è stato
 * addestrato) sono algoritmi di segmentazione diversi con vocabolari/id
 * diversi — usare `WordPieceTokenizer` contro `sentencepiece.model`
 * produrrebbe id di token sbagliati (un vero bug di correttezza, non solo di
 * qualità), quindi non tentato alla cieca.
 */
class FallbackWhitespaceTokenizer : SemanticTokenizer {
    override var isReady: Boolean = false
        private set

    override fun load(tokenizerPath: String): Boolean {
        val ok = File(tokenizerPath).let { it.exists() && it.length() > 0 }
        isReady = ok
        if (!ok) Log.w(TAG, "tokenizer_file_missing_or_empty")
        return ok
    }

    override fun encode(text: String, maxSequenceLength: Int): IntArray? {
        if (!isReady) return null
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val ids = IntArray(maxSequenceLength) { PAD_TOKEN_ID }
        for (i in words.indices) {
            if (i >= maxSequenceLength) break
            // Deterministic, bounded hash — never a real SentencePiece id;
            // see the class doc comment's honesty note.
            ids[i] = 1 + (words[i].lowercase().hashCode().and(0x7fffffff) % (VOCAB_SIZE_UPPER_BOUND - 1))
        }
        return ids
    }

    private companion object {
        const val TAG = "SemanticTokenizer"
        const val PAD_TOKEN_ID = 0
        const val VOCAB_SIZE_UPPER_BOUND = 256_000 // EmbeddingGemma's public vocab size, per its model card
    }
}
