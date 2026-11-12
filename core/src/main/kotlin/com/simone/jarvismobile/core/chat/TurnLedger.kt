package com.simone.jarvismobile.core.chat

import com.simone.jarvismobile.core.llm.InferenceEvent
import com.simone.jarvismobile.core.llm.InferenceEventLog
import com.simone.jarvismobile.core.llm.InferenceEventType

/**
 * HOTFIX 4 — the single authority on "may THIS turn's result still be written into THIS conversation?".
 *
 * A turn is one user message and its answer. It is registered when the message reaches the coordinator and
 * must pass [check] twice: before it starts working and — the one that matters — immediately before its
 * answer is committed to the chat/history. A turn is rejected when
 *  - the user pressed Stop for it ([Reason.STOPPED]) — even if the model happened to finish in the same instant;
 *  - the conversation was reset after it began ([Reason.CONVERSATION_RESET]) — its answer belongs to a chat that no longer exists;
 *  - a NEWER message was already answered ([Reason.SUPERSEDED]) — a late retry of an old message must not
 *    surface an answer to a previous question under the current one.
 *
 * Pure and thread-safe: the Android layer only supplies the clock-free inputs. Events carry the turn sequence,
 * a hash of the task id (never its text), the conversation epoch and the native-engine epoch — enough to tell a
 * discarded stale reply from a real one without recording any content.
 */
class TurnLedger(private val log: InferenceEventLog? = null, private val now: () -> Long = System::currentTimeMillis) {

    enum class Reason { STOPPED, CONVERSATION_RESET, SUPERSEDED }

    class Turn internal constructor(
        val seq: Long,
        val taskId: String?,
        /** Arrival order of the user's message (task creation time for queued chat); orders answers. */
        val orderKey: Long,
        val conversationEpoch: Long,
    ) {
        @Volatile internal var stopReason: Reason? = null
        internal val idHash: Long get() = ((taskId?.hashCode() ?: 0).toLong() and 0xFFFFFFL)
    }

    private val lock = Any()
    private val active = LinkedHashSet<Turn>()
    private var seqCounter = 0L
    private var lastCommittedOrder = Long.MIN_VALUE

    /** Bumped by [newConversation]; every turn remembers the value it began under. */
    @Volatile var conversationEpoch: Long = 0L
        private set

    val activeCount: Int get() = synchronized(lock) { active.size }

    fun begin(taskId: String?, orderKey: Long, engineEpoch: Long): Turn = synchronized(lock) {
        val t = Turn(++seqCounter, taskId, orderKey, conversationEpoch)
        active.add(t)
        emit(InferenceEventType.TURN_BEGIN, t, "eng$engineEpoch")
        t
    }

    /** @return null when the turn may proceed, otherwise why it must not. */
    fun check(turn: Turn): Reason? = synchronized(lock) {
        turn.stopReason?.let { return it }
        if (turn.conversationEpoch != conversationEpoch) return Reason.CONVERSATION_RESET
        if (turn.orderKey < lastCommittedOrder) return Reason.SUPERSEDED
        null
    }

    fun commit(turn: Turn, engineEpoch: Long) = synchronized(lock) {
        lastCommittedOrder = maxOf(lastCommittedOrder, turn.orderKey)
        active.remove(turn)
        emit(InferenceEventType.TURN_COMMIT, turn, "eng$engineEpoch")
    }

    fun discard(turn: Turn, reason: Reason, engineEpoch: Long) = synchronized(lock) {
        active.remove(turn)
        emit(InferenceEventType.TURN_DISCARD, turn, "${reason.name}_eng$engineEpoch")
    }

    /** Ends a turn that threw (cancelled, failed): it can never commit afterwards. */
    fun finish(turn: Turn) = synchronized(lock) { active.remove(turn) }

    /** The user pressed Stop: every turn registered so far (waiting or running) is abandoned. */
    fun stopAll() = synchronized(lock) {
        active.forEach { if (it.stopReason == null) it.stopReason = Reason.STOPPED }
        log?.record(InferenceEvent(now(), InferenceEventType.TURN_STOP, "turn", conversationEpoch, n1 = active.size.toLong()))
    }

    /** Stops the turns of one queued task. @return true if one was registered. */
    fun stop(taskId: String): Boolean = synchronized(lock) {
        var hit = false
        active.filter { it.taskId == taskId }.forEach { if (it.stopReason == null) it.stopReason = Reason.STOPPED; hit = true }
        hit
    }

    /** «Nuova conversazione»: invalidates every turn still in flight and restarts the ordering. */
    fun newConversation(): Long = synchronized(lock) {
        conversationEpoch++
        lastCommittedOrder = Long.MIN_VALUE
        log?.record(
            InferenceEvent(now(), InferenceEventType.TURN_NEW_CONVERSATION, "turn", conversationEpoch, n1 = active.size.toLong()),
        )
        conversationEpoch
    }

    private fun emit(type: InferenceEventType, t: Turn, tag: String) {
        log?.record(
            InferenceEvent(now(), type, "turn", t.conversationEpoch, -1, t.seq, t.idHash, InferenceEventLog.sanitiseTag(tag)),
        )
    }
}
