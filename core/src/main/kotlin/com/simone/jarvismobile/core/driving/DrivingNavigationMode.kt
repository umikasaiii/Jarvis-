package com.simone.jarvismobile.core.driving

/**
 * Which navigation backend drives Modalità Guida.
 *
 * [EXTERNAL_MAPS_OVERLAY] is the current, default, user-facing behaviour: the
 * `WindowManager` overlay on top of the real Google Maps app, launched by
 * Intent (`DrivingModeService`/`DrivingMapsLauncher`) — no routing or map
 * rendering of JARVIS's own. This stays the default until the internal mode
 * is complete.
 *
 * [INTERNAL_JARVIS_NAVIGATION] is the new, fully in-app `DrivingModeActivity`.
 * It is not a second navigation stack: it reuses the same offline engine
 * already powering the "Navigazione offline" screen (MapLibre rendering,
 * `OfflineRoutingEngine`/`AStarRouter`, `NavigationRepository`'s GPS/route/
 * progress flows) behind a UI that never talks to Google Maps. Developer-only
 * (behind a feature flag) while it is being built.
 */
enum class DrivingNavigationMode {
    EXTERNAL_MAPS_OVERLAY,
    INTERNAL_JARVIS_NAVIGATION,
}
