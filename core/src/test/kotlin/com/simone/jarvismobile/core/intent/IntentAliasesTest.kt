package com.simone.jarvismobile.core.intent

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [IntentAliases.isListeningCancelWord] is the "ok" stop-word a wake-word or
 * accidentally-triggered listening session reads as "stop listening" instead
 * of as a command. It must recognize the bare word in its common spoken forms
 * and — just as importantly — never fire on a sentence that merely starts
 * with a vocative "ok" or that happens to answer a real pending confirmation,
 * since [IntentAliases.AFFIRMATIVE] already owns that meaning.
 */
class IntentAliasesTest {

    @Test
    fun bareOkAndVariantsAreListeningCancelWords() {
        assertTrue(IntentAliases.isListeningCancelWord("ok"))
        assertTrue(IntentAliases.isListeningCancelWord("Ok"))
        assertTrue(IntentAliases.isListeningCancelWord("Ok."))
        assertTrue(IntentAliases.isListeningCancelWord("okay"))
        assertTrue(IntentAliases.isListeningCancelWord("  ok  "))
    }

    @Test
    fun sentencesThatMerelyContainOkAreNotCancelWords() {
        assertFalse(IntentAliases.isListeningCancelWord("ok accendi la torcia"))
        assertFalse(IntentAliases.isListeningCancelWord("va bene"))
        assertFalse(IntentAliases.isListeningCancelWord("perfetto"))
        assertFalse(IntentAliases.isListeningCancelWord(""))
    }

    @Test
    fun okStillAnswersAffirmativeWhenSomethingIsPending() {
        // isListeningCancelWord itself doesn't know about pending state — callers
        // gate it on "nothing pending" — but isAffirmative must keep matching "ok"
        // for the real confirmation-answer path, unaffected by the new function.
        assertTrue(IntentAliases.isAffirmative("ok"))
    }
}
