# 0003 — Scaffolding environment cannot build the Android app

- **Status:** Accepted (environmental fact)
- **Date:** 2026-08-03

## Context

The build container provides JDK 21, Gradle 8.14.3, CMake 3.28, Python 3.11 — but
**no Android SDK/NDK**, and its egress proxy returns **403 for `dl.google.com`**.
That host serves the Android SDK, the Android Gradle Plugin, and every
AndroidX/Compose/Material3 artifact (`google()` redirects there). Maven Central,
the Gradle plugin portal and `services.gradle.org` are reachable; `huggingface.co`
is not.

## Decision

- Compile and test `:core` here (Maven Central only) — done, 58 tests green.
- Write `:app` fully and correctly, but treat it as **build-pending** until run in
  an environment with SDK access. Do not assert it compiles here.
- Do not attempt to fetch models or NDK here.

## Consequences

- Honest status: the deliverable in this environment is verified domain logic +
  complete-but-uncompiled Android code + full docs, not a built APK.
- To produce an APK: open in Android Studio or run CI with the Android SDK and
  unrestricted access to `dl.google.com`, then `./gradlew :app:assembleDebug`.
- If a future agent runs where `dl.google.com` is allowed, it can build, then
  update `CLAUDE.md` phase state and this ADR.
