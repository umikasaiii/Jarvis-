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
    fun `work vibrates, driving listens for the wake word and filters notifications`() {
        assertEquals(RingerPreference.VIBRATE, JarvisModes.profile("work")!!.ringer)
        val driving = JarvisModes.profile(JarvisModes.DRIVING)!!
        assertTrue(driving.wakeWord)
        assertTrue(driving.doNotDisturb, "driving must filter chatter, only urgent things through")
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

    @Test
    fun `driving asks for the tightest GPS effort, home and sleep the loosest`() {
        assertEquals(LocationPrecision.HIGH_PRECISION, JarvisModes.profile(JarvisModes.DRIVING)!!.locationPrecision)
        assertEquals(LocationPrecision.LOW_POWER, JarvisModes.profile(JarvisModes.HOME)!!.locationPrecision)
        assertEquals(LocationPrecision.LOW_POWER, JarvisModes.profile(JarvisModes.SLEEP)!!.locationPrecision)
        assertEquals(LocationPrecision.BALANCED, JarvisModes.profile(JarvisModes.WORK)!!.locationPrecision)
    }
}
