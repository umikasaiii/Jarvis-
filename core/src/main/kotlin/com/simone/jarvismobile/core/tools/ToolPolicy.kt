package com.simone.jarvismobile.core.tools

/**
 * Risk classification of a tool (docs/SECURITY.md §15). Higher ordinal = higher
 * risk. `CONFIRMING_WRITE` and higher require confirmation; the strongest
 * categories additionally require a biometric-capable execution surface.
 */
enum class ToolPolicy {
    READ_ONLY,
    LOW_RISK_WRITE,
    /** A local write that must be shown exactly and explicitly approved. */
    CONFIRMING_WRITE,
    EXTERNAL_COMMUNICATION,
    SENSITIVE_DEVICE_ACTION,
    HOME_SECURITY,
    DESTRUCTIVE;

    /** Whether this policy requires explicit user confirmation before executing. */
    val requiresConfirmation: Boolean
        get() = this >= CONFIRMING_WRITE

    /** Whether this policy requires biometric authentication (strongest gate). */
    val requiresBiometric: Boolean
        get() = this == HOME_SECURITY || this == DESTRUCTIVE
}

/** How sensitive the *data* a tool touches is (independent of write-risk). */
enum class SensitivityLevel { PUBLIC, PERSONAL, SENSITIVE }
