package com.simone.jarvismobile.core.engine

/**
 * Which orchestrator turns a transcript into a reply.
 *
 * [CLASSICO] is the current, default behaviour: deterministic command/intent
 * matching first, the on-device model only where it already runs today
 * (chat replies, the classifier, Modalità Pro). Nothing about it changes when
 * this enum is introduced — see `ClassicJarvisEngine`, which wraps that
 * existing behaviour verbatim.
 *
 * [CONVERSAZIONALE] is the new LLM-first orchestrator (`ConversationalJarvisEngine`):
 * free-form understanding, multi-turn state, retrieval-backed memory, still
 * calling the exact same tool layer as Classico — only the reasoning in front
 * of it differs.
 *
 * [IBRIDA] is reserved for a future engine that blends the two. It exists as
 * a real value from day one (so the type never has to change shape again),
 * but no Settings control offers it yet and `JarvisEngineRouter` falls back
 * to [CLASSICO] if it is ever selected — the same one-value-ahead-of-its-UI
 * posture already used by `DrivingNavigationMode.INTERNAL_JARVIS_NAVIGATION`.
 */
enum class JarvisEngineMode {
    CLASSICO,
    CONVERSAZIONALE,
    IBRIDA,
}
