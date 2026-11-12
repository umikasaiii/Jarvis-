package com.simone.jarvismobile.core.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * HOTFIX 4 — runs submitted writes strictly in submission order, one at a time. The chat file used to be
 * saved by independent `scope.launch { save(snapshot) }` coroutines on a multi-threaded dispatcher, so an
 * older snapshot (or the one written before «Nuova conversazione» cleared the file) could finish LAST and
 * resurrect stale history that the next recap then fed back to the model.
 */
class SerialWriter(scope: CoroutineScope) {
    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (job in queue) runCatching { job() }
        }
    }

    fun submit(job: suspend () -> Unit) {
        queue.trySend(job)
    }
}
