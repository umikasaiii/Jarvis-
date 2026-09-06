package com.simone.jarvismobile.core.tools

/**
 * § FASE 2A.10 SEMANTIC ROUTER AUTHORITATIVE — the closed, explicit allowlist
 * of tool names [com.simone.jarvismobile.engine.FastPathRouter] (the
 * conversational engine's pre-semantic HARD COMMAND gate) may treat as an
 * instant, model-free command. Deliberately tiny: local, side-effect already
 * controlled, unambiguous regardless of phrasing, and calling a model for
 * them would be pure overhead — exactly the user's own list (torch, media
 * transport). Everything else `CommandMatcher.match()` can also produce
 * (agenda queries, reminders, alarms, timers, memory, communication drafts,
 * navigation, …) is real natural language whose MEANING must be established
 * by the Semantic Interpreter first, never bypassed just because a
 * deterministic regex happens to also match it.
 *
 * `CommandMatcher`/`Match.Run` itself is untouched — Modalità Classica keeps
 * its full deterministic command set exactly as before; this allowlist only
 * narrows what the CONVERSATIONAL engine's fast path is allowed to shortcut.
 *
 * No dedicated volume/mute tool exists in this project's registry today (only
 * `flashlight` and `media_control`'s play/pause/next/previous transport are
 * real, registered, side-effect-controlled tools) — the request's "volume
 * +/-, mute/unmute... se già supportati in modo sicuro" is honestly not
 * applicable until such a tool exists; nothing invented here.
 */
val HARD_COMMAND_TOOL_NAMES: Set<String> = setOf("flashlight", "media_control")
