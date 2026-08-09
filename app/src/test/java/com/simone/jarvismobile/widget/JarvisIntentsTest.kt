package com.simone.jarvismobile.widget

import com.simone.jarvismobile.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The deep-link hosts the widgets/notifications fire must stay in lockstep with
 * the hosts MainActivity parses, or a widget tap would silently do nothing.
 */
class JarvisIntentsTest {

    @Test
    fun `intent hosts are the agreed deep-link contract`() {
        assertEquals("voice", JarvisIntents.HOST_VOICE)
        assertEquals("chat", JarvisIntents.HOST_CHAT)
        assertEquals("stop", JarvisIntents.HOST_STOP)
        assertEquals("jarvis", JarvisIntents.SCHEME)
    }

    @Test
    fun `the stop action string is the one the manifest registers`() {
        assertEquals("com.simone.jarvismobile.action.STOP", JarvisActionReceiver.ACTION_STOP)
    }

    @Test
    fun `chat deep-link host is recognised by the activity parser`() {
        // Guards against renaming one side of the contract without the other.
        assertEquals("chat", MainActivity.jarvisHostForTest("jarvis", "chat"))
        assertEquals(null, MainActivity.jarvisHostForTest("http", "chat"))
    }
}
