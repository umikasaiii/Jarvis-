package com.simone.jarvismobile.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QueueLivenessTest {
    private val now = 10_000_000L

    @Test fun `a recently touched active row still blocks newer messages`() {
        assertTrue(QueueLiveness.blocksNewerMessages(now - 60_000, now))
        assertTrue(QueueLiveness.blocksNewerMessages(now - QueueLiveness.STALE_AFTER_MS, now))
    }

    @Test fun `an orphaned row left behind by a dead process stops blocking`() {
        assertFalse(QueueLiveness.blocksNewerMessages(now - QueueLiveness.STALE_AFTER_MS - 1, now))
        assertFalse(QueueLiveness.blocksNewerMessages(0, now))
    }

    @Test fun `the DAO cut-off equals the policy`() {
        assertEquals(now - QueueLiveness.STALE_AFTER_MS, QueueLiveness.staleBefore(now))
    }
}
