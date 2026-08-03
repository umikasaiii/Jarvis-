# Android Build Audit (Phase 1)

Goal: make the existing, never-compiled Android code actually build an installable
debug APK, verified by a real environment (GitHub Actions with the Android SDK).
The scaffolding container has no Android SDK/NDK and blocks `dl.google.com`, so
**local compilation is impossible here** — CI is the compiler.

## 1. Configuration review

| Item | State | Notes |
|------|-------|-------|
| `settings.gradle.kts` | OK | `includeBuild("core")` composite; `google()` content-filtered; `:app` included. |
| root `build.gradle.kts` | OK | Plugins declared `apply false` via catalog aliases. |
| `app/build.gradle.kts` | OK, hardened | compileSdk/targetSdk 35, minSdk 31, arm64-v8a, Compose, Hilt, KSP, Room. |
| `gradle/libs.versions.toml` | Provisional | AGP 8.7.3 / Kotlin 2.0.21 / KSP 2.0.21-1.0.28 / Hilt 2.52 / Compose BOM 2024.10.01. Exact availability is confirmed only when CI resolves them. |
| JDK / Gradle / AGP | Compatible | Gradle 8.11.1 wrapper; AGP 8.7 needs Gradle 8.9+ and JDK 17 — CI Android job uses JDK 17. |
| namespace / applicationId | OK | `com.simone.jarvismobile`; sources under `app/src/main/java/com/simone/jarvismobile`. |
| AndroidManifest | OK, reviewed | FGS type `microphone`, `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`, `BLUETOOTH_CONNECT`, `RECORD_AUDIO`; tile + deep link declared. |
| Resources / theme / icons / strings | OK | Adaptive launcher, splash theme, Italian strings, vector icons. Added diagnostics strings. |
| Application / Activity / Services / Tile | Reworked | See §2 fixes. |
| DI (Hilt) | OK, extended | `AudioModule` binds interfaces; added `SessionCoordinator` (`@Singleton`). |

## 2. Real compile risks found & fixes applied

These are concrete issues that would fail the build or crash at runtime, fixed
before the first CI run to save round-trips:

1. **`TileService.startActivityAndCollapse`** — the `PendingIntent` overload exists
   only on API 34+. On minSdk 31 the code must branch: `PendingIntent` overload on
   34+, deprecated `Intent` overload on 31–33. *(Fixed in `JarvisTileService`.)*
2. **`MaterialTheme.colorScheme` member import** — importing the `@Composable`
   property getter unqualified is fragile. Switched to fully-qualified
   `MaterialTheme.colorScheme.*`. *(Fixed in `HomeScreen`.)*
3. **Double `beginSession()`** — both the service and the ViewModel began an audio
   session. Ownership consolidated into a single `SessionCoordinator` that the
   foreground service drives; the ViewModel only observes. *(Fixed.)*
4. **`START_STICKY` on a mic FGS** — Phase-1 must not auto-restart recording.
   Switched to `START_NOT_STICKY`. *(Fixed in `ListeningService`.)*
5. **Tile → background FGS start** — starting a `microphone`-type FGS from the
   tile's background context is restricted. The tile now launches an explicit
   Activity (`jarvis://listen`) which starts the FGS while foregrounded, then the
   session proceeds. *(Fixed.)*
6. **Configuration cache** — disabled for now (`org.gradle.configuration-cache=false`)
   to avoid first-build flakiness with Hilt/KSP tasks; can be re-enabled later.

## 3. Phase-1 functional gaps closed

- Real `AudioRecord` capture behind an `AudioCapture` interface
  (`AndroidAudioCapture`), fixed 3 s window, RMS mic-level flow, buffer discarded
  immediately (no persistence).
- Fixed TTS reply set to *"Sistema audio operativo. Sono pronto."*, offline-voice
  only, with a clear fallback path.
- Diagnostics screen with the required fields + Test microfono / Test voce /
  Reset audio buttons.
- The service/UI are wired to the **real** `ConversationStateMachine` from `:core`
  via a new pure-Kotlin `Fase1Flow`, with an explicit, documented Phase-1
  `SkipToSpeaking` transition (no simulated STT/LLM states).

## 4. Verification method

CI runs, in order (must all pass):

```
./gradlew --no-daemon :core:test
./gradlew --no-daemon :app:assembleDebug
./gradlew --no-daemon :app:testDebugUnitTest
./gradlew --no-daemon :app:lintDebug
```

Build is "successful" only when the runner log shows `BUILD SUCCESSFUL`, the
uploaded artifact contains a real `app-debug.apk`, and a CI step prints its
SHA-256. AirPods routing can only be declared verified after a physical test on
the HONOR 200 (see `docs/PHASE1_HONOR_TEST_CHECKLIST.md`).

## 5. CI results log

| Run | Commit | Result | Notes |
|-----|--------|--------|-------|
| [#2](https://github.com/umikasaiii/Jarvis-/actions/runs/30802226114) | `f8e94dd` | ✅ **BUILD SUCCESSFUL** | All four tasks green on the first Phase-1 build (no error iterations needed thanks to the pre-emptive fixes in §2). |

### Verified evidence (run #2)

- Tasks: `:core:test` (green), `:app:assembleDebug` (green), `:app:testDebugUnitTest`
  (green), `:app:lintDebug` → `BUILD SUCCESSFUL in 48s`, lint HTML report written.
- Artifact `app-debug` (id 8851434207) contains **`app-debug.apk`** + `app-debug.apk.sha256`.
- **APK SHA-256:** `f0f5b7c197284e38e48f631f2a31cc0f15c2d4c58b4c05d6c4b5d5e04b7a0546`
- APK size: 64,994,357 bytes (unminified debug; the icons-extended set dominates —
  release minification will shrink this substantially).
- Local path after a checkout+build: `app/build/outputs/apk/debug/app-debug.apk`.

A successful CI build proves the app **compiles and packages**. It does **not**
prove the audio loop / AirPods routing works — that requires the physical HONOR
200 test in `docs/PHASE1_HONOR_TEST_CHECKLIST.md`.
