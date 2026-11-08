package com.simone.jarvismobile.core.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** BETA 2/8 — cancelling a generation / generation errors: pure stop-decision logic (the native call is a fake). */
class GenerationStopControllerTest {
    @Test fun userCancelCallsNativeOnceAndSurfacesAsCancelled() {
        var native = 0
        val c = GenerationStopController { native++ }
        assertTrue(c.request(StopReason.USER))
        assertFailsWith<GenerationCancelled> { c.throwIfStopped() }
        assertEquals(1, native)
    }

    @Test fun firstReasonWinsAndTheNativeCancelIsNotRepeated() {
        var native = 0
        val c = GenerationStopController { native++ }
        c.request(StopReason.USER)
        assertFalse(c.request(StopReason.TIMEOUT)) // watchdog fires later: must not rewrite history
        assertFailsWith<GenerationCancelled> { c.throwIfStopped() }
        assertEquals(1, native)
        assertEquals(StopReason.USER, c.stopReason)
    }

    @Test fun timeoutIsReportedAsTimeoutNotAsUserCancel() {
        val c = GenerationStopController {}
        c.request(StopReason.TIMEOUT); c.request(StopReason.USER)
        assertFailsWith<GenerationTimedOut> { c.throwIfStopped() }
    }

    @Test fun nothingStoppedNothingThrown() {
        GenerationStopController {}.throwIfStopped()
    }

    @Test fun aFailingNativeCancelStillRecordsTheStop() {
        val c = GenerationStopController { error("native cancel crashed") }
        assertTrue(c.request(StopReason.USER))
        assertFailsWith<GenerationCancelled> { c.throwIfStopped() }
    }
}
