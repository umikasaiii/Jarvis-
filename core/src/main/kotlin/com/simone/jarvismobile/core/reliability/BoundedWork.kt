package com.simone.jarvismobile.core.reliability

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

enum class WorkOutcome { COMPLETED, TIMED_OUT, FAILED }

/**
 * A broadcast receiver's `goAsync()` window is short and the process can be killed once it ends. An alarm handler that
 * does its work and *then* books the next occurrence therefore loses every future occurrence if the work hangs.
 *
 * [runThenAlways] bounds [work] with a timeout, swallows its failure into an outcome, and then **always** runs
 * [afterwards] (the re-arm of the successor). Cancellation of the caller still propagates.
 */
object BoundedWork {
    suspend fun runThenAlways(
        timeoutMs: Long,
        work: suspend () -> Unit,
        afterwards: suspend () -> Unit,
    ): WorkOutcome {
        val outcome = try {
            withTimeout(timeoutMs) { work() }
            WorkOutcome.COMPLETED
        } catch (e: TimeoutCancellationException) {
            WorkOutcome.TIMED_OUT
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            WorkOutcome.FAILED
        }
        afterwards()
        return outcome
    }
}
