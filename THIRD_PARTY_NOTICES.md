# Third-Party Notices

JARVIS Mobile depends on the following third-party components. Exact versions are
pinned in `gradle/libs.versions.toml` (Android) and `core/build.gradle.kts` (core).
This list is the project's dependency/license inventory (SBOM-lite, docs/SECURITY.md §21).

## Runtime / build libraries

| Component | License |
|-----------|---------|
| Kotlin, kotlinx-coroutines, kotlinx-serialization (JetBrains) | Apache-2.0 |
| AndroidX Core, Activity, Lifecycle, Navigation, DataStore, WorkManager, Room, Security-Crypto, Biometric, Splashscreen | Apache-2.0 |
| Jetpack Compose + Material 3 | Apache-2.0 |
| Dagger / Hilt (Google) | Apache-2.0 |
| OkHttp (Square) | Apache-2.0 |
| Android Gradle Plugin, KSP | Apache-2.0 |

## Test libraries

| Component | License |
|-----------|---------|
| JUnit 4 | EPL-1.0 |
| kotlinx-coroutines-test | Apache-2.0 |
| Turbine (Cash App) | Apache-2.0 |
| OkHttp MockWebServer | Apache-2.0 |
| AndroidX Test (JUnit ext, Espresso), Compose UI Test | Apache-2.0 |

## Planned native / model components (imported by the user, not bundled)

| Component | License | Notes |
|-----------|---------|-------|
| llama.cpp | MIT | Phase 3 LLM runtime. |
| sherpa-onnx | Apache-2.0 | Phase 2 VAD/STT runtime. |
| GGUF models (e.g. Qwen3) | Model-specific | License shown in-app before import; verify per model. |
| sherpa-onnx STT/TTS models | Model-specific | License shown before import. |

Model **weights** are never included in this repository and are subject to their
own licenses, which the app displays before any import/download.
