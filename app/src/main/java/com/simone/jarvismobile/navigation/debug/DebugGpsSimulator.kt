package com.simone.jarvismobile.navigation.debug

import com.simone.jarvismobile.BuildConfig
import com.simone.jarvismobile.core.navigation.GpxReplayRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Debug-only, in-memory (never persisted) toggle for the synthetic GPS loop
 * (Driving Mode V2 spec §13) that [NavigationLocationProvider][com.simone.jarvismobile.navigation.NavigationLocationProvider]
 * substitutes for real GNSS fixes when this is on. [setEnabled] is a no-op
 * outside a debug build, so this can never affect release behaviour even if
 * something calls it — completely separate from the real location path,
 * as the spec requires. Surfaced only in Diagnostica, itself gated on
 * `BuildConfig.DEBUG` there too.
 *
 * [gpxRoute] optionally replaces the plain synthetic square loop with a real
 * recorded track (spec §28) — a user picks a `.gpx` file in Diagnostica, it is
 * parsed once ([com.simone.jarvismobile.core.navigation.GpxParser], pure
 * `:core`), and replayed. Null falls back to the synthetic loop, same as before
 * this existed.
 */
object DebugGpsSimulator {
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled

    fun setEnabled(value: Boolean) {
        if (BuildConfig.DEBUG) _enabled.value = value
    }

    private val _gpxRoute = MutableStateFlow<GpxReplayRoute?>(null)
    val gpxRoute: StateFlow<GpxReplayRoute?> = _gpxRoute

    /** Loads (or clears, with `null`) a GPX track for replay. No-op in release. */
    fun loadGpx(route: GpxReplayRoute?) {
        if (BuildConfig.DEBUG) _gpxRoute.value = route
    }
}
