package com.simone.jarvismobile.core.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * HOTFIX 3 — the ONE owner of a native LLM engine's lifecycle. Replaces the ad-hoc mix of mutexes in the
 * previous `LitertLmEngine`, where `resetConversation()` closed a live `Conversation` while `sendMessage()`
 * was inside native code and `generate()` bypassed the chat lock entirely.
 *
 * Invariants (each pinned by `InferenceSlotTest`):
 *  1. SINGLE LANE — [chat] and [once] run strictly one at a time (one [Mutex]); nothing else touches the
 *     native engine. A caller that cannot get the lane in a bounded time gets `null` (never an unbounded wait).
 *  2. NEVER CLOSE WHAT IS IN USE — [reset] and [shutdown] only mark intent when a call is in flight; the
 *     native close happens later, by the lane owner, after `send()` has returned.
 *  3. STOP IS REAL — caller cancellation, [cancel] and the watchdog all reach the native `cancel()`; a stop
 *     requested before the native call starts (while queued) aborts it instead of being lost.
 *  4. NO POISONED STATE SURVIVES — after a cancel, timeout, failure or degenerate output the persistent
 *     conversation is discarded ([epoch] changes so the owner reseeds it), never reused.
 *  5. WEDGE IS DETECTED — if native code does not unwind within [InferenceConfig.unwindGraceMs] of a stop
 *     request the slot reports [onWedged] so the owner can replace the engine instead of hanging forever.
 */
