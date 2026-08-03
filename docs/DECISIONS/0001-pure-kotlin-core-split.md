# 0001 — Split the domain into a pure-Kotlin `:core` build

- **Status:** Accepted
- **Date:** 2026-08-03

## Context

The spec asks for a single well-organized Android module first, extracting Gradle
modules only when a slice is stable. Separately, it demands that the state
machine, router, tool protocol, policies and retrieval be **unit-tested**. The
scaffolding environment has no Android SDK and its network blocks `dl.google.com`,
so an Android module cannot be compiled or tested here at all.

## Decision

Keep the highest-value, framework-independent logic in a **standalone pure-Kotlin
Gradle build** at `core/` (no Android dependencies), consumed by the Android app
via a Gradle **composite build** (`includeBuild("core")`). Everything Android-
specific stays in `app/`.

## Consequences

- The conversation engine compiles and is tested on any JVM (58 tests green here),
  giving real verification instead of unbuildable claims.
- It enforces the spec's own layering rule (UI/mic/model/files/network never fused).
- Engines that need Android (audio, TTS) keep their interfaces in `app/`; if a
  clean split emerges they can move to `core/` later.
- Slightly more Gradle wiring than a single module — accepted for the testability
  and the environment reality.
