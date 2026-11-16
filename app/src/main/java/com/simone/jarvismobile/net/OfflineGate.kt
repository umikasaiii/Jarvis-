package com.simone.jarvismobile.net

import com.simone.jarvismobile.core.net.Cause
import com.simone.jarvismobile.core.net.Decision
import com.simone.jarvismobile.core.net.NetDecision
import com.simone.jarvismobile.core.net.NetOperation
import com.simone.jarvismobile.core.net.NetworkPolicy
import com.simone.jarvismobile.core.net.OfflineBlockedException
import com.simone.jarvismobile.core.net.OfflineMode
import com.simone.jarvismobile.core.net.OfflineTrace
import okhttp3.Interceptor
import okhttp3.Response

/**
 * M3-D — the process-wide offline gate. It sits at the BOUNDARY of every OkHttp client JARVIS builds (see [interceptor]) and in the router that
 * decides whether a chat turn may leave the device, so a worker, a background service or a late retry is stopped exactly like a UI action.
 *
 * Fail-closed: until the persisted mode has been read ([initialise]) the mode is STRICT_OFFLINE, so no call can slip out during start-up.
 * It governs only requests that JARVIS itself makes. It does not (and cannot) control other apps started through Intents, Google Play services
 * components (Drive authorisation, ML Kit model downloads) or the OS; those are listed as out of coverage in docs/OFFLINE_POLICY.md.
 * The per-feature opt-in (weather switch, TomTom key, Core PC authorisation…) is still checked by each feature BEFORE it calls; this gate adds the mode.
 */
object OfflineGate {
    @Volatile private var mode: OfflineMode = OfflineMode.EXPLICIT_ONLINE
    @Volatile private var ready = false
    val trace = OfflineTrace()

    /** Called once at start-up with the persisted mode, and again whenever the user changes it. */
    fun initialise(persisted: OfflineMode) { mode = persisted; ready = true }

    fun effectiveMode(): OfflineMode = if (!ready) OfflineMode.STRICT_OFFLINE else mode

    fun decide(op: NetOperation, requestActive: Boolean = true): NetDecision = NetworkPolicy.decide(effectiveMode(), op, userEnabled = true, requestActive = requestActive)

    /** True when [op] may proceed; every decision is traced (content-free). */
    fun allows(op: NetOperation, requestId: String = "-", requestActive: Boolean = true): Boolean {
        val d = decide(op, requestActive)
        trace.net(requestId, op, effectiveMode(), d, if (d.decision == Decision.ALLOW) "ok" else "blocked")
        return d.decision == Decision.ALLOW
    }

    fun check(op: NetOperation, requestId: String = "-", requestActive: Boolean = true) {
        val d = decide(op, requestActive)
        if (d.decision == Decision.BLOCK) {
            trace.net(requestId, op, effectiveMode(), d, "blocked")
            throw OfflineBlockedException(op, d.cause)
        }
    }

    /** One interceptor per client: [op] names the feature the client belongs to, never a URL. */
    fun interceptor(op: NetOperation): Interceptor = Interceptor { chain ->
        val call = chain.call()
        val d = decide(op, requestActive = !call.isCanceled())
        val mode = effectiveMode()
        if (d.decision == Decision.BLOCK) {
            trace.net("-", op, mode, d, if (d.cause == Cause.BLOCKED_REQUEST_CANCELLED) "cancelled" else "blocked")
            throw OfflineBlockedException(op, d.cause)
        }
        val t0 = System.nanoTime()
        try {
            val r: Response = chain.proceed(chain.request())
            trace.net("-", op, mode, d, if (r.isSuccessful) "ok" else "failed", (System.nanoTime() - t0) / 1_000_000)
            r
        } catch (e: java.io.IOException) {
            trace.net("-", op, mode, d, if (call.isCanceled()) "cancelled" else "failed", (System.nanoTime() - t0) / 1_000_000)
            throw e
        }
    }
}

/** `OkHttpClient.Builder().guarded(op)`: the one-line way every JARVIS client installs the gate. */
fun okhttp3.OkHttpClient.Builder.guarded(op: NetOperation): okhttp3.OkHttpClient.Builder = addInterceptor(OfflineGate.interceptor(op))