class InferenceSlot(
    val label: String,
    private val engine: NativeEngine,
    private val scheduler: InferenceScheduler,
    private val log: InferenceEventLog,
    private val config: InferenceConfig = InferenceConfig(),
    private val now: () -> Long = System::currentTimeMillis,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onGenerating: (Boolean) -> Unit = {},
    private val onWedged: (InferenceSlot) -> Unit = {},
) {
    private val lane = Mutex()

    // Guarded by [lane] (the persistent conversation is only touched by the lane owner).
    private var conversation: NativeConversation? = null
    private var conversationTurns = 0
    private var conversationOrigin = "load"
    private var conversationOpenedAt = 0L

    /** Bumped whenever the persistent conversation is discarded: the owner must reseed it. */
    @Volatile var epoch: Long = 0L
        private set

    @Volatile var wedged: Boolean = false
        private set
    @Volatile var closed: Boolean = false
        private set

    private val resetRequested = AtomicBoolean(false)
    private val pendingClose = AtomicBoolean(false)
    private val cancelSeq = AtomicLong(0)
    private val active = AtomicReference<Ticket?>(null)
    private val closedSignal = CompletableDeferred<Unit>()

    // ---- public API ------------------------------------------------------------------------------------

    /** Multi-turn chat on the persistent conversation (seeded with [systemPrompt] on first use). */
    suspend fun chat(userText: String, systemPrompt: String, timeoutMs: Long, seed: ChatSeed = ChatSeed.NONE): String? =
        gated(config.chatLockWaitMs, "chat") {
            val first = turnOnPersistent(userText, systemPrompt, timeoutMs, seed)
            val verdict = first?.let { DegenerateOutput.analyze(it) } ?: DegenerateOutput.Verdict.OK
            if (!verdict.degenerate) return@gated first
            noteDegenerate("chat", verdict)
            discardConversation("degenerate")
            if (!config.retryDegenerateChat) return@gated null
            record(InferenceEventType.DEGENERATE_RETRY)
            val second = turnOnPersistent(userText, systemPrompt, timeoutMs, seed)
            val v2 = second?.let { DegenerateOutput.analyze(it) } ?: DegenerateOutput.Verdict.OK
            if (v2.degenerate) {
                noteDegenerate("chat_retry", v2)
                discardConversation("degenerate")
                null
            } else {
                second
            }
        }

    /** One stateless turn on a brand-new conversation that is closed before the lane is released. */
    suspend fun once(userText: String, systemPrompt: String?, timeoutMs: Long, kind: String = "once"): String? =
        gated(config.onceLockWaitMs, kind) {
            val start = now()
            record(InferenceEventType.ONCE_START, tag = kind)
            var conv: NativeConversation? = null
            try {
                conv = engine.open(systemPrompt)
                val answer = generateOn(conv, userText, timeoutMs, kind)
                val verdict = DegenerateOutput.analyze(answer)
                if (verdict.degenerate) {
                    noteDegenerate(kind, verdict)
                    null
                } else {
                    answer
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: GenerationTimedOut) {
                throw t
            } catch (t: GenerationCancelled) {
                throw CancellationException("generation_cancelled")
            } catch (t: Throwable) {
                record(InferenceEventType.FAILED, tag = t.javaClass.simpleName)
                null
            } finally {
                conv?.let { runCatching { it.close() } }
                record(InferenceEventType.ONCE_END, durationMs = now() - start, tag = kind)
            }
        }

    /**
     * Drops the persistent history. Safe at any time: if a call is in flight the actual native close is
     * deferred to the lane owner (invariant 2). [epoch] changes immediately so owners reseed.
     */
    fun reset(reason: String = "api") {
        epoch++
        resetRequested.set(true)
        if (lane.tryLock()) {
            try {
                applyPendingReset(reason)
            } finally {
                lane.unlock()
            }
        } else {
            record(InferenceEventType.RESET_DEFERRED, tag = reason)
        }
    }

    /** Stops the generation in flight (if any) and any call still queued for the lane. */
    fun cancel() {
        cancelSeq.incrementAndGet()
        active.get()?.stop(StopReason.USER)
    }

    /** Releases the engine as soon as nothing is using it. Never blocks. */
    fun shutdown() {
        if (closed) return
        closed = true
        cancel()
        pendingClose.set(true)
        if (lane.tryLock()) {
            try {
                closeNow()
            } finally {
                lane.unlock()
            }
        }
    }

    /** Completes when the native engine has actually been closed (not merely requested to). */
    suspend fun awaitClosed(timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) { closedSignal.await(); true } ?: false

    val isIdle: Boolean get() = active.get() == null && !lane.isLocked

    // ---- internals -------------------------------------------------------------------------------------

    private suspend fun <T> gated(waitMs: Long, kind: String, block: suspend () -> T?): T? {
        val seq = cancelSeq.get()
        if (closed) return null
        val acquired = withTimeoutOrNull(waitMs) { lane.lock(); true } ?: false
        if (!acquired) {
            record(InferenceEventType.BUSY, durationMs = waitMs, tag = kind)
            return null
        }
        try {
            if (closed) return null
            // A stop requested while this call was queued/starting must not be lost (invariant 3).
            if (cancelSeq.get() != seq) throw CancellationException("cancelled_before_start")
            applyPendingReset("deferred")
            return block()
        } finally {
            try {
                applyPendingReset("deferred")
                if (pendingClose.get()) closeNow()
            } finally {
                lane.unlock()
            }
        }
    }

    private suspend fun turnOnPersistent(userText: String, systemPrompt: String, timeoutMs: Long, seed: ChatSeed): String? {
        val start = now()
        record(InferenceEventType.CHAT_START, n1 = conversationTurns.toLong(), tag = conversationOrigin)
        var conv = conversation
        if (conv == null) {
            try {
                conv = engine.open(systemPrompt, seed)
            } catch (t: Throwable) {
                record(InferenceEventType.FAILED, tag = "open_" + t.javaClass.simpleName)
                return null
            }
            conversation = conv
            conversationTurns = 0
            conversationOpenedAt = now()
            record(InferenceEventType.CONV_OPENED, tag = conversationOrigin)
        }
        try {
            val answer = generateOn(conv, userText, timeoutMs, "chat")
            conversationTurns++
            return answer
        } catch (c: CancellationException) {
            discardConversation("cancel")
            throw c
        } catch (t: GenerationTimedOut) {
            discardConversation("timeout")
            throw t
        } catch (t: GenerationCancelled) {
            discardConversation("cancel")
            throw CancellationException("generation_cancelled")
        } catch (t: Throwable) {
            record(InferenceEventType.FAILED, tag = t.javaClass.simpleName)
            discardConversation("failure")
            return null
        } finally {
            record(InferenceEventType.CHAT_END, durationMs = now() - start, n1 = conversationTurns.toLong())
        }
    }

    /** The blocking native call, with the three stop paths wired to the native cancel. */
    private suspend fun generateOn(conv: NativeConversation, text: String, timeoutMs: Long, kind: String): String {
        val ticket = Ticket(conv)
        active.set(ticket)
        onGenerating(true)
        val cancelTimer = scheduler.schedule(timeoutMs) { ticket.stop(StopReason.TIMEOUT) }
        try {
            return coroutineScope {
                // Caller cancellation (Activity/ViewModel/Worker scope) -> native cancel, not just a lost coroutine.
                val watcher = launch {
                    try {
                        awaitCancellation()
                    } catch (e: CancellationException) {
                        ticket.stop(StopReason.USER)
                        throw e
                    }
                }
                try {
                    val out = withContext(dispatcher) {
                        try {
                            conv.send(text)
                        } catch (t: Throwable) {
                            ticket.finished.set(true)
                            ticket.controller.throwIfStopped()
                            throw t
                        } finally {
                            ticket.finished.set(true)
                        }
                    }
                    ticket.controller.throwIfStopped()
                    out
                } finally {
                    watcher.cancel()
                }
            }
        } finally {
            cancelTimer()
            ticket.cancelGrace?.invoke()
            active.compareAndSet(ticket, null)
            onGenerating(false)
        }
    }

    private fun applyPendingReset(reason: String) {
        if (!resetRequested.compareAndSet(true, false)) return
        val had = conversation != null
        closeConversationQuietly()
        conversationOrigin = "reset"
        if (had) record(InferenceEventType.RESET_APPLIED, tag = reason)
    }

    private fun discardConversation(reason: String) {
        if (conversation == null) return
        closeConversationQuietly()
        epoch++
        conversationOrigin = reason
        record(InferenceEventType.CONV_DISCARDED, tag = reason)
    }

    private fun closeConversationQuietly() {
        val c = conversation
        conversation = null
        conversationTurns = 0
        c?.let { runCatching { it.close() } }
    }

    private fun closeNow() {
        if (!pendingClose.compareAndSet(true, false)) return
        closeConversationQuietly()
        runCatching { engine.close() }
        closed = true
        closedSignal.complete(Unit)
    }

    private fun noteDegenerate(kind: String, v: DegenerateOutput.Verdict) {
        record(
            InferenceEventType.DEGENERATE,
            n1 = v.unitLength.toLong(),
            n2 = conversationTurns.toLong(),
            tag = InferenceEventLog.sanitiseTag("${kind}_${v.reason.name}_from_$conversationOrigin"),
        )
    }

    private fun record(
        type: InferenceEventType,
        durationMs: Long = -1,
        n1: Long = -1,
        n2: Long = -1,
        tag: String = "",
    ) = log.record(
        InferenceEvent(now(), type, label, epoch, durationMs, n1, n2, InferenceEventLog.sanitiseTag(tag)),
    )

    private fun markWedged(ticket: Ticket) {
        if (ticket.finished.get() || wedged) return
        wedged = true
        record(InferenceEventType.WEDGED, durationMs = config.unwindGraceMs)
        runCatching { onWedged(this) }
    }

    /** One in-flight native call; the stop decision lives in the tested [GenerationStopController]. */
    private inner class Ticket(conv: NativeConversation) {
        val finished = AtomicBoolean(false)
        val controller = GenerationStopController { conv.cancel() }
        @Volatile var cancelGrace: (() -> Unit)? = null

        fun stop(reason: StopReason) {
            if (finished.get()) return
            if (controller.request(reason)) {
                record(if (reason == StopReason.TIMEOUT) InferenceEventType.TIMEOUT else InferenceEventType.CANCEL_REQUESTED)
                cancelGrace = scheduler.schedule(config.unwindGraceMs) { markWedged(this) }
            }
        }
    }
}
