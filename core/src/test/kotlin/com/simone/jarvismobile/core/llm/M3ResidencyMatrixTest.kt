package com.simone.jarvismobile.core.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** M3-B — the residency criterion is explicit and testable: a slot is parked only to make room for a load whose estimate is INSUFFICIENT; nothing oscillates per message. */
class M3ResidencyMatrixTest {
    private val gb = 1024L * 1024 * 1024
    private val fast = ResidentModel(SlotId.FAST, 3 * gb)
    private val adv = ResidentModel(SlotId.ADVANCED, 5 * gb)
    private val cls = ResidentModel(SlotId.CLASSIFIER, 1 * gb)

    @Test fun firstLoadAndSameModelAgainParkNothing() {
        assertNull(ModelResidencyPolicy.nextToPark(SlotId.FAST, LoadAdviceLevel.OK, emptyList()))
        assertNull(ModelResidencyPolicy.nextToPark(SlotId.FAST, LoadAdviceLevel.OK, listOf(fast)))
    }

    @Test fun fastAdvancedFastRoundTripNeverParksTheFastBrain() {
        // loading ADVANCED while FAST is resident and memory is tight: FAST is never the one parked
        assertNull(ModelResidencyPolicy.nextToPark(SlotId.ADVANCED, LoadAdviceLevel.INSUFFICIENT, listOf(fast)))
        // loading FAST again with ADVANCED resident and tight memory: ADVANCED (not FAST) is released first
        assertEquals(SlotId.ADVANCED, ModelResidencyPolicy.nextToPark(SlotId.FAST, LoadAdviceLevel.INSUFFICIENT, listOf(adv)))
    }

    @Test fun theClassifierGoesFirstThenAdvanced() {
        assertEquals(SlotId.CLASSIFIER, ModelResidencyPolicy.nextToPark(SlotId.ADVANCED, LoadAdviceLevel.INSUFFICIENT, listOf(fast, cls)))
        assertEquals(SlotId.ADVANCED, ModelResidencyPolicy.nextToPark(SlotId.FAST, LoadAdviceLevel.INSUFFICIENT, listOf(adv, cls.copy(fileBytes = 10))))
    }

    @Test fun aSmallResidentIsNotWorthAReloadAndTheDecisionIsStableForTheSameInput() {
        assertNull(ModelResidencyPolicy.nextToPark(SlotId.ADVANCED, LoadAdviceLevel.INSUFFICIENT, listOf(fast, cls.copy(fileBytes = 10L * 1024 * 1024))))
        val a = ModelResidencyPolicy.nextToPark(SlotId.ADVANCED, LoadAdviceLevel.INSUFFICIENT, listOf(fast, cls))
        repeat(20) { assertEquals(a, ModelResidencyPolicy.nextToPark(SlotId.ADVANCED, LoadAdviceLevel.INSUFFICIENT, listOf(fast, cls))) } // no oscillation: same input, same answer
    }

    @Test fun anEstimateBelowInsufficientNeverParksAnything() {
        for (level in LoadAdviceLevel.values().filter { it != LoadAdviceLevel.INSUFFICIENT }) assertNull(ModelResidencyPolicy.nextToPark(SlotId.ADVANCED, level, listOf(fast, cls, adv)))
    }
}
