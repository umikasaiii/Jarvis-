package com.simone.jarvismobile.core.translate

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TranslatorSupportTest {
    @Test fun echoOfTheSpokenTranslationIsDroppedOnlyInsideTheWindow() {
        val g = EchoGuard(windowMs = 1_000)
        g.recordSpoken("Hello how are you", 0)
        assertTrue(g.shouldDrop("hello how are you", 500))
        val g2 = EchoGuard(windowMs = 1_000); g2.recordSpoken("Hello how are you", 0)
        assertFalse(g2.shouldDrop("hello how are you", 5_000))
    }

    @Test fun partialOverlapBelowTheThresholdIsNotEcho() {
        val g = EchoGuard(); g.recordSpoken("the weather is nice today in Rome", 0)
        assertFalse(g.shouldDrop("what time is the train", 100))
    }

    @Test fun japaneseEchoUsesCharacterBigrams() {
        val g = EchoGuard(); g.recordSpoken("おはようございます", 0)
        assertTrue(g.shouldDrop("おはようございます", 100))
        assertFalse(EchoGuard().also { it.recordSpoken("おはようございます", 0) }.shouldDrop("ありがとう", 100))
    }

    @Test fun repeatedIdenticalResultsAreDropped() {
        val g = EchoGuard()
        assertFalse(g.shouldDrop("buongiorno a tutti", 0))
        assertTrue(g.shouldDrop("Buongiorno a tutti.", 1_000))
        assertFalse(g.shouldDrop("buongiorno a tutti", 9_000))
    }

    @Test fun emptyResultsAreDropped() = assertTrue(EchoGuard().shouldDrop("  ", 0))

    @Test fun everyStateAndNoticeHasAnItalianMessage() {
        val states = listOf(
            LiveTranslationState.Idle, LiveTranslationState.CheckingModels, LiveTranslationState.DownloadingModels(TranslationLanguage.JAPANESE),
            LiveTranslationState.Ready, LiveTranslationState.Listening, LiveTranslationState.Translating, LiveTranslationState.Speaking,
            LiveTranslationState.Paused, LiveTranslationState.Error("model_missing:ja"), LiveTranslationState.Error("stt_failed"),
        )
        states.forEach { assertTrue(TranslatorMessages.state(it, TranslationLanguage.ITALIAN).isNotBlank()) }
        listOf(
            TranslatorNotice.Busy, TranslatorNotice.NoVoice(TranslationLanguage.JAPANESE), TranslatorNotice.SpeakFailed,
            TranslatorNotice.TranslateFailed("x"), TranslatorNotice.ModelMissing(listOf(TranslationLanguage.FRENCH)),
            TranslatorNotice.RecognizerLanguageMissing(TranslationLanguage.SPANISH), TranslatorNotice.RecognizerUnavailable,
        ).forEach { assertTrue(TranslatorMessages.notice(it).isNotBlank()) }
        assertFalse(TranslatorMessages.state(LiveTranslationState.Error("stt_failed"), null).startsWith("Errore: "))
    }

    @Test fun theAutomaticModeLimitIsNeverPresentedAsSimultaneousRecognition() {
        assertTrue("Non è un riconoscimento simultaneo" in TranslatorMessages.MODE_LIMIT_AUTO)
    }
}
