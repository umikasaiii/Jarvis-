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
    /**
     * Opens a conversation, optionally seeded with a system instruction, earlier turns (as real user/model
     * turns, not text pasted into the system block) and sampling parameters.
     */
    fun open(systemPrompt: String?, seed: ChatSeed = ChatSeed.NONE): NativeConversation
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

/** One earlier turn handed to a NEW native conversation as a real user/model message. */
data class SeedTurn(val fromUser: Boolean, val text: String)

/** Sampling parameters the LiteRT-LM `SamplerConfig` documents (top-k, top-p, temperature). */
data class Sampling(val topK: Int, val topP: Double, val temperature: Double)

/**
 * HOTFIX 5 — what a NEW conversation starts from besides the system instruction. Ignored when the engine
 * already holds a live conversation (its own history is then the memory). [normalized] makes the turn list a
 * valid alternating user/model sequence that ends with a model turn, because the next user message is sent as
 * the live turn.
 */
data class ChatSeed(val history: List<SeedTurn> = emptyList(), val sampling: Sampling? = null) {
    fun normalized(): List<SeedTurn> {
        val out = ArrayList<SeedTurn>()
        for (turn in history) {
            val text = turn.text.trim()
            if (text.isEmpty()) continue
            if (out.isEmpty() && !turn.fromUser) continue // must start with the user
            if (out.isNotEmpty() && out.last().fromUser == turn.fromUser) {
                out[out.lastIndex] = out.last().copy(text = out.last().text + "\n" + text)
            } else {
                out.add(SeedTurn(turn.fromUser, text))
            }
        }
        while (out.isNotEmpty() && out.last().fromUser) out.removeAt(out.lastIndex) // unanswered tail
        return out
    }

    companion object { val NONE = ChatSeed() }
}

/** HOTFIX 5 — sampling presets. Values are applied through the documented `SamplerConfig`; see docs for rationale. */
object SamplingProfiles {
    /**
     * A moderately conservative profile for a small quantised model: lower temperature and a tighter nucleus reduce
     * the random token slips that turn into wrong digits or cut-off phrasing, while staying far from greedy decoding
     * (which makes small models loop). NOT measured on the device yet — it is the one setting to compare on the Honor 200.
     */
    val CONSERVATIVE = Sampling(topK = 40, topP = 0.9, temperature = 0.7)
}
