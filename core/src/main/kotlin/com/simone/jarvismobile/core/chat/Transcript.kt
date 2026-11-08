package com.simone.jarvismobile.core.chat

/** One line of the on-screen/persisted conversation (the app's `ChatMessage` maps 1:1). */
data class TranscriptLine(val fromUser: Boolean, val text: String, val taskId: String? = null)

/**
 * BETA 2/8 — the append rules of the conversation log, kept pure so duplicate protection, ordering and
 * recovery after a process restart can be tested.
 */
object Transcript {

    /**
     * Appends [text] unless it is blank or the same side already has a line for the same [taskId]
     * (a retried/resumed background task must never duplicate the user line or its reply).
     * Order is exactly arrival order: nothing is ever re-sorted or dropped.
     */
    fun append(lines: List<TranscriptLine>, fromUser: Boolean, text: String, taskId: String? = null): List<TranscriptLine> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return lines
        if (taskId != null && lines.any { it.fromUser == fromUser && it.taskId == taskId }) return lines
        return lines + TranscriptLine(fromUser, trimmed, taskId)
    }

    /** The reply already stored for [taskId], if the process died after answering but before the task finished. */
    fun existingReply(lines: List<TranscriptLine>, taskId: String): String? =
        lines.firstOrNull { !it.fromUser && it.taskId == taskId }?.text
}
