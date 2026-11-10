package com.simone.jarvismobile.core.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The strings are already normalised the way CommandMatcher.normalize does (lower case, no accents, no polite prefix). */
class PhoneCommandGrammarTest {
    @Test fun aKnowledgeOrChatSentenceNeverLooksLikeACallRequest() {
        listOf(
            "quale dei due conserva i dati anche quando spengo il telefono",
            "ricorda che il mio animale immaginario si chiama nebulix. come si chiama",
            "come si chiama la capitale della francia",
            "quanta ram ha il telefono",
            "il mio telefono e lento, che faccio",
            "mi telefona mia madre ogni giorno",
            "ho dimenticato il telefono a casa",
            "la chiamata e caduta ieri",
            "devo chiamare il dentista",
            "ricordami di chiamare il dottore domani",
            "chiamami tra 10 minuti",
        ).forEach { assertFalse(PhoneCommandGrammar.isDialRequest(it), it) }
    }

    @Test fun explicitCallRequestsAreRecognised() {
        listOf(
            "chiama il numero 3331234567",
            "chiama marco",
            "chiama",
            "telefona al 061234567",
            "telefonare al +39 333 123 4567",
            "chiamare mia madre",
            "ciao jarvis, chiama marco",
            "ok jarvis chiama il 061234567",
            "per favore chiama il 333 1234567",
            "prepara una chiamata al 333 1234567",
            "apri il dialer",
        ).forEach { assertTrue(PhoneCommandGrammar.isDialRequest(it), it) }
    }

    @Test fun smsNeedsAnOpeningSendingVerbAndAnSmsOrANumber() {
        assertTrue(PhoneCommandGrammar.isSmsRequest("prepara un sms al +39 333 123 4567: arrivo tra poco", true))
        assertTrue(PhoneCommandGrammar.isSmsRequest("manda un messaggio al 3331234567 dicendo arrivo", true))
        assertTrue(PhoneCommandGrammar.isSmsRequest("sms al 3331234567: ciao", true))
        assertTrue(PhoneCommandGrammar.isSmsRequest("scrivi un sms a marco", false))
    }

    @Test fun writingARequestForTheModelIsNotAnSms() {
        assertFalse(PhoneCommandGrammar.isSmsRequest("scrivi un messaggio di auguri per mia madre", false))
        assertFalse(PhoneCommandGrammar.isSmsRequest("ho ricevuto un sms ieri, cos'e", false))
        assertFalse(PhoneCommandGrammar.isSmsRequest("quanti sms posso mandare al giorno", false))
        assertFalse(PhoneCommandGrammar.isSmsRequest("il messaggio che ho scritto era lungo", false))
    }

    @Test fun aModelProposalNeedsAPhoneNumberAndCoherentVocabularyInTheCurrentTurn() {
        assertTrue(PhoneCommandGrammar.modelProposedCallHasEvidence("fammi una telefonata al 3331234567", true))
        assertFalse(PhoneCommandGrammar.modelProposedCallHasEvidence("fammi una telefonata a marco", false))
        assertFalse(PhoneCommandGrammar.modelProposedCallHasEvidence("il mio numero e 3331234567", true)) // a number alone is not a call request
        assertTrue(PhoneCommandGrammar.modelProposedSmsHasEvidence("un messaggio al 3331234567 arrivo", true))
        assertFalse(PhoneCommandGrammar.modelProposedSmsHasEvidence("un messaggio per marco", false))
    }

    @Test fun phoneNumbersComeOnlyFromTheUsersWords() {
        assertEquals("3331234567", PhoneNumbers.extract("chiama il numero 3331234567"))
        assertEquals("+393331234567", PhoneNumbers.extract("telefonare al +39 333 123 4567"))
        assertEquals("061234567", PhoneNumbers.extract("telefona al 06-1234567".replace("-", "")))
        assertNull(PhoneNumbers.extract("chiama marco"))
        assertNull(PhoneNumbers.extract("ho 2 gatti")) // fewer than 3 digits
        assertNull(PhoneNumbers.extract("1234567890123456789")) // more than 15 digits
    }
}

class SlotAnswerPolicyTest {
    private fun assess(tool: String, slot: String, reply: String, ago: Long = 1_000) = SlotAnswerPolicy.assess(tool, slot, reply, ago)

    @Test fun aCallSlotAcceptsOnlyAPhoneNumber() {
        assertEquals(SlotAnswerVerdict.ANSWER, assess("prepare_call", "number", "3331234567"))
        assertEquals(SlotAnswerVerdict.ANSWER, assess("prepare_call", "number", "il 333 123 4567"))
        assertEquals(SlotAnswerVerdict.NOT_AN_ANSWER, assess("prepare_call", "number", "Ricorda che il mio animale immaginario si chiama Nebulix. Come si chiama?"))
        assertEquals(SlotAnswerVerdict.NOT_AN_ANSWER, assess("prepare_call", "number", "Quale dei due conserva i dati anche quando spengo il telefono?"))
        assertEquals(SlotAnswerVerdict.NOT_AN_ANSWER, assess("compose_sms", "number", "Ciao Jarvis, presentati"))
        assertEquals(SlotAnswerVerdict.NOT_AN_ANSWER, assess("prepare_call", "number", "   "))
    }

    @Test fun aStaleQuestionNeverCapturesALaterMessage() {
        assertEquals(SlotAnswerVerdict.EXPIRED, assess("prepare_call", "number", "3331234567", ago = SlotAnswerPolicy.TTL_MS + 1))
        assertEquals(SlotAnswerVerdict.ANSWER, assess("prepare_call", "number", "3331234567", ago = SlotAnswerPolicy.TTL_MS))
    }

    @Test fun aQuestionIsNeverTheValueOfAFreeTextSlot() {
        assertEquals(SlotAnswerVerdict.NOT_AN_ANSWER, assess("navigate", "destination", "Quanto ci vuole per arrivare a Roma?"))
        assertEquals(SlotAnswerVerdict.NOT_AN_ANSWER, assess("search_vault", "query", "Cosa c'e nel vault?"))
        assertEquals(SlotAnswerVerdict.ANSWER, assess("navigate", "destination", "Piazza Navona"))
    }

    @Test fun aLongSentenceIsNotADestinationOrAQuery() {
        val long = "Ricorda che il mio animale immaginario si chiama Nebulix e vive su una luna lontana"
        assertEquals(SlotAnswerVerdict.NOT_AN_ANSWER, assess("navigate", "destination", long))
        assertEquals(SlotAnswerVerdict.NOT_AN_ANSWER, assess("play_media", "query", long))
        // but a note's text may be long: it IS the value of that slot
        assertEquals(SlotAnswerVerdict.ANSWER, assess("remember", "text", long))
    }

    @Test fun timeAndDateSlotsKeepWorkingExactlyAsBefore() {
        assertEquals(SlotAnswerVerdict.ANSWER, assess("set_timer", "seconds", "dieci minuti"))
        assertEquals(SlotAnswerVerdict.ANSWER, assess("set_alarm", "time", "alle sette e mezza"))
        assertEquals(SlotAnswerVerdict.ANSWER, assess("add_reminder", "when", "domani alle 15"))
        assertEquals(SlotAnswerVerdict.ANSWER, assess("remember", "text", "comprare il latte"))
    }

    @Test fun theSameQuestionTwiceIsNotProgress() {
        assertTrue(SlotAnswerPolicy.isRepeatOfSameQuestion("prepare_call", "number", "prepare_call", "number"))
        assertFalse(SlotAnswerPolicy.isRepeatOfSameQuestion("add_reminder", "text", "add_reminder", "when"))
    }
}
