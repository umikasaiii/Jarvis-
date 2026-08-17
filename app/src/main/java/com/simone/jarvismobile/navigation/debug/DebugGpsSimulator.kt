package com.simone.jarvismobile.navigation.debug

import com.simone.jarvismobile.BuildConfig
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
 */
object DebugGpsSimulator {
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled

    fun setEnabled(value: Boolean) {
        if (BuildConfig.DEBUG) _enabled.value = value
    }
}
