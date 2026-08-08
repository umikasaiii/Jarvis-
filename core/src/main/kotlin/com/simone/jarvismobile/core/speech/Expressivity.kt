package com.simone.jarvismobile.core.speech

/**
 * How a reply should be *interpreted* — the same voice, different delivery.
 *
 * These are communicative tones, not theatrical emotions: they move rate,
 * rhythm, pauses and emphasis, never the speaker's identity. On the neural ONNX
 * voices (Kokoro/Piper) there is no emotion or pitch input, so a tone reaches
 * the ear mainly as speed, pausing and expressiveness — pitch nudges only affect
 * engines that accept it. That honesty is baked into [ExpressivePlanner]: it
 * never promises more range than the backend can produce.
 */
enum class Emotion {
    NEUTRAL, WARM, EXPLAINING, HAPPY, GENTLE, SERIOUS, URGENT, PLAYFUL, GREETING;

    companion object {
        /** Maps a metadata word (from the model or a preset) to a tone. */
        fun fromTag(tag: String): Emotion? = when (tag.trim().lowercase()) {
            "neutral", "normale", "normal", "naturale" -> NEUTRAL
            "warm", "calda", "calorosa", "conversational", "conversazionale" -> WARM
            "explaining", "explain", "spiegazione", "chiara", "clear" -> EXPLAINING
            "happy", "felice", "gioiosa", "allegra", "entusiasta", "good_news", "positive" -> HAPPY
            "calm", "calma", "gentle", "delicata", "soft", "reassuring", "rassicurante", "tender" -> GENTLE
            "serious", "seria", "firm", "ferma", "warning", "avviso", "important", "importante" -> SERIOUS
            "urgent", "urgente", "fast", "rapida", "energica", "authoritative", "autorevole" -> URGENT
            "playful", "giocosa", "joke", "battuta", "ironic", "ironica", "fun" -> PLAYFUL
            "greeting", "saluto", "hello" -> GREETING
            "sad", "negative", "negativa", "composed", "composta" -> GENTLE
            else -> null
        }
    }
}

/** A picked interpretation: the tone, its strength, the resolved style, and the
 *  reply with any hidden metadata removed (never shown or spoken). */
data class ExpressivePlan(
    val emotion: Emotion,
    val intensity: Float,
    val style: SpeechStyle,
    val cleanedText: String,
)

/**
 * Chooses how to deliver a reply, without a second heavy model pass.
 *
 * Preferred path: the conversational model emits a hidden metadata tag alongside
 * the answer (e.g. `⟦tts: emotion=reassuring; intensity=0.4⟧`); we read it and
 * strip it so it is never displayed or spoken. Fallback path, when no tag is
 * present: a light contextual heuristic over structure and cue phrases (not a
 * single keyword). The chosen tone is applied to the whole reply for continuity,
 * blended onto the user's base style by an intensity scale so normal speech
 * stays natural and relatively neutral — expressive, not theatrical.
 */
object ExpressivePlanner {

    /** Intensity multipliers for the three UI levels; higher = more pronounced. */
    const val LOW = 0.6f
    const val MEDIUM = 1.0f
    const val HIGH = 1.45f

    /** Base blend toward the tone at MEDIUM intensity — deliberately gentle. */
    private const val BASE_BLEND = 0.34f

    private val META = Regex(
        """[⟦\[]{1,2}\s*tts\b[:\s]([^⟧\]]*)[⟧\]]{1,2}""",
        RegexOption.IGNORE_CASE,
    )

    fun plan(
        reply: String,
        base: SpeechStyle = SpeechStyle.NATURALE,
        intensityScale: Float = MEDIUM,
        userContext: String = "",
        forcedEmotion: Emotion? = null,
    ): ExpressivePlan {
        val meta = META.find(reply)
        val cleaned = (if (meta != null) reply.removeRange(meta.range) else reply)
            .replace(Regex("""\s{2,}"""), " ").trim()

        var emotion: Emotion? = forcedEmotion
        var metaIntensity: Float? = null
        if (forcedEmotion == null) {
            meta?.groupValues?.getOrNull(1)?.let { body ->
                fields(body)["emotion"]?.let { emotion = Emotion.fromTag(it) }
                fields(body)["intensity"]?.toFloatOrNull()?.let { metaIntensity = it.coerceIn(0f, 1f) }
            }
        }
        val tone = emotion ?: heuristic(cleaned, userContext)

        // The metadata intensity (0..1) rescales the UI multiplier a little, so a
        // model that says "intensity=0.2" is calmer than one that says "0.9".
        val scale = intensityScale * (metaIntensity?.let { 0.5f + it } ?: 1f)
        val style = blend(base, target(tone), (BASE_BLEND * scale).coerceIn(0f, 0.75f)).coerced()
        return ExpressivePlan(tone, (metaIntensity ?: (intensityScale / HIGH)).coerceIn(0f, 1f), style, cleaned)
    }

    private fun fields(body: String): Map<String, String> =
        body.split(';', ',').mapNotNull {
            val i = it.indexOf('='); if (i <= 0) null else it.take(i).trim().lowercase() to it.substring(i + 1).trim()
        }.toMap()

    /** Blends [a] toward [b] by [t] in [0,1] on every dimension. */
    private fun blend(a: SpeechStyle, b: SpeechStyle, t: Float): SpeechStyle = SpeechStyle(
        rate = a.rate + (b.rate - a.rate) * t,
        pitch = a.pitch + (b.pitch - a.pitch) * t,
        pauseScale = a.pauseScale + (b.pauseScale - a.pauseScale) * t,
        expressiveness = a.expressiveness + (b.expressiveness - a.expressiveness) * t,
    )

