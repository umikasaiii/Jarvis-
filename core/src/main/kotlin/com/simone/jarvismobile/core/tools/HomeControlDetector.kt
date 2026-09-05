package com.simone.jarvismobile.core.tools

/**
 * § FASE 2A.6 §2/§9 — detects a request for real home-automation control
 * (room lighting, climate, shutters, locks/gates) — a capability this
 * project has no Home Assistant/smart-home integration for yet (`CLAUDE.md`
 * Phase 7, "Not started"; confirmed by audit — the only existing code that
 * even names Home Assistant is `core/routing/DeterministicCommandMatcher`'s
 * `homeAssistantHints`, part of an earlier `AssistantRouter`/`HybridRouter`
 * scaffold documented as built but never wired into the live app). This
 * reuses that exact same pattern shape (action verb + lighting/climate/
 * shutter noun) rather than inventing a second one — it is that signal,
 * wired into the live conversational path for the first time, not a
 * rewrite.
 *
 * Deliberately does NOT match "torcia"/"flash" (the phone's own flashlight,
 * a real, supported [Tool]) — those stay on [RelevantToolSelector]'s DEVICE
 * family exactly as `CommandMatcher.TORCH_RE` (the deterministic fast path)
 * already requires. This is the other half of the same root-cause fix: "luce
 * della camera"/"luce del salotto"/"lampada"/"luci di casa" must never
 * silently become a call to `flashlight` just because a tool catalog
 * happened to include it — with no real smart-home tool registered, such a
 * request is answered honestly as unsupported instead (see
 * `ConversationalJarvisEngine.runHomeControlGuard`), never guessed at.
 */
object HomeControlDetector {
    private val patterns = listOf(
        Regex(
            """\b(accend\w*|spegn\w*|abbass\w*|alz\w*|attiv\w*|disattiv\w*|imposta\w*)\b.{0,25}""" +
                """\b(luc\w+|lampad\w+|termostato|riscaldamento|climatizzatore|tapparell\w+|clima|persian\w+)\b""",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            """\b(chiudi\w*|apri\w*)\b.{0,25}\b(garage|serratura|cancello|tapparell\w+|persian\w+)\b""",
            RegexOption.IGNORE_CASE,
        ),
    )

    fun looksLikeUnsupportedHomeControl(text: String): Boolean = patterns.any { it.containsMatchIn(text) }
}
