package com.simone.jarvismobile.core.driving

import com.simone.jarvismobile.core.state.ConversationState
import kotlin.test.Test
import kotlin.test.assertEquals

class DrivingModelsTest {

    @Test
    fun `expanding one panel implicitly closes the other, never both open`() {
        val messagesOpen = DrivingModeState().expand(DrivingExpandedPanel.MESSAGES)
        assertEquals(DrivingExpandedPanel.MESSAGES, messagesOpen.expandedPanel)

        val mediaOpen = messagesOpen.expand(DrivingExpandedPanel.MEDIA)
        assertEquals(DrivingExpandedPanel.MEDIA, mediaOpen.expandedPanel, "opening media must close messages")
    }

    @Test
    fun `toggle collapses an already-open panel, expands a closed one`() {
        val closed = DrivingModeState()
        val opened = closed.toggle(DrivingExpandedPanel.MEDIA)
        assertEquals(DrivingExpandedPanel.MEDIA, opened.expandedPanel)

        val reClosed = opened.toggle(DrivingExpandedPanel.MEDIA)
        assertEquals(DrivingExpandedPanel.NONE, reClosed.expandedPanel)

        val switched = opened.toggle(DrivingExpandedPanel.MESSAGES)
        assertEquals(DrivingExpandedPanel.MESSAGES, switched.expandedPanel, "toggling the other panel switches, not stacks")
    }

    @Test
    fun `collapse always returns to NONE regardless of what was open`() {
        assertEquals(
            DrivingExpandedPanel.NONE,
            DrivingModeState().expand(DrivingExpandedPanel.MESSAGES).collapse().expandedPanel,
        )
    }

    @Test
    fun `every conversation state maps to exactly one of the four voice labels`() {
        assertEquals(DrivingVoiceState.READY, ConversationState.Idle.toDrivingVoiceState())
        assertEquals(DrivingVoiceState.READY, ConversationState.FollowUpWindow.toDrivingVoiceState())
        assertEquals(DrivingVoiceState.READY, ConversationState.NetworkUnavailable.toDrivingVoiceState())
        assertEquals(DrivingVoiceState.READY, ConversationState.RecoverableError("x").toDrivingVoiceState())
        assertEquals(DrivingVoiceState.READY, ConversationState.FatalError("x").toDrivingVoiceState())

        assertEquals(DrivingVoiceState.LISTENING, ConversationState.PreparingAudio.toDrivingVoiceState())
        assertEquals(DrivingVoiceState.LISTENING, ConversationState.Listening.toDrivingVoiceState())

        assertEquals(DrivingVoiceState.PROCESSING, ConversationState.Transcribing.toDrivingVoiceState())
        assertEquals(DrivingVoiceState.PROCESSING, ConversationState.ThinkingLocal.toDrivingVoiceState())
        assertEquals(DrivingVoiceState.PROCESSING, ConversationState.ExecutingTool.toDrivingVoiceState())

        assertEquals(DrivingVoiceState.REPLYING, ConversationState.Speaking.toDrivingVoiceState())
    }
}
