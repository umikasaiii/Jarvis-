package com.simone.jarvismobile.core.llm

/**
 * HOTFIX 3 — the minimal contract of the native LLM runtime that JARVIS depends on, expressed without
 * any Android/LiteRT type so the lifecycle logic ([InferenceSlot]) can be unit-tested on a plain JVM with
 * a fake engine. The Android adapter lives in `app/.../llm/LitertLmEngine.kt`.
 *
 * Contract assumed (and enforced by [InferenceSlot], never left to callers):
 *  - [send] is BLOCKING and not interruptible by thread/coroutine cancellation; only [cancel] unblocks it.
 *  - [cancel] may be called from any thread while [send] runs.
 *  - [close] on a conversation/engine that is still inside [send] is a use-after-free in native code, so
 *    nothing here ever closes a resource that is in use.
 */
interface NativeConversation {
    fun send(text: String): String
    fun cancel()
    fun close()
}

interface NativeEngine {
    /** Opens a conversation, optionally seeded with a system instruction. */
    fun open(systemPrompt: String?): NativeConversation
    fun close()
}

/** Schedules [task] after [delayMs]; the returned function cancels it. Injectable so tests control time. */
fun interface InferenceScheduler {
    fun schedule(delayMs: Long, task: () -> Unit): () -> Unit
}

data class InferenceConfig(
    /** How long a user chat turn waits for the single native lane before giving up (never unbounded). */
    val chatLockWaitMs: Long = 8_000L,
    /** Short helper calls (classifier, summaries) fail fast instead of queueing behind a long answer. */
    val onceLockWaitMs: Long = 3_000L,
    /** After a stop was requested, how long native code may take to unwind before the slot is declared wedged. */
    val unwindGraceMs: Long = 10_000L,
    /** One automatic retry of a chat turn on a FRESH conversation when the output is degenerate. */
    val retryDegenerateChat: Boolean = true,
)
