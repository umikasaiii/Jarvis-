package com.simone.jarvismobile.translate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * BETA 5/8 — source-level guards (JVM) for the translator wiring. They prove what the code is connected to and
 * what it cannot do (no cloud/LLM/Core PC dependency); they do NOT prove that the real on-device recognizer,
 * ML Kit or the TTS voices behave on a phone — that needs the Honor 200.
 */
class TranslatorGuardTest {
    private val root: File = listOf(File("src/main/java/com/simone/jarvismobile"), File("app/src/main/java/com/simone/jarvismobile"))
        .firstOrNull { it.isDirectory } ?: error("app sources not found from ${File(".").absolutePath}")

    private fun src(rel: String) = File(root, rel).also { check(it.isFile) { "missing $rel" } }.readText()

    private val network = Regex("""okhttp3|HttpURLConnection|java\.net\.URL\b|URLConnection|OkHttpClient|retrofit|WebView|firebase|openai""", RegexOption.IGNORE_CASE)

    @Test fun theTranslatorPackageHasNoCloudApiAndNoDependencyOnTheAssistantLlmOrCorePc() {
        val files = File(root, "translate").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() +
            File(root, "ui/livetranslate").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(files.size >= 8)
        files.forEach {
            val t = it.readText()
            assertFalse("network API in ${it.name}", network.containsMatchIn(t))
            assertFalse("LLM/Core PC referenced from ${it.name}", Regex("""LlmRouter|LitertLm|LlmEngine|AiRouter|RemoteAiEngine""").containsMatchIn(t))
        }
    }

    @Test fun theManagerIsOnlyAnAdapterOverTheTestedController() {
        val m = src("translate/LiveTranslatorManager.kt")
        assertTrue("TranslatorController(" in m)
        assertFalse("while (" in m) // no control loop of its own any more
        assertFalse(Regex("""\.launch\s*\{[^}]*stt\.transcribe""").containsMatchIn(m))
        listOf("fun start(", "fun pushToTalk(", "fun pause(", "fun resume(", "fun stop(", "fun swap(", "fun changeMode(", "suspend fun translateText(")
            .forEach { assertTrue(it, it in m) }
    }

    @Test fun recognitionIsOnDeviceOnlyAndAnUnavailableLanguageIsReportedNotSwallowed() {
        val stt = src("audio/AndroidOnDeviceSpeechEngine.kt")
        assertTrue("createOnDeviceSpeechRecognizer" in stt)
        assertTrue("EXTRA_PREFER_OFFLINE" in stt)
        assertFalse("createSpeechRecognizer(" in stt.replace("createOnDeviceSpeechRecognizer", ""))
        val m = src("translate/LiveTranslatorManager.kt")
        assertTrue("Heard.LanguageUnavailable" in m && "startsWith(\"language:\")" in m)
    }

    @Test fun aMissingOfflineVoiceNeverSpeaksWithTheWrongVoiceAndFallsBackToText() {
        val a = src("translate/TranslationAudioController.kt")
        val speak = a.substringAfter("suspend fun speak(").substringBefore("fun stop()")
        val noVoice = speak.indexOf("SpeakOutcome.NO_VOICE")
        val spokeCall = speak.indexOf("engine.speak(")
        assertTrue(noVoice in 0 until spokeCall) // the NO_VOICE return comes BEFORE any engine.speak
    }

    @Test fun downloadsOnlyWhenTheNetworkAndThePolicyAllowIt() {
        val m = src("translate/LiveTranslatorManager.kt")
        val gate = m.substringAfter("override suspend fun canDownloadNow()").substringBefore("override suspend fun download")
        assertTrue("NET_CAPABILITY_INTERNET" in gate && "translateWifiOnly" in gate)
    }

    @Test fun theScreenOffersTextTranslationPauseResumeLiveModeChangeAndStatesTheAutoLimit() {
        val ui = src("ui/livetranslate/LiveTranslatorScreen.kt")
        listOf("Traduci un testo", "Riprendi", "Pausa", "Inverti", "MODE_LIMIT_AUTO", "tocca per annullare").forEach { assertTrue(it, it in ui) }
        assertTrue("onClick = { viewModel.setMode(m) }" in ui) // chips are not disabled while a session runs
        val vm = src("ui/livetranslate/LiveTranslatorViewModel.kt")
        assertTrue("manager.changeMode(mode)" in vm)
    }

    @Test fun roomSchemaIsUnchangedByThisStep() {
        assertTrue("version = 15" in src("background/JarvisDatabase.kt"))
    }
}
