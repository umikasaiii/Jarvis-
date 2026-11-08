package com.simone.jarvismobile.core.translate

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation

/** Hand-driven fakes for [TranslatorController]. Nothing here is a real recognizer, translator or voice. */
class FakeRecognizer : Recognizer {
    val calls = mutableListOf<TranslationLanguage>()
    var cancels = 0
    private val queued = ArrayDeque<CompletableDeferred<Heard>>()
    private val pending = mutableListOf<CompletableDeferred<Heard>>()
    var throwOnNext: Throwable? = null

    /** Next [listen] waits for the test to complete the returned deferred. */
    fun expect(): CompletableDeferred<Heard> = CompletableDeferred<Heard>().also { queued.addLast(it) }

    /** Next listens return these results immediately, in order. */
    fun script(vararg results: Heard) { results.forEach { r -> queued.addLast(CompletableDeferred(r)) } }

    override suspend fun listen(language: TranslationLanguage): Heard {
        calls += language
        throwOnNext?.let { throwOnNext = null; throw it }
        val d = queued.removeFirstOrNull() ?: CompletableDeferred<Heard>() // script exhausted: wait forever
        pending += d
        return try { d.await() } finally { pending -= d }
    }

    override fun cancel() { cancels++; pending.toList().forEach { it.complete(Heard.Silence) } }
}

class FakeEngine : TranslationEngine {
    override val id = "fake"
    override fun isOffline(sourceLanguage: String, targetLanguage: String) = true
    val calls = mutableListOf<Triple<String, String, String>>()
    var gate: CompletableDeferred<Unit>? = null
    var failWith: String? = null
    var failTimes = 0
    override suspend fun translate(text: String, sourceLanguage: String, targetLanguage: String): TranslationResult {
        calls += Triple(text, sourceLanguage, targetLanguage)
        gate?.await()
        if (failTimes > 0) { failTimes--; return TranslationResult.Failure(failWith ?: "boom") }
        failWith?.let { if (failTimes == 0 && it.isNotEmpty() && gate == null && always) return TranslationResult.Failure(it) }
        return TranslationResult.Ok("[$targetLanguage]$text", sourceLanguage, targetLanguage, 5)
    }
    var always = false
}

class FakeSpeaker : Speaker {
    val spoken = mutableListOf<Pair<String, TranslationLanguage>>()
    var stops = 0
    var outcome = SpeakOutcome.SPOKEN
    var gate: CompletableDeferred<Unit>? = null
    override suspend fun speak(text: String, language: TranslationLanguage): SpeakOutcome {
        spoken += text to language
        val g = gate
        if (g != null) { try { g.await() } catch (e: kotlinx.coroutines.CancellationException) { throw e } }
        return outcome
    }
    override fun stop() { stops++; gate?.complete(Unit) }
}

class FakeDetector(private val byContains: Map<String, TranslationLanguage> = emptyMap(), private val conf: Float = 0.9f) : LanguageDetector {
    override suspend fun detect(text: String): Detection {
        val hit = byContains.entries.firstOrNull { text.contains(it.key, ignoreCase = true) }
        return if (hit == null) Detection(null, 0f) else Detection(hit.value, conf)
    }
}

class FakeModels(
    val present: MutableSet<TranslationLanguage> = TranslationLanguage.entries.toMutableSet(),
    var online: Boolean = false,
) : ModelGate {
    val downloads = mutableListOf<TranslationLanguage>()
    override suspend fun missing(langs: Collection<TranslationLanguage>) = langs.filter { it !in present }
    override suspend fun canDownloadNow() = online
    override suspend fun download(language: TranslationLanguage): Boolean {
        downloads += language
        if (online) present += language
        return online
    }
}

class FakePrefs(var tts: Boolean = true) : TranslatorPrefs {
    override suspend fun ttsEnabled() = tts
}

@Suppress("unused")
suspend fun neverReturns(): Nothing = awaitCancellation()
