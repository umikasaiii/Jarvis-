# 0002 — Dependency versions

- **Status:** Accepted (provisional — verify on first SDK build)
- **Date:** 2026-08-03

## Context

The spec requires latest mutually-compatible **stable** versions, recorded in the
version catalog, with no invented versions and no alpha/snapshot unless justified.
The scaffolding environment blocks `dl.google.com`, so AGP/AndroidX versions could
**not** be resolved here to confirm exact availability.

## Decision

Pin conservative, known-stable versions in `gradle/libs.versions.toml`:

- AGP 8.7.3, Kotlin 2.0.21 (+ compose compiler plugin 2.0.21), KSP 2.0.21-1.0.28.
- Compose BOM 2024.10.01, Material3 via BOM, Navigation 2.8.4.
- Hilt 2.52, Room 2.6.1, DataStore 1.1.1, WorkManager 2.9.1.
- kotlinx-serialization 1.7.3, coroutines 1.9.0, OkHttp 4.12.0.
- Turbine 1.1.0, MockWebServer 4.12.0.

Two libraries are on their vendors' latest **alpha** because no stable line exists
with the needed API: `androidx.security:security-crypto` and `androidx.biometric:biometric-ktx`.
Documented here as the justification the spec requires.

## Consequences

- The catalog is the single source of truth for versions (`core/` pins its own JVM
  deps independently: Kotlin 2.0.21, serialization 1.7.3, coroutines 1.9.0).
- **Action for first SDK build:** run `./gradlew :app:dependencies` and bump any
  version the resolver rejects; update this ADR with the confirmed set.
