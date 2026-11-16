package com.simone.jarvismobile.core.net

/**
 * M3-D — the offline policy, decided per OPERATION at the boundary of every call that can touch the network (the app puts it in an OkHttp
 * interceptor and in the few non-OkHttp initiators). The UI is not the guard: a switch in a screen never stops a worker.
 *
 *  - STRICT_OFFLINE: JARVIS itself makes NO network request. Everything below is blocked, whatever the user toggled in an integration.
 *  - EXPLICIT_ONLINE (default): an operation may use the network only when the user enabled that integration; assistant-path operations
 *    (chat, knowledge, documents, memory, tools) never use the network by themselves.
 *
 * Out of scope and NOT claimed: traffic of other apps started through Intents (Organic Maps, Kiwix, browser…), Google Play services components
 * (Drive authorisation, ML Kit model download), the OS. A "connected" flag is never used as proof that nothing was sent.
 */
enum class OfflineMode { STRICT_OFFLINE, EXPLICIT_ONLINE }

enum class NetOperation(val label: String, val assistantPath: Boolean) {
    /** Core PC / remote brain used for a chat turn. Part of the assistant path: never a silent fallback. */
    CORE_REMOTE_CHAT("core_remote_chat", true),
    CORE_PROBE("core_probe", false),
    WEATHER("weather", false),
    MAP_STYLE_ONLINE("map_style_online", false),
    MAP_TRAFFIC("map_traffic", false),
    MAP_SEARCH("map_search", false),
    MAP_ROUTING("map_routing", false),
    MAP_REGION_DOWNLOAD("map_region_download", false),
    MAP_CATALOG("map_catalog", false),
    BACKUP_DRIVE("backup_drive", false),
    AUTH("auth", false),
    TRANSLATION_MODEL_DOWNLOAD("translation_model_download", false),
    UNKNOWN("unknown", false),
}

enum class Decision { ALLOW, BLOCK }

enum class Cause {
    ALLOWED_EXPLICIT_USER_INTEGRATION, ALLOWED_NOT_STRICT,
    BLOCKED_STRICT_OFFLINE, BLOCKED_NOT_ENABLED_BY_USER, BLOCKED_REQUEST_CANCELLED, BLOCKED_UNKNOWN_OPERATION,
}

data class NetDecision(val decision: Decision, val cause: Cause)

object NetworkPolicy {
    /**
     * [userEnabled]: the user switched this integration on (and, for the assistant path, authorised the remote brain). [requestActive]: the request the
     * call belongs to is still live (a cancelled request must not start a late attempt).
     */
    fun decide(mode: OfflineMode, op: NetOperation, userEnabled: Boolean, requestActive: Boolean = true): NetDecision = when {
        !requestActive -> NetDecision(Decision.BLOCK, Cause.BLOCKED_REQUEST_CANCELLED)
        mode == OfflineMode.STRICT_OFFLINE -> NetDecision(Decision.BLOCK, Cause.BLOCKED_STRICT_OFFLINE)
        op == NetOperation.UNKNOWN -> NetDecision(Decision.BLOCK, Cause.BLOCKED_UNKNOWN_OPERATION) // an uncensused call site is not allowed by default
        !userEnabled -> NetDecision(Decision.BLOCK, Cause.BLOCKED_NOT_ENABLED_BY_USER)
        else -> NetDecision(Decision.ALLOW, Cause.ALLOWED_EXPLICIT_USER_INTEGRATION)
    }
}

/** Thrown at the boundary when the policy blocks a call; an IOException so every existing network error path treats it as a failed call. */
class OfflineBlockedException(val op: NetOperation, val cause2: Cause) : java.io.IOException("offline_blocked:${op.label}:${cause2.name}")
