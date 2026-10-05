package com.simone.jarvismobile.audio.silero

import com.simone.jarvismobile.core.voice.silero.SileroFailure
import com.simone.jarvismobile.core.voice.silero.SileroModelState
import com.simone.jarvismobile.core.voice.silero.SileroVadEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream

/** Bounded, content-free status of the Silero model for the Models / Diagnostics UI. */
data class SileroModelStatus(
    val state: SileroModelState = SileroModelState.NOT_IMPORTED,
    val failure: SileroFailure? = null,
    /** True once the installed file's size + SHA-256 were re-verified against the pinned manifest. */
    val shaVerified: Boolean = false,
)

/**
 * Lifecycle owner of the Silero model + engine (lazy load, loaded once, never reloaded per frame).
 * Delete/replace closes the engine BEFORE touching the file. A failed load never throws into JARVIS:
 * production voice does not use Silero in this phase, so there is nothing to fall back from.
 */
class SileroVadModelManager(
    private val store: SileroModelStore,
    private val inspector: SileroGraphInspector,
    private val backendFactory: SileroBackendFactory,
    private val io: CoroutineDispatcher,
) {
    private val mutex = Mutex()
    private var engine: SileroVadEngine? = null
    private val _status = MutableStateFlow(SileroModelStatus())
    val status: StateFlow<SileroModelStatus> = _status.asStateFlow()

    /** Re-reads what is on disk (cheap, no ORT load). */
    suspend fun refresh() = withContext(io) { mutex.withLock { refreshLocked() } }

    private fun refreshLocked() {
        _status.value = when (val f = store.verifyInstalled()) {
            null -> SileroModelStatus(SileroModelState.READY, null, true)
            SileroFailure.NOT_IMPORTED -> SileroModelStatus(SileroModelState.NOT_IMPORTED)
            else -> SileroModelStatus(SileroModelState.ERROR, f)
        }
    }

    /** Loads the engine once (lazy). null when the model is missing / corrupt / incompatible. */
    suspend fun ensureLoaded(): SileroVadEngine? = withContext(io) {
        mutex.withLock {
            engine?.let { return@withLock it }
            store.verifyInstalled()?.let { f ->
                _status.value = if (f == SileroFailure.NOT_IMPORTED) SileroModelStatus() else SileroModelStatus(SileroModelState.ERROR, f)
                return@withLock null
            }
            _status.value = SileroModelStatus(SileroModelState.LOADING, null, true)
            when (val opened = backendFactory.open(store.modelFile)) {
                is SileroBackendOpen.Failed -> {
                    _status.value = SileroModelStatus(SileroModelState.INCOMPATIBLE, opened.failure, true)
                    null
                }
                is SileroBackendOpen.Ok -> {
                    val e = SileroVadEngine(opened.backend)
                    engine = e
                    _status.value = SileroModelStatus(SileroModelState.READY, null, true)
                    e
                }
            }
        }
    }

    /** Validates + atomically installs the model. A rejection leaves any previous good model untouched. */
    suspend fun import(input: InputStream): SileroImportOutcome = withContext(io) {
        mutex.withLock {
            val outcome = input.use { store.import(it, inspector) }
            if (outcome is SileroImportOutcome.Ok) {
                engine?.close() // a replacement must reload from the new file
                engine = null
                refreshLocked()
            }
            outcome
        }
    }

    /** Closes the engine first, then deletes the model. */
    suspend fun remove(): Boolean = withContext(io) {
        mutex.withLock {
            engine?.close()
            engine = null
            val ok = store.remove()
            refreshLocked()
            ok
        }
    }

    /** Releases the ORT session (keeps the file). */
    suspend fun release() = withContext(io) {
        mutex.withLock {
            engine?.close()
            engine = null
        }
    }
}
