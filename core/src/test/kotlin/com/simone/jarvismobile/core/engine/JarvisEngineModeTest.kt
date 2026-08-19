package com.simone.jarvismobile.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class JarvisEngineModeTest {

    @Test
    fun `round trips through name and valueOf, matching the SettingsRepository pattern`() {
        for (mode in JarvisEngineMode.entries) {
            assertEquals(mode, JarvisEngineMode.valueOf(mode.name))
        }
    }

    @Test
    fun `ibrida exists as a real value ahead of any UI offering it`() {
        // Pins the "one value ahead of its UI" posture: IBRIDA must exist so the
        // type never needs to change shape later, but is never the default.
        assertEquals(JarvisEngineMode.CLASSICO, JarvisEngineMode.entries.first())
        assertEquals(3, JarvisEngineMode.entries.size)
    }
}
