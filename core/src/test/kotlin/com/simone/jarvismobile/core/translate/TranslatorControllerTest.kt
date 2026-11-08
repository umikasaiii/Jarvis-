package com.simone.jarvismobile.core.translate

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TranslatorControllerTest {
    private val IT = TranslationLanguage.ITALIAN
    private val EN = TranslationLanguage.ENGLISH
    private val JA = TranslationLanguage.JAPANESE

    private class Rig(scope: kotlinx.coroutines.CoroutineScope, val models: FakeModels = FakeModels(), detector: FakeDetector = FakeDetector(mapOf("ciao" to TranslationLanguage.ITALIAN, "hello" to TranslationLanguage.ENGLISH, "thanks" to TranslationLanguage.ENGLISH))) {
        val rec = FakeRecognizer(); val engine = FakeEngine(); val speaker = FakeSpeaker(); val prefs = FakePrefs()
        var now = 1_000L
        val c = TranslatorController(scope, rec, engine, speaker, detector, models, prefs, clock = { now }, settleDelayMs = 100, retryBaseMs = 10)
    }

    private fun session(mode: TranslationMode, a: TranslationLanguage = TranslationLanguage.ITALIAN, b: TranslationLanguage = TranslationLanguage.ENGLISH) =
        TranslationSession("s", a, b, mode)

    private fun TestScope.rig(models: FakeModels = FakeModels()) = Rig(backgroundScope, models)

    // ---------------------------------------------------------------- bidirectional

    @Test fun pushToTalkTranslatesBothWaysAndSpeaksInTheTargetLanguage() = runTest {
        val r = rig()
        r.c.start(session(TranslationMode.PUSH_TO_TALK)); runCurrent()
        assertEquals(LiveTranslationState.Ready, r.c.state.value)
        r.rec.script(Heard.Text("ciao a tutti")); r.c.pushToTalk(IT); runCurrent()
        r.rec.script(Heard.Text("hello everyone")); r.c.pushToTalk(EN); advanceTimeBy(1); runCurrent()
        val segs = r.c.segments.value
        assertEquals(listOf(IT to EN, EN to IT), segs.map { it.detectedLanguage to it.targetLanguage })
        assertEquals(listOf(EN, IT), r.speaker.spoken.map { it.second })
        assertEquals(listOf(IT, EN), r.rec.calls)
        assertEquals(LiveTranslationState.Ready, r.c.state.value)
    }

    @Test fun autoModeFlipsSidesOnlyAfterTheOtherLanguageIsHeardTwiceAndSaysSo() = runTest {
        val r = rig()
        r.rec.script(Heard.Text("ciao come stai"), Heard.Text("hello my friend"), Heard.Text("thanks a lot"))
        r.c.start(session(TranslationMode.AUTO)); advanceTimeBy(5_000); runCurrent()
        val segs = r.c.segments.value
        assertEquals(3, segs.size)
        assertEquals(IT, segs[0].detectedLanguage)
        // honest limit: the FIRST sentence of the other speaker is still treated as the active language
        assertEquals(IT, segs[1].detectedLanguage)
        assertEquals(EN, segs[2].detectedLanguage)
        assertEquals(EN, r.rec.calls.last())
        assertTrue("una lingua alla volta" in TranslatorMessages.MODE_LIMIT_AUTO)
    }

    @Test fun aDetectionOutsideThePairIsIgnoredNotMistranslated() = runTest {
        val r = Rig(backgroundScope, detector = FakeDetector(mapOf("hola" to TranslationLanguage.SPANISH)))
        r.rec.script(Heard.Text("hola amigos"))
        r.c.start(session(TranslationMode.AUTO)); advanceTimeBy(1_000); runCurrent()
        assertTrue(r.c.segments.value.isEmpty()); assertTrue(r.engine.calls.isEmpty())
    }

    // ---------------------------------------------------------------- models / offline

    @Test fun missingModelStopsBeforeListeningAndNeverTriesTheNetworkWhenOffline() = runTest {
        val r = rig(FakeModels(present = mutableSetOf(IT), online = false))
        r.c.start(session(TranslationMode.PUSH_TO_TALK)); runCurrent()
        assertEquals(LiveTranslationState.Error("model_missing:en"), r.c.state.value)
        assertEquals(TranslatorNotice.ModelMissing(listOf(EN)), r.c.notice.value)
        assertTrue(r.models.downloads.isEmpty()); assertTrue(r.rec.calls.isEmpty())
        assertTrue("scaricalo" in TranslatorMessages.error("model_missing:en"))
    }

    @Test fun nonEnglishPairAlsoNeedsTheEnglishPivotModel() = runTest {
        val r = rig(FakeModels(present = mutableSetOf(IT, JA), online = false))
        r.c.start(session(TranslationMode.PUSH_TO_TALK, IT, JA)); runCurrent()
        assertEquals(LiveTranslationState.Error("model_missing:en"), r.c.state.value)
        assertEquals(listOf(IT, JA, EN), TranslationModels.required(IT, JA))
        assertEquals(listOf(IT, EN), TranslationModels.required(IT, EN))
    }

    @Test fun whenOnlineMissingModelsAreFetchedThenTheSessionStarts() = runTest {
        val r = rig(FakeModels(present = mutableSetOf(IT), online = true))
        r.c.start(session(TranslationMode.PUSH_TO_TALK)); runCurrent()
        assertEquals(listOf(EN), r.models.downloads)
        assertEquals(LiveTranslationState.Ready, r.c.state.value)
    }

    @Test fun aFailedDownloadEndsInAClearModelMissingError() = runTest {
        val m = object : ModelGate {
            override suspend fun missing(langs: Collection<TranslationLanguage>) = listOf(EN)
            override suspend fun canDownloadNow() = true
            override suspend fun download(language: TranslationLanguage) = false
        }
        val c = TranslatorController(backgroundScope, FakeRecognizer(), FakeEngine(), FakeSpeaker(), FakeDetector(), m, FakePrefs())
        c.start(session(TranslationMode.PUSH_TO_TALK)); runCurrent()
        assertEquals(LiveTranslationState.Error("model_missing:en"), c.state.value)
    }

    // ---------------------------------------------------------------- text, no microphone

    @Test fun textTranslationWorksWithoutAnySessionOrMicrophone() = runTest {
        val r = rig()
        val out = r.c.translateText("  ciao  ", IT, EN)
        assertEquals("[en]ciao", (out as TextOutcome.Ok).text)
        assertTrue(r.rec.calls.isEmpty()); assertEquals(LiveTranslationState.Idle, r.c.state.value)
        assertTrue(r.models.downloads.isEmpty()) // offline: never touches the network
    }

    @Test fun textTranslationEdgeCases() = runTest {
        val r = rig(FakeModels(present = mutableSetOf(IT, EN)))
        assertEquals(TextOutcome.Empty, r.c.translateText("   ", IT, EN))
        assertEquals(TextOutcome.TooLong, r.c.translateText("x".repeat(TranslatorController.MAX_TEXT_CHARS + 1), IT, EN))
        assertEquals("ciao", (r.c.translateText("ciao", IT, IT) as TextOutcome.Ok).text)
        assertEquals(TextOutcome.ModelMissing(listOf(JA)), r.c.translateText("ciao", IT, JA).let { if (it is TextOutcome.ModelMissing) TextOutcome.ModelMissing(it.languages.filter { l -> l == JA }) else it })
        r.engine.failWith = "mlkit"; r.engine.failTimes = 1
        assertEquals(TextOutcome.Failed("mlkit"), r.c.translateText("ciao", IT, EN))
    }

    // ---------------------------------------------------------------- push-to-talk concurrency

    @Test fun twoDifferentButtonsAtOnceOpenOnlyOneListen() = runTest {
        val r = rig(); r.c.start(session(TranslationMode.PUSH_TO_TALK)); runCurrent()
        val first = r.rec.expect()
        r.c.pushToTalk(IT); runCurrent()
        r.c.pushToTalk(EN); runCurrent() // the interlocutor presses while the first is still listening
        assertEquals(listOf(IT), r.rec.calls)
        assertEquals(TranslatorNotice.Busy, r.c.notice.value)
        first.complete(Heard.Text("ciao")); runCurrent()
        assertEquals(1, r.c.segments.value.size)
    }

    @Test fun rapidDoublePressOfTheSameButtonCancelsInsteadOfDoubleTranscribing() = runTest {
        val r = rig(); r.c.start(session(TranslationMode.PUSH_TO_TALK)); runCurrent()
        r.rec.expect()
        r.c.pushToTalk(IT); runCurrent()
        r.c.pushToTalk(IT); runCurrent()
        assertEquals(1, r.rec.calls.size)
        assertEquals(LiveTranslationState.Ready, r.c.state.value)
        assertTrue(r.c.segments.value.isEmpty())
        // and the button works again afterwards
        r.rec.script(Heard.Text("ciao")); r.c.pushToTalk(IT); runCurrent()
        assertEquals(1, r.c.segments.value.size)
    }

    @Test fun pushToTalkIsIgnoredWhileTheTranslationIsBeingSpoken() = runTest {
        val r = rig(); r.speaker.gate = CompletableDeferred(); r.c.start(session(TranslationMode.PUSH_TO_TALK)); runCurrent()
        r.rec.script(Heard.Text("ciao")); r.c.pushToTalk(IT); runCurrent()
        assertEquals(LiveTranslationState.Speaking, r.c.state.value)
        r.c.pushToTalk(EN); runCurrent()
        assertEquals(1, r.rec.calls.size)
        assertEquals(TranslatorNotice.Busy, r.c.notice.value)
    }

    @Test fun pushToTalkOutsidePushToTalkModeOrWithoutSessionDoesNothing() = runTest {
        val r = rig()
        r.c.pushToTalk(IT); runCurrent()
        r.c.start(session(TranslationMode.AUTO)); runCurrent()
        r.c.pushToTalk(IT); runCurrent()
        assertEquals(1, r.rec.calls.size) // only the automatic loop's own listen
    }

    // ---------------------------------------------------------------- stop during every stage

    @Test fun stopWhileListeningDiscardsTheLateTranscript() = runTest {
        val r = rig(); r.c.start(session(TranslationMode.PUSH_TO_TALK)); runCurrent()
        val d = r.rec.expect(); r.c.pushToTalk(IT); runCurrent()
        r.c.stop(); d.complete(Heard.Text("ciao tardivo")); runCurrent()
        assertEquals(LiveTranslationState.Idle, r.c.state.value)
        assertTrue(r.c.segments.value.isEmpty()); assertTrue(r.engine.calls.isEmpty()); assertTrue(r.speaker.spoken.isEmpty())
        assertTrue(r.rec.cancels >= 1)
    }

    @Test fun stopWhileTranslatingPublishesNothingAndSpeaksNothing() = runTest {
        val r = rig(); r.engine.gate = CompletableDeferred(); r.c.start(session(TranslationMode.PUSH_TO_TALK)); runCurrent()
        r.rec.script(Heard.Text("ciao")); r.c.pushToTalk(IT); runCurrent()
        assertEquals(LiveTranslationState.Translating, r.c.state.value)
        r.c.stop(); r.engine.gate!!.complete(Unit); runCurrent()
        assertEquals(LiveTranslationState.Idle, r.c.state.value)
        assertTrue(r.c.segments.value.isEmpty()); assertTrue(r.speaker.spoken.isEmpty())
    }

    @Test fun stopWhileSpeakingSilencesTheVoiceAndNeverListensAgain() = runTest {
        val r = rig(); r.speaker.gate = CompletableDeferred()
        r.rec.script(Heard.Text("ciao uno")); r.c.start(session(TranslationMode.AUTO)); runCurrent()
        assertEquals(LiveTranslationState.Speaking, r.c.state.value)
        val listens = r.rec.calls.size
        r.c.stop(); advanceUntilIdle()
        assertTrue(r.speaker.stops >= 1)
        assertEquals(LiveTranslationState.Idle, r.c.state.value)
        assertEquals(listens, r.rec.calls.size)
    }

    @Test fun aLateResultOfAnOldSessionNeverLeaksIntoTheNewOne() = runTest {
        val r = rig(); r.c.start(session(TranslationMode.PUSH_TO_TALK)); runCurrent()
        val old = r.rec.expect(); r.c.pushToTalk(IT); runCurrent()
        r.c.stop()
        r.c.start(session(TranslationMode.PUSH_TO_TALK)); runCurrent()
        old.complete(Heard.Text("ciao vecchio")); runCurrent()
        assertTrue(r.c.segments.value.isEmpty())
        assertEquals(LiveTranslationState.Ready, r.c.state.value)
        // and the new session is not blocked by the old turn
        r.rec.script(Heard.Text("ciao nuovo")); r.c.pushToTalk(IT); runCurrent()
        assertEquals(1, r.c.segments.value.size)
    }

    @Test fun startingTwiceDoesNotStartTwoSessions() = runTest {
        val r = rig(); r.c.start(session(TranslationMode.AUTO)); r.c.start(session(TranslationMode.AUTO)); runCurrent()
        assertEquals(1, r.rec.calls.size)
    }

    // ---------------------------------------------------------------- pause / resume / swap / mode

    @Test fun pauseDropsTheTurnInFlightAndResumeRestartsListening() = runTest {
        val r = rig(); val d = r.rec.expect()
        r.c.start(session(TranslationMode.AUTO)); runCurrent()
        r.c.pause(); d.complete(Heard.Text("ciao")); runCurrent()
        assertEquals(LiveTranslationState.Paused, r.c.state.value)
        assertTrue(r.c.segments.value.isEmpty())
        val before = r.rec.calls.size
        r.c.resume(); runCurrent()
        assertEquals(before + 1, r.rec.calls.size)
        assertEquals(LiveTranslationState.Listening, r.c.state.value)
    }

    @Test fun resumeOnlyWorksFromPause() = runTest {
        val r = rig(); r.c.start(session(TranslationMode.PUSH_TO_TALK)); runCurrent()
        r.c.resume(); runCurrent()
        assertEquals(LiveTranslationState.Ready, r.c.state.value); assertTrue(r.rec.calls.isEmpty())
    }

    @Test fun swapChangesTheNextTurnButNotOneAlreadyRunning() = runTest {
        val r = rig(); r.c.start(session(TranslationMode.PUSH_TO_TALK)); runCurrent()
        val d = r.rec.expect(); r.c.pushToTalk(IT); runCurrent()
        r.c.swap(); d.complete(Heard.Text("ciao")); runCurrent()
        val seg = r.c.segments.value.single()
        assertEquals(IT to EN, seg.detectedLanguage to seg.targetLanguage) // started as IT→EN
        assertEquals(EN, r.c.session.value!!.languageA)
        r.rec.script(Heard.Text("hello")); r.c.pushToTalk(EN); runCurrent() // EN is now the A side → target IT
        assertEquals(IT, r.c.segments.value.last().targetLanguage)
    }

    @Test fun changingModeLiveSwitchesBetweenPushToTalkAndTheAutomaticLoop() = runTest {
        val r = rig(); r.c.start(session(TranslationMode.PUSH_TO_TALK)); runCurrent()
        assertTrue(r.rec.calls.isEmpty())
        r.c.changeMode(TranslationMode.AUTO); runCurrent()
        assertEquals(1, r.rec.calls.size) // the loop is now listening
        r.c.changeMode(TranslationMode.PUSH_TO_TALK); runCurrent()
        val after = r.rec.calls.size
        advanceTimeBy(10_000); runCurrent()
        assertEquals(after, r.rec.calls.size) // no loop any more
        assertEquals(LiveTranslationState.Ready, r.c.state.value)
        assertEquals(TranslationMode.PUSH_TO_TALK, r.c.session.value!!.mode)
    }

    @Test fun changingModeWhilePausedKeepsItPaused() = runTest {
        val r = rig(); r.c.start(session(TranslationMode.AUTO)); runCurrent(); r.c.pause()
        r.c.changeMode(TranslationMode.PUSH_TO_TALK)
        assertEquals(LiveTranslationState.Paused, r.c.state.value)
    }

    // ---------------------------------------------------------------- errors

    @Test fun aFailedTranslationIsReportedAndTheLoopKeepsGoing() = runTest {
        val r = rig(); r.engine.failTimes = 1; r.engine.failWith = "x"
        r.rec.script(Heard.Text("ciao uno"), Heard.Text("ciao due"))
        r.c.start(session(TranslationMode.AUTO)); advanceTimeBy(5_000); runCurrent()
        assertEquals(1, r.c.segments.value.size)
        assertEquals("ciao due", r.c.segments.value.single().sourceText)
        assertEquals(TranslatorNotice.TranslateFailed("x"), r.c.notice.value)
    }

    @Test fun repeatedRecognizerFailuresBackOffAndEndInAnError() = runTest {
        val r = rig(); r.rec.script(*Array(10) { Heard.Failure("e$it") })
        r.c.start(session(TranslationMode.AUTO)); advanceTimeBy(60_000); runCurrent()
        assertEquals(LiveTranslationState.Error("stt_failed"), r.c.state.value)
        assertEquals(TranslatorController.MAX_FAILURES, r.rec.calls.size) // no hot loop
    }

    @Test fun aThrowingRecognizerDoesNotCrashTheSession() = runTest {
        val r = rig(); r.rec.throwOnNext = IllegalStateException("boom")
        r.rec.script(Heard.Text("ciao dopo"))
        r.c.start(session(TranslationMode.AUTO)); advanceTimeBy(5_000); runCurrent()
        assertEquals(1, r.c.segments.value.size)
    }

    @Test fun anUnavailableRecognizerLanguageIsExplainedAndEndsTheLoop() = runTest {
        val r = rig(); r.rec.script(Heard.LanguageUnavailable(JA))
        r.c.start(session(TranslationMode.AUTO, JA, EN)); runCurrent()
        assertEquals(LiveTranslationState.Error("stt_language:ja"), r.c.state.value)
        assertEquals(TranslatorNotice.RecognizerLanguageMissing(JA), r.c.notice.value)
        assertTrue("Giapponese".lowercase() in TranslatorMessages.error("stt_language:ja").lowercase() || "日本語" in TranslatorMessages.error("stt_language:ja"))
        // text translation still works for that language pair
        assertTrue(r.c.translateText("hello", EN, JA) is TextOutcome.Ok)
    }

    @Test fun aMissingOfflineVoiceKeepsTheTextAndTellsTheUser() = runTest {
        val r = rig(); r.speaker.outcome = SpeakOutcome.NO_VOICE
        r.rec.script(Heard.Text("ciao")); r.c.start(session(TranslationMode.AUTO)); advanceTimeBy(1_000); runCurrent()
        assertEquals(1, r.c.segments.value.size)
        assertEquals(TranslatorNotice.NoVoice(EN), r.c.notice.value)
    }

    @Test fun speechCanBeTurnedOffAndThenNothingIsSpoken() = runTest {
        val r = rig(); r.prefs.tts = false
        r.rec.script(Heard.Text("ciao")); r.c.start(session(TranslationMode.AUTO)); advanceTimeBy(1_000); runCurrent()
        assertEquals(1, r.c.segments.value.size); assertTrue(r.speaker.spoken.isEmpty())
    }

    @Test fun textOnlyModesNeverSpeak() = runTest {
        val r = rig()
        r.rec.script(Heard.Text("ciao")); r.c.start(session(TranslationMode.SOLO_TESTO)); advanceTimeBy(1_000); runCurrent()
        assertEquals(1, r.c.segments.value.size); assertTrue(r.speaker.spoken.isEmpty())
    }

    // ---------------------------------------------------------------- echo / duplicates

    @Test fun theOwnSpokenTranslationHeardBackIsDropped() = runTest {
        val r = rig()
        r.rec.script(Heard.Text("ciao a tutti"), Heard.Text("[en]ciao a tutti"))
        r.c.start(session(TranslationMode.AUTO)); advanceTimeBy(5_000); runCurrent()
        assertEquals(1, r.c.segments.value.size)
    }

    @Test fun anIdenticalResultRightAfterTheFirstIsNotTranscribedTwice() = runTest {
        val r = rig(); r.prefs.tts = false
        r.rec.script(Heard.Text("ciao a tutti"), Heard.Text("Ciao a tutti!"))
        r.c.start(session(TranslationMode.AUTO)); advanceTimeBy(5_000); runCurrent()
        assertEquals(1, r.c.segments.value.size)
    }

    @Test fun listeningNeverOverlapsSpeaking() = runTest {
        val r = rig(); r.speaker.gate = CompletableDeferred()
        r.rec.script(Heard.Text("ciao uno"), Heard.Text("ciao due"))
        r.c.start(session(TranslationMode.AUTO)); runCurrent()
        assertEquals(1, r.rec.calls.size) // still speaking → the mic is not open
        r.speaker.gate!!.complete(Unit); runCurrent()
        assertEquals(1, r.rec.calls.size) // settle pause before listening again
        advanceTimeBy(150); runCurrent()
        assertEquals(2, r.rec.calls.size)
    }

    @Test fun clearingNoticeAndTranscript() = runTest {
        val r = rig(); r.prefs.tts = false
        r.rec.script(Heard.Text("ciao")); r.c.start(session(TranslationMode.AUTO)); advanceTimeBy(1_000); runCurrent()
        r.c.clearTranscript(); assertTrue(r.c.segments.value.isEmpty())
        r.c.stop(); assertNull(r.c.session.value); assertFalse(r.c.isRunning)
    }
}
