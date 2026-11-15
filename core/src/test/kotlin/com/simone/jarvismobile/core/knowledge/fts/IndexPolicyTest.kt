package com.simone.jarvismobile.core.knowledge.fts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class IndexPolicyTest {
    private fun c(battery: Int = 80, charging: Boolean = false, saver: Boolean = false, thermal: Int = 0, low: Boolean = false, mem: Long = 2000) = DeviceConditions(battery, charging, saver, thermal, low, mem)

    @Test fun `a healthy device gets every worker`() = assertEquals(IndexDecision(3, 0, null), IndexPolicy.decide(c(), 3))

    @Test fun `low battery stops unless charging`() {
        assertNotNull(IndexPolicy.decide(c(battery = 10), 3).stopReason)
        assertNull(IndexPolicy.decide(c(battery = 10, charging = true), 3).stopReason)
        assertEquals(1, IndexPolicy.decide(c(battery = 25), 3).workers)
        assertEquals(3, IndexPolicy.decide(c(battery = 25, charging = true), 3).workers)
    }

    @Test fun `heat throttles then stops`() {
        assertEquals(2, IndexPolicy.decide(c(thermal = IndexPolicy.THERMAL_LIGHT), 3).workers)
        val moderate = IndexPolicy.decide(c(thermal = IndexPolicy.THERMAL_MODERATE), 3)
        assertEquals(1, moderate.workers); assertEquals(400, moderate.pauseMillis)
        assertNotNull(IndexPolicy.decide(c(thermal = IndexPolicy.THERMAL_SEVERE), 3).stopReason)
    }

    @Test fun `power saver and tight memory use one worker, very low memory stops`() {
        val saver = IndexPolicy.decide(c(saver = true), 3); assertEquals(1, saver.workers); assertEquals(150, saver.pauseMillis)
        assertEquals(1, IndexPolicy.decide(c(low = true), 3).workers)
        assertEquals(1, IndexPolicy.decide(c(mem = 250), 3).workers)
        assertNotNull(IndexPolicy.decide(c(mem = 80), 3).stopReason)
        assertEquals(1, IndexPolicy.decide(c(), 1).workers)
    }

    @Test fun `unknown readings never block the run`() {
        // -1 = the platform did not tell us: treat as "no constraint", never as "stop"
        assertNull(IndexPolicy.decide(c(battery = -1, mem = -1), 2).stopReason)
    }
}
