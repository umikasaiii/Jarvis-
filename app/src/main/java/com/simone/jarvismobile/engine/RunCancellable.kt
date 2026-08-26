package com.simone.jarvismobile.engine

import kotlinx.coroutines.CancellationException

/**
 * `runCatching`, minus the one thing it must never catch.
 *
 * `runCatching` swallows `Throwable`, and `CancellationException` is a
 * `Throwable`. Around a suspending call that means a user-initiated stop is
 * converted into an ordinary failure and the turn carries on — the engine keeps
 * reasoning, keeps calling tools, and eventually answers, as if nobody had
 * pressed anything.
 *
 * That matters more here than almost anywhere else in the app:
 * `AssistantTaskWorker` tells a cancelled turn apart from a failed one by
 * whether a `CancellationException` actually reaches it, and
 * `ConversationalJarvisEngine`'s own contract says the exception is "explicitly
 * let through, never caught". Several `runCatching`s around Room reads,
 * retrieval and the planner router quietly broke that promise.
 *
 * Use this for any `runCatching` that wraps something suspending. Plain
 * `runCatching` stays fine for non-suspending work, where no cancellation can
 * arrive in the first place.
 */
internal inline fun <R> runCancellable(block: () -> R): Result<R> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Result.failure(t)
    }
