package com.simone.jarvismobile.widget

import com.simone.jarvismobile.core.state.ConversationState
import org.junit.Assert.assertEquals
import org.junit.Test

/** The widget status label is pure logic and must read correctly for every state. */
class JarvisWidgetStateTest {

    @Test
    fun `idle reads ready when a model is loaded`() {
        assertEquals("Pronto", JarvisWidgetState.label(ConversationState.Idle, modelReady = true))
    }

    @Test
    fun `idle reads offline with no model`() {
        assertEquals("Offline", JarvisWidgetState.label(ConversationState.Idle, modelReady = false))
    }

    @Test
    fun `listening states read ascolto`() {
        assertEquals("Ascolto…", JarvisWidgetState.label(ConversationState.Listening, true))
        assertEquals("Ascolto…", JarvisWidgetState.label(ConversationState.PreparingAudio, true))
    }

    @Test
    fun `thinking states read elaborazione`() {
        assertEquals("Elaborazione…", JarvisWidgetState.label(ConversationState.ThinkingLocal, true))
        assertEquals("Elaborazione…", JarvisWidgetState.label(ConversationState.Transcribing, true))
        assertEquals("Elaborazione…", JarvisWidgetState.label(ConversationState.ExecutingTool, true))
    }

    @Test
    fun `speaking reads parla`() {
        assertEquals("Parla…", JarvisWidgetState.label(ConversationState.Speaking, true))
    }

    @Test
    fun `a model-unavailable state is surfaced`() {
        assertEquals("Nessun modello", JarvisWidgetState.label(ConversationState.ModelUnavailable, true))
    }
}
