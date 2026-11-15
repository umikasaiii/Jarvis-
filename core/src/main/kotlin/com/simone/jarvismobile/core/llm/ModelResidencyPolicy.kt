package com.simone.jarvismobile.core.llm

enum class SlotId { FAST, ADVANCED, CLASSIFIER }

data class ResidentModel(val slot: SlotId, val fileBytes: Long)

/**
 * Which already-loaded models to release BEFORE loading a heavy one, so two large models are never resident by accident.
 * Pure: the caller releases one slot, waits for the native engine to close, re-reads the device memory and asks again; the
 * loop ends when the estimate no longer says "insufficient" or nothing sensible is left to release.
 *
 * Releasing a slot does not forget it: the selection stays in the settings and the slot is only "parked" until the user (or the
 * next start, if memory allows) loads it again. The FAST slot is the assistant's always-on brain and is never parked on its own.
 */
object ModelResidencyPolicy {
    /** Below this a resident model is not worth releasing to make room (it barely helps and costs a reload). */
    const val WORTH_RELEASING_BYTES = 256L * 1024 * 1024

    fun nextToPark(requested: SlotId, adviceLevel: LoadAdviceLevel, others: List<ResidentModel>): SlotId? {
        if (adviceLevel != LoadAdviceLevel.INSUFFICIENT) return null
        val order = listOf(SlotId.CLASSIFIER, SlotId.ADVANCED)
        return order.firstOrNull { s -> s != requested && others.any { it.slot == s && it.fileBytes >= WORTH_RELEASING_BYTES } }
    }
}
