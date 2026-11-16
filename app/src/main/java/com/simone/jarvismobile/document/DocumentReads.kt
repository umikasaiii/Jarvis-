package com.simone.jarvismobile.document

import kotlinx.coroutines.CancellationException

/** The document store could not be READ (as opposed to being empty): the caller must say so, not "nothing found". */
class DocumentReadException(val what: String, cause: Throwable) : java.io.IOException("document_read_failed:$what", cause)

/**
 * M1 — the one rule for reads that ANSWER from documents: an empty result is returned as empty; a failure becomes a [DocumentReadException]
 * (never an empty list); cancellation always passes through untouched. [onFailure] gets only the exception class (never document text).
 */
internal inline fun <T> readOrThrow(what: String, onFailure: (String) -> Unit = {}, block: () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    onFailure("document_read_failed $what ${e.javaClass.simpleName}")
    throw DocumentReadException(what, e)
}
