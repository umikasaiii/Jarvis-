package com.simone.jarvismobile.snapshot.providers

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.simone.jarvismobile.context.ContextEngine
import com.simone.jarvismobile.core.snapshot.DeviceContext
import com.simone.jarvismobile.core.snapshot.NetworkType
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Battery/charging/Bluetooth come from [ContextEngine]'s already-live state
 * (no new polling). Wifi-vs-cellular has no existing reactive source in
 * this codebase (confirmed by audit — `AutomationEventService` only fires
 * one-shot triggers, never exposes a Flow), so this provider does a single
 * `ConnectivityManager` read at snapshot-build time — not a continuous
 * poll, matching the same "one-shot Android read on demand" pattern already
 * used elsewhere in this project (`WeatherManager`, `HealthConnectManager`).
 *
 * **Onestà**: wired (Bluetooth) headphones are distinguishable via
 * `connectedBluetooth`, but a *wired* headset's connect/disconnect state is
 * only ever observed transiently by `AutomationEventService`'s broadcast
 * receiver, never stored anywhere reactive — so [headphonesConnected]
 * reflects Bluetooth only, not wired headphones, and is `null` (unknown)
 * rather than a false negative when Bluetooth state itself isn't known yet.
 */
fun interface DeviceContextProvider {
    suspend fun provide(): DeviceContext?
}

@Singleton
class DefaultDeviceContextProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contextEngine: ContextEngine,
) : DeviceContextProvider {

    override suspend fun provide(): DeviceContext {
        val state = contextEngine.state.value
        val networkType = runCatching { readNetworkType() }.getOrDefault(NetworkType.UNKNOWN)
        return DeviceContext(
            batteryLevel = state.batteryPercent,
            isCharging = state.charging,
            networkType = networkType,
            isOnline = state.networkAvailable,
            headphonesConnected = if (state.bluetoothKnown) state.connectedBluetooth.isNotEmpty() else null,
            carMode = state.driving,
            capturedAt = Instant.now(),
        )
    }

    private fun readNetworkType(): NetworkType {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return NetworkType.UNKNOWN
        val network = cm.activeNetwork ?: return NetworkType.NONE
        val caps = cm.getNetworkCapabilities(network) ?: return NetworkType.UNKNOWN
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            else -> NetworkType.UNKNOWN
        }
    }
}
