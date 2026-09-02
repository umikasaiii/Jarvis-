package com.simone.jarvismobile.core.ai

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure coverage for [JarvisCoreState.remoteUsable] — which states [AiRouter] may ever attempt a remote call from. */
class JarvisCoreStateTest {

    @Test
    fun `online and degraded are remote-usable`() {
        assertTrue(JarvisCoreState.ONLINE.remoteUsable)
        assertTrue(JarvisCoreState.DEGRADED.remoteUsable)
    }

    @Test
    fun `disabled connecting offline and error are never remote-usable`() {
        assertFalse(JarvisCoreState.DISABLED.remoteUsable)
        assertFalse(JarvisCoreState.CONNECTING.remoteUsable)
        assertFalse(JarvisCoreState.OFFLINE.remoteUsable)
        assertFalse(JarvisCoreState.ERROR.remoteUsable)
    }
}
