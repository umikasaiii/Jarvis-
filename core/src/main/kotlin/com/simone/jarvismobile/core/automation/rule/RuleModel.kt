package com.simone.jarvismobile.core.automation.rule

import java.time.LocalDateTime

/**
 * The generic automation model: **TRIGGER + CONDITIONS + ACTIONS**.
 *
 * There is deliberately no `ArrivoACasaAutomation` class and never will be: a
 * rule about arriving home is this same [AutomationRule] holding a place trigger,
 * so anything the user can imagine is a configuration rather than new code.
 *
 * Triggers and actions are stored as [TriggerSpec]/[ActionSpec] — a type string
 * plus flat string parameters — rather than as sealed subclasses. That is what
 * makes them serialisable, versionable and migratable (§23), and what lets the
 * Rule Builder enumerate the available kinds from a registry instead of a giant
 * `when` repeated across the app (§27). The trade is that parameters are
 * untyped at rest, so every handler validates its own input before use.
 *
 * This model lives beside the original single-trigger `Automation` rather than
 * replacing it, so the existing engine keeps working while the new one is built.
 */

/** Serialised trigger: a registered [type] plus its parameters. */
data class TriggerSpec(
    val type: String,
    val params: Map<String, String> = emptyMap(),
) {
    fun param(key: String): String? = params[key]?.takeIf { it.isNotBlank() }
    fun int(key: String): Int? = param(key)?.trim()?.toIntOrNull()
}

/** Serialised action: a registered [type] plus its parameters. */
data class ActionSpec(
    val type: String,
    val params: Map<String, String> = emptyMap(),
) {
    fun param(key: String): String? = params[key]?.takeIf { it.isNotBlank() }
    fun int(key: String): Int? = param(key)?.trim()?.toIntOrNull()
}

/** What to do when a rule fires again while a previous run is still going. */
enum class ExecutionPolicy {
    /** Ignore the new firing (the safe default: no duplicate side effects). */
    SKIP_IF_RUNNING,

    /** Cancel the in-flight run and start again — for "latest wins" rules. */
    REPLACE,

    /** Run them one after the other. */
    QUEUE,
}

/**
 * How much damage an action can do (§20). Used to decide what a rule may do
 * unattended and what has to be switched on knowingly by the user.
 */
enum class ActionRisk {
    /** Reads state, changes nothing. */
    READ_ONLY,

    /** Changes only JARVIS's own local state (a note, a mode, a notification). */
    LOCAL_SAFE,

    /** Reaches outside the app — Home Assistant, another app, the network. */
    EXTERNAL_SIDE_EFFECT,

    /** Touches personal data or something not easily undone. */
    SENSITIVE,
}

/**
 * A platform ability a trigger or action depends on. Kept abstract here so
 * `:core` stays free of Android; the app layer maps each to a real permission
 * or service and reports a [CapabilityStatus].
 */
enum class Capability {
    NONE,
    LOCATION_BACKGROUND,
    ACTIVITY_RECOGNITION,
    NOTIFICATION_LISTENER,
    EXACT_ALARM,
    BLUETOOTH,
    POST_NOTIFICATIONS,
    HOME_ASSISTANT,
}

/**
 * Honest answer to "can this actually run on this phone right now?".
 *
 * The prompt's rule is absolute: never pretend an action happened. An action
 * whose capability is not [SUPPORTED] must be reported, not silently skipped
 * and not silently "succeeded".
 */
enum class CapabilityStatus {
    SUPPORTED,
    REQUIRES_PERMISSION,
    REQUIRES_USER_ACTION,
    UNSUPPORTED,
    ;

    val usable: Boolean get() = this == SUPPORTED
}

/**
 * One user-defined rule.
 *
 * [triggers] is a list because the same outcome often has several ways of
 * happening ("when I get home OR when the car disconnects"); any of them firing
 * starts an evaluation. [condition] is a single composable tree — see
 * [Condition.All]/[Condition.Any] — so AND/OR/NOT nest without needing a list
 * plus a separate operator field.
 */
data class AutomationRule(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val triggers: List<TriggerSpec> = emptyList(),
    val condition: Condition? = null,
    val actions: List<ActionSpec> = emptyList(),
    /** Higher wins when two rules want the same exclusive state (§12). */
    val priority: Int = PRIORITY_NORMAL,
    val executionPolicy: ExecutionPolicy = ExecutionPolicy.SKIP_IF_RUNNING,
    /** Minimum gap between two firings; 0 means no limit. */
    val cooldownSeconds: Long = 0,
    /** Disable the rule after its first successful run. */
    val oneShot: Boolean = false,
    /** After this moment the rule stops mattering (temporary rules). */
    val expiresAt: LocalDateTime? = null,
    val lastFiredAt: LocalDateTime? = null,
    val createdAt: LocalDateTime? = null,
    val updatedAt: LocalDateTime? = null,
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    init {
        require(cooldownSeconds >= 0) { "cooldown negativo" }
    }

    /** A rule with no action can only waste a wake-up. */
    val isRunnable: Boolean get() = enabled && actions.isNotEmpty() && triggers.isNotEmpty()

    fun hasExpired(now: LocalDateTime): Boolean = expiresAt?.isBefore(now) == true

    /**
     * Whether the cooldown has elapsed. A rule that has never fired is always
     * ready; the comparison is inclusive so a cooldown of exactly N seconds
     * allows the firing at N, not only at N+1.
     */
    fun cooldownElapsed(now: LocalDateTime): Boolean {
        if (cooldownSeconds <= 0L) return true
        val last = lastFiredAt ?: return true
        return !now.isBefore(last.plusSeconds(cooldownSeconds))
    }

    /**
     * Everything that must be true before conditions are even looked at.
     * Deliberately cheap and pure: the expensive checks come later.
     */
    fun isEligible(now: LocalDateTime): Boolean =
        isRunnable && !hasExpired(now) && cooldownElapsed(now)

    /** Privacy-safe log line: names the rule by id, never by its contents. */
    fun logLine(): String =
        "rule=$id enabled=$enabled triggers=${triggers.size} actions=${actions.size} priority=$priority"

    companion object {
        /** Bumped whenever the persisted shape changes; drives DB migrations. */
        const val SCHEMA_VERSION = 1

        const val PRIORITY_LOW = 10
        const val PRIORITY_NORMAL = 50
        const val PRIORITY_HIGH = 80
        const val PRIORITY_CRITICAL = 100
    }
}