    /** The absolute delivery each tone aims for (before blending with the base). */
    private fun target(e: Emotion): SpeechStyle = when (e) {
        Emotion.NEUTRAL -> SpeechStyle(rate = 1.0f, pitch = 1.0f, pauseScale = 1.0f, expressiveness = 0.55f)
        Emotion.WARM -> SpeechStyle(rate = 0.99f, pitch = 1.01f, pauseScale = 1.05f, expressiveness = 0.7f)
        Emotion.EXPLAINING -> SpeechStyle(rate = 0.97f, pitch = 1.0f, pauseScale = 1.18f, expressiveness = 0.85f)
        Emotion.HAPPY -> SpeechStyle(rate = 1.08f, pitch = 1.06f, pauseScale = 0.85f, expressiveness = 0.9f)
        Emotion.GENTLE -> SpeechStyle(rate = 0.9f, pitch = 0.98f, pauseScale = 1.4f, expressiveness = 0.8f)
        Emotion.SERIOUS -> SpeechStyle(rate = 0.96f, pitch = 0.97f, pauseScale = 1.12f, expressiveness = 0.5f)
        Emotion.URGENT -> SpeechStyle(rate = 1.22f, pitch = 1.02f, pauseScale = 0.6f, expressiveness = 0.4f)
        Emotion.PLAYFUL -> SpeechStyle(rate = 1.1f, pitch = 1.05f, pauseScale = 0.82f, expressiveness = 1.0f)
        Emotion.GREETING -> SpeechStyle(rate = 1.02f, pitch = 1.03f, pauseScale = 0.95f, expressiveness = 0.78f)
    }

    // --- the light contextual fallback -------------------------------------

    private fun heuristic(text: String, userContext: String): Emotion {
        val t = normalize(text)
        if (t.isBlank()) return Emotion.NEUTRAL
        // A user asking for help colours a plain answer as reassuring/warm.
        val askedForHelp = REASSURE_RE.containsMatchIn(normalize(userContext))

        return when {
            GREETING_RE.containsMatchIn(t) && t.length < 60 -> Emotion.GREETING
            URGENT_RE.containsMatchIn(t) -> Emotion.URGENT
            PLAYFUL_RE.containsMatchIn(t) -> Emotion.PLAYFUL
            WARNING_RE.containsMatchIn(t) -> Emotion.SERIOUS
            NEGATIVE_RE.containsMatchIn(t) -> Emotion.GENTLE
            REASSURE_RE.containsMatchIn(t) || askedForHelp -> Emotion.GENTLE
            HAPPY_RE.containsMatchIn(t) -> Emotion.HAPPY
            // A long, multi-sentence, connector-rich answer is an explanation.
            looksLikeExplanation(t) -> Emotion.EXPLAINING
            else -> Emotion.WARM
        }
    }

    private fun looksLikeExplanation(t: String): Boolean {
        val sentences = t.count { it == '.' || it == ';' || it == ':' }
        return t.length > 160 && sentences >= 2 && EXPLAIN_RE.containsMatchIn(t)
    }

    private fun normalize(s: String): String = s.lowercase()
        .replace('à', 'a').replace('è', 'e').replace('é', 'e')
        .replace('ì', 'i').replace('ò', 'o').replace('ù', 'u')

    private fun word(w: String) = "(?<![\\p{L}\\p{Nd}])(?:$w)(?![\\p{L}\\p{Nd}])"

    private val GREETING_RE = Regex("^\\s*(?:${word("ciao")}|${word("buongiorno")}|${word("buonasera")}|${word("salve")}|${word("ehi")}|${word("bentornato")}|${word("bentornata")})")
    private val URGENT_RE = Regex("${word("subito")}|${word("urgente")}|${word("in fretta")}|${word("sbrigati")}|${word("presto")}|${word("attenzione adesso")}")
    private val PLAYFUL_RE = Regex("${word("ahah")}|${word("aha")}|${word("scherzo")}|${word("battuta")}|${word("ovviamente no")}")
    private val WARNING_RE = Regex("${word("attenzione")}|${word("importante")}|${word("attento")}|${word("attenta")}|${word("ricordati di")}|${word("non dimenticare")}")
    private val NEGATIVE_RE = Regex("${word("purtroppo")}|${word("mi dispiace")}|${word("non sono riuscito")}|${word("non e stato possibile")}|${word("errore")}|${word("non ci sono riuscito")}|${word("temo")}")
    private val REASSURE_RE = Regex("${word("tranquillo")}|${word("tranquilla")}|${word("non preoccuparti")}|${word("nessun problema")}|${word("ci penso io")}|${word("va tutto bene")}|${word("stai sereno")}|${word("stai serena")}")
    private val HAPPY_RE = Regex("${word("ottimo")}|${word("perfetto")}|${word("benissimo")}|${word("evviva")}|${word("fantastico")}|${word("ottima notizia")}|${word("complimenti")}|${word("great")}")
    private val EXPLAIN_RE = Regex("${word("perche")}|${word("quindi")}|${word("in pratica")}|${word("significa")}|${word("in sostanza")}|${word("ovvero")}|${word("cioe")}|${word("innanzitutto")}")
}
