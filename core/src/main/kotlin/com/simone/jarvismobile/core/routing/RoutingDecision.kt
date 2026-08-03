package com.simone.jarvismobile.core.routing

import com.simone.jarvismobile.core.state.RouteTarget
import com.simone.jarvismobile.core.tools.SensitivityLevel

/**
 * Privacy profile chosen by the user (docs/PRIVACY.md §13). Controls whether,
 * and how much, may leave the device toward a companion PC.
 */
enum class PrivacyProfile {
    DEVICE_ONLY,
    PREFER_DEVICE,
    PREFER_PC_TRUSTED_NETWORK,
    ALLOW_PC_VIA_VPN,
    MAX_PRIVACY;

    /** Whether this profile ever permits sending anything to a remote PC. */
    val allowsRemote: Boolean
        get() = this == PREFER_PC_TRUSTED_NETWORK || this == ALLOW_PC_VIA_VPN
}

/** What kind of memory context, if any, may be attached to a remote request. */
enum class MemorySharing { NONE, SELECTED_FRAGMENTS_ONLY, ALLOWED }

/**
 * Fully-auditable record of a routing decision. Every field exists so the UI
 * and logs can explain *why* a request went where it did, and *what* would be
 * shared, before anything leaves the device.
 */
data class RoutingDecision(
    val target: RouteTarget,
    /** Technical reason string (no personal data). */
    val reason: String,
    val timeoutMs: Long,
    val memorySharing: MemorySharing,
    val sensitivity: SensitivityLevel,
    val fallback: RouteTarget?,
)
