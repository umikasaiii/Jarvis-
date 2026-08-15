package com.simone.jarvismobile.core.mode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JarvisModesTest {

    @Test
    fun `sleep is a zen mode, not a mute`() {
        val sleep = JarvisModes.profile(JarvisModes.SLEEP)!!
        assertEquals(RingerPreference.SILENT, sleep.ringer)
        assertTrue(sleep.doNotDisturb, "SLEEP must keep alarms/priority via DND, not just silence")
    }

    @Test
    fun `work vibrates, driving listens for the wake word`() {
        assertEquals(RingerPreference.VIBRATE, JarvisModes.profile("work")!!.ringer)
        assertTrue(JarvisModes.profile(JarvisModes.DRIVING)!!.wakeWord)
        assertFalse(JarvisModes.profile(JarvisModes.HOME)!!.wakeWord)
    }

    @Test
    fun `lookup is case-insensitive and rejects the unknown`() {
        assertEquals(JarvisModes.HOME, JarvisModes.profile("  home ")!!.id)
        assertNull(JarvisModes.profile("teleport"))
        assertNull(JarvisModes.profile(null))
        assertFalse(JarvisModes.isKnown("nope"))
    }

    @Test
    fun `mode ids are unique`() {
        assertEquals(JarvisModes.all.size, JarvisModes.ids.toSet().size)
    }
}
