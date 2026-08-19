package com.simone.jarvismobile.core.engine

/**
 * How hard `ConversationalJarvisEngine` should think before answering.
 *
 * Maps onto the model-slot choice `LlmRouter` already makes for Classic mode
 * (`ComplexityHeuristic.needsReasoning` picking the fast vs. advanced brain) —
 * this is the same axis, exposed as an explicit setting instead of an
 * always-automatic heuristic:
 *  - [FAST] always uses the fast slot.
 *  - [DEEP] always uses the advanced slot (falling back to fast if none is
 *    loaded — the model simply may not be available on this device).
 *  - [AUTO] (default) delegates back to the existing heuristic per turn.
 *
 * Never exposes a reasoning trace either way — only the modelled slot choice.
 */
enum class ReasoningMode {
    FAST,
    AUTO,
    DEEP,
}
