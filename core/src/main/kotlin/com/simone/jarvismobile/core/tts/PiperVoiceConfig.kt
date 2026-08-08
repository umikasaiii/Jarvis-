package com.simone.jarvismobile.core.tts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A Piper voice's `.onnx.json`.
 *
 * This file is the reason Piper is easier to get right than Kokoro: it ships the
 * exact `phoneme_id_map` the graph was trained with, its sample rate and its
 * inference scales. Nothing has to be reconstructed or guessed — if the config
 * is present, the token ids are correct by construction.
 *
 * Only the fields that affect synthesis are read; everything else in the file is
 * training metadata.
 */
data class PiperVoiceConfig(
    val sampleRate: Int,
    val noiseScale: Float,
    val lengthScale: Float,
    val noiseW: Float,
    val numSpeakers: Int,
    /** Speaker names in id order; a single-speaker voice reports one entry. */
    val speakers: List<String>,
    val speakerIds: Map<String, Int>,
    /** IPA character to token ids. A phoneme can map to more than one id. */
    val phonemeIds: Map<Char, List<Int>>,
    /** espeak voice the model was trained on, e.g. "it". */
    val language: String,
) {
    val isMultiSpeaker: Boolean get() = numSpeakers > 1

    fun speakerId(name: String): Int = speakerIds[name] ?: 0

    /**
     * Turns phonemes into the id sequence Piper expects: a begin marker, then
     * every phoneme followed by the pad token, then an end marker. The
     * interleaved pad is not decoration — the model was trained on it, and
     * leaving it out produces audio that is recognisably wrong rather than
     * merely worse.
     */
    fun encode(phonemes: String): IntArray {
        val out = ArrayList<Int>(phonemes.length * 2 + 4)
        phonemeIds[BOS]?.let(out::addAll)
        val pad = phonemeIds[PAD]
        for (c in phonemes) {
            val ids = phonemeIds[c] ?: continue
            out.addAll(ids)
            pad?.let(out::addAll)
        }
        phonemeIds[EOS]?.let(out::addAll)
        return out.toIntArray()
    }

    companion object {
        const val PAD = '_'
        const val BOS = '^'
        const val EOS = '$'
    }
}

object PiperConfigReader {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Returns null when the file is not a usable Piper config. */
    fun parse(text: String): PiperVoiceConfig? = runCatching {
        val root = json.parseToJsonElement(text).jsonObject

        val phonemeIds = (root["phoneme_id_map"] as? JsonObject)
            ?.mapNotNull { (symbol, value) ->
                // Keys are single characters; multi-character entries are not
                // phonemes this pipeline can emit, so they are skipped rather
                // than mangled.
                val c = symbol.singleOrNull() ?: return@mapNotNull null
                val ids = (value as? JsonArray)?.mapNotNull { it.jsonPrimitive.intOrNull }
                    ?: (value as? JsonPrimitive)?.intOrNull?.let { listOf(it) }
                if (ids.isNullOrEmpty()) null else c to ids
            }
            ?.toMap()
            ?: return null
        if (phonemeIds.size < 16) return null

        val audio = root["audio"] as? JsonObject
        val inference = root["inference"] as? JsonObject
        val speakerIdMap = (root["speaker_id_map"] as? JsonObject)
            ?.mapNotNull { (name, value) ->
                value.jsonPrimitive.intOrNull?.let { name to it }
            }
            ?.toMap()
            .orEmpty()

        val numSpeakers = root["num_speakers"]?.jsonPrimitive?.intOrNull
            ?: speakerIdMap.size.coerceAtLeast(1)

        PiperVoiceConfig(
            sampleRate = audio?.get("sample_rate")?.jsonPrimitive?.intOrNull ?: DEFAULT_SAMPLE_RATE,
            noiseScale = inference?.get("noise_scale")?.jsonPrimitive?.floatOrNull ?: 0.667f,
            lengthScale = inference?.get("length_scale")?.jsonPrimitive?.floatOrNull ?: 1.0f,
            noiseW = inference?.get("noise_w")?.jsonPrimitive?.floatOrNull ?: 0.8f,
            numSpeakers = numSpeakers,
            speakers = speakerNames(speakerIdMap, numSpeakers),
            speakerIds = speakerIdMap,
            phonemeIds = phonemeIds,
            language = (root["espeak"] as? JsonObject)?.get("voice")?.jsonPrimitive?.contentOrNullSafe()
                ?: (root["language"] as? JsonObject)?.get("code")?.jsonPrimitive?.contentOrNullSafe()
                ?: "",
        )
    }.getOrNull()

    private fun speakerNames(map: Map<String, Int>, numSpeakers: Int): List<String> = when {
        map.isNotEmpty() -> map.entries.sortedBy { it.value }.map { it.key }
        numSpeakers > 1 -> (0 until numSpeakers).map { "speaker $it" }
        else -> listOf("default")
    }

    private fun JsonPrimitive.contentOrNullSafe(): String? = runCatching { content }.getOrNull()

    private const val DEFAULT_SAMPLE_RATE = 22_050
}
