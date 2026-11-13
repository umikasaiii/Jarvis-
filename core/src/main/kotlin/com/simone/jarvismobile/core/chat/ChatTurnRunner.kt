package com.simone.jarvismobile.core.chat

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where a committed line goes (the app's on-screen/persisted chat). Idempotent per (side, taskId). */
fun interface ChatSink {
    fun append(fromUser: Boolean, text: String, taskId: String?)
}

sealed interface TurnResult {
    data class Answered(val text: String) : TurnResult
    data class Discarded(val reason: TurnLedger.Reason) : TurnResult
}

/**
 * HOTFIX 4 — runs ONE written-chat turn end to end behind the existing single lane [lane] (the coordinator's
 * session mutex, shared with voice, so there is still exactly one place where a turn can run) and is the only
 * code that may write a turn's lines into the chat.
 *
 * Order of events for a turn: register -> wait for the lane -> [TurnLedger.check] -> user line -> generate ->
 * [TurnLedger.check] again -> answer line -> commit. Nothing is written for a turn the ledger rejects, so a
 * stopped, reset or superseded turn can never surface in the chat, the notification or the task output.
 */
class ChatTurnRunner(
    private val lane: Mutex,
    private val ledger: TurnLedger,
    private val sink: ChatSink,
    private val engineEpoch: () -> Long,
) {
    suspend fun run(
        taskId: String?,
        orderKey: Long,
        userText: String,
        onStart: () -> Unit = {},
        onTurn: (Long) -> Unit = {},
        onCommitted: (String) -> Unit = {},
        onEnd: () -> Unit = {},
        generate: suspend (String) -> String,
    ): TurnResult {
        val turn = ledger.begin(taskId, orderKey, engineEpoch())
        try {
            return lane.withLock {
                ledger.check(turn)?.let { reason ->
                    ledger.discard(turn, reason, engineEpoch())
                    return@withLock TurnResult.Discarded(reason)
                }
                onStart()
                onTurn(turn.seq)
                try {
                    sink.append(true, userText, taskId)
                    val answer = generate(userText)
                    ledger.check(turn)?.let { reason ->
                        ledger.discard(turn, reason, engineEpoch())
                        return@withLock TurnResult.Discarded(reason)
                    }
                    sink.append(false, answer, taskId)
                    ledger.commit(turn, engineEpoch())
                    onCommitted(answer)
                    TurnResult.Answered(answer)
                } finally {
                    onEnd()
                }
            }
        } finally {
            ledger.finish(turn)
        }
    }
}
