package com.simone.jarvismobile.automation.rule

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.simone.jarvismobile.core.automation.rule.Capability
import com.simone.jarvismobile.core.automation.rule.CapabilityStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Answers, honestly, whether the phone can currently deliver a given ability
 * (§19, and the prompt's absolute rule about never pretending).
 *
 * The four answers are meaningfully different and the UI treats them
 * differently: [CapabilityStatus.REQUIRES_PERMISSION] is something the user can
 * grant in a dialog, [CapabilityStatus.REQUIRES_USER_ACTION] needs a trip to a
 * settings screen, and [CapabilityStatus.UNSUPPORTED] means this device or this
 * build simply cannot do it and no amount of tapping will help. Collapsing them
 * into a boolean would send the user to a settings page that cannot fix
 * anything.
 */
@Singleton
class CapabilityManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun status(capability: Capability): CapabilityStatus = when (capability) {
        Capability.NONE -> CapabilityStatus.SUPPORTED

        Capability.POST_NOTIFICATIONS ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                CapabilityStatus.SUPPORTED
            } else {
                permission(Manifest.permission.POST_NOTIFICATIONS)
            }

        Capability.EXACT_ALARM -> exactAlarm()

        Capability.BLUETOOTH ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                permission(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                CapabilityStatus.SUPPORTED
            }

        Capability.LOCATION_BACKGROUND -> backgroundLocation()

        Capability.ACTIVITY_RECOGNITION ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                permission(Manifest.permission.ACTIVITY_RECOGNITION)
            } else {
                // Below Q the transition API is not usable the way the engine
                // needs, so this is a real "no", not a permission away.
                CapabilityStatus.UNSUPPORTED
            }

        Capability.NOTIFICATION_LISTENER -> notificationListener()

        // Phase 7 of the project. Saying REQUIRES_USER_ACTION would imply a
        // setting exists somewhere; it does not.
        Capability.HOME_ASSISTANT -> CapabilityStatus.UNSUPPORTED
    }

    /** Convenience for the gate, which takes a plain function. */
    fun asLookup(): (Capability) -> CapabilityStatus = ::status

    /** Everything currently missing, for the diagnostics screen. */
    fun missing(): List<Pair<Capability, CapabilityStatus>> =
        Capability.entries
            .filter { it != Capability.NONE }
            .map { it to status(it) }
            .filterNot { it.second.usable }

    /**
     * A short, non-technical explanation of why something is unavailable and
     * what — if anything — the user can do about it.
     */
    fun explain(capability: Capability): String = when (status(capability)) {
        CapabilityStatus.SUPPORTED -> "Disponibile."
        CapabilityStatus.REQUIRES_PERMISSION -> when (capability) {
            Capability.POST_NOTIFICATIONS -> "Serve il permesso per le notifiche."
            Capability.BLUETOOTH -> "Serve il permesso Bluetooth."
            Capability.LOCATION_BACKGROUND ->
                "Serve la posizione, anche quando l'app è chiusa."
            Capability.ACTIVITY_RECOGNITION -> "Serve il permesso per il rilevamento attività."
            else -> "Serve un permesso."
        }
        CapabilityStatus.REQUIRES_USER_ACTION -> when (capability) {
            Capability.EXACT_ALARM ->
                "Serve il permesso «Sveglie e promemoria» nelle impostazioni di sistema."
            Capability.NOTIFICATION_LISTENER ->
                "Serve l'accesso alle notifiche in Impostazioni Android › Accesso notifiche."
            else -> "Serve un'azione nelle impostazioni."
        }
        CapabilityStatus.UNSUPPORTED -> when (capability) {
            Capability.HOME_ASSISTANT -> "Home Assistant non è ancora collegabile in questa versione."
            Capability.ACTIVITY_RECOGNITION -> "Questo telefono non espone il rilevamento attività."
            else -> "Non disponibile su questo dispositivo."
        }
    }

    // --- Individual checks ---------------------------------------------

    private fun permission(name: String): CapabilityStatus =
        if (ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED) {
            CapabilityStatus.SUPPORTED
        } else {
            CapabilityStatus.REQUIRES_PERMISSION
        }

    /**
     * Exact alarms are a settings-screen grant on Android 12+, not a runtime
     * dialog — hence REQUIRES_USER_ACTION rather than REQUIRES_PERMISSION.
     */
    private fun exactAlarm(): CapabilityStatus {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return CapabilityStatus.SUPPORTED
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return CapabilityStatus.UNSUPPORTED
        return if (manager.canScheduleExactAlarms()) {
            CapabilityStatus.SUPPORTED
        } else {
            CapabilityStatus.REQUIRES_USER_ACTION
        }
    }

    /**
     * Background location is two grants on Android 10+: the foreground one in a
     * dialog, then the background one from a settings screen. Reporting only the
     * first would let a geofence rule look armed while it can never fire in the
     * background — the exact dishonesty this layer exists to prevent.
     */
    private fun backgroundLocation(): CapabilityStatus {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return CapabilityStatus.REQUIRES_PERMISSION
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return CapabilityStatus.SUPPORTED
        val background = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        return if (background) CapabilityStatus.SUPPORTED else CapabilityStatus.REQUIRES_USER_ACTION
    }

    private fun notificationListener(): CapabilityStatus {
        val enabled = runCatching {
            Settings.Secure.getString(context.contentResolver, ENABLED_LISTENERS)
                ?.contains(context.packageName) == true
        }.getOrDefault(false)
        return if (enabled) CapabilityStatus.SUPPORTED else CapabilityStatus.REQUIRES_USER_ACTION
    }

    private companion object {
        const val ENABLED_LISTENERS = "enabled_notification_listeners"
    }
}
