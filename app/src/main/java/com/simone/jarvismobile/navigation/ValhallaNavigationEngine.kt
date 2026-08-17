package com.simone.jarvismobile.navigation

/**
 * NOT IMPLEMENTED — explicitly, per the plan this repo is following: [NavigationEngine]
 * must have exactly one real backend ([AStarRouterEngine]) before a second one is
 * attempted, and that second one must not be built against a guessed API.
 *
 * What was actually checked before writing this file (session research, this
 * repo's egress proxy blocks several of the relevant hosts so this is
 * necessarily partial):
 * - Valhalla has no official Android target; the community project
 *   `Rallista/valhalla-mobile` (MIT) wraps it as a Gradle-consumable Android
 *   library, publishing to Maven Central as `io.github.rallista:valhalla-mobile`.
 *   Its own README's Gradle snippet shows `0.1.0`, but its GitHub Releases page
 *   lists `0.5.1` as the current tag — these were NOT reconciled (Maven Central
 *   itself — `search.maven.org`/`repo1.maven.org` — is unreachable from this
 *   environment), so the version to actually depend on still needs confirming
 *   from an environment that can reach Maven Central directly.
 * - It "only exposes the route function" per its own README — no trace/map-matching
 *   API was found. That is fine for this engine's contract (map matching in this
 *   app is [com.simone.jarvismobile.core.navigation.MapMatcher], already
 *   provider-agnostic pure `:core` code that works on any [com.simone.jarvismobile.core.navigation.Route]
 *   regardless of which [NavigationEngine] produced it — it does not need to
 *   change when this lands).
 * - The exact Kotlin API (class/method names, tile-config format, ABI list, NDK
 *   version pinned for a *consumer* app) could not be confirmed from source —
 *   pages under `rallista.github.io` and the exact source file paths tried were
 *   blocked or 404'd from this environment. Implementing against a guessed
 *   signature would silently produce code nobody has verified compiles, let
 *   alone works — that is the "scatola chiusa" this file exists to avoid.
 *
 * To actually build this: confirm the real Maven coordinate + version from an
 * unblocked environment, read the real Kotlin API (Dokka docs / source), write
 * `ValhallaNavigationEngine : NavigationEngine` against that real API, generate
 * Valhalla graph tiles for a test region on a PC, and verify route calculation
 * end-to-end via CI before ever touching [com.simone.jarvismobile.di.NavigationEngineModule]'s binding.
 */
private const val VALHALLA_NAVIGATION_ENGINE_NOT_YET_IMPLEMENTED = true
