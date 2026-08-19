package com.simone.jarvismobile.core.engine

import com.simone.jarvismobile.core.protocol.AssistantResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BrainEventTest {

    @Test
    fun `emits one sentence per SpeechShaper segment, then Done, last`() {
        val response = AssistantResponse(assistantText = "Fatto. Ho spostato l'impegno alle 18.")
        val events = SentenceStream.from(response)

        assertTrue(events.isNotEmpty())
        assertIs<BrainEvent.Done>(events.last())
        val sentences = events.dropLast(1)
        assertTrue(sentences.isNotEmpty())
        sentences.forEach { assertIs<BrainEvent.Sentence>(it) }
        assertEquals(response, (events.last() as BrainEvent.Done).response)
    }

    @Test
    fun `blank assistant text yields only Done, never a blank Sentence`() {
        val response = AssistantResponse(assistantText = "")
        val events = SentenceStream.from(response)
        assertEquals(1, events.size)
        assertIs<BrainEvent.Done>(events.single())
    }

    @Test
    fun `a clarification question streams the same way as a plain reply`() {
        val response = AssistantResponse(assistantText = "Per quando lo sposto?", followUpExpected = true)
        val events = SentenceStream.from(response)
        assertTrue(events.first() is BrainEvent.Sentence)
        assertEquals("Per quando lo sposto?", (events.first() as BrainEvent.Sentence).text)
    }
}
