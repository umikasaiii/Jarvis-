package com.simone.jarvismobile.core.navigation

/**
 * The atomic, immutable representation of one route generation's derived
 * runtime state (§ JARVIS Implementation Master Plan PASSAGGIO 9.1 §1).
 *
 * Before this type existed, [com.simone.jarvismobile.navigation.NavigationRepository]
 * held [route]/[matcher]/[progressCalculator] as three independently
 * mutable fields, written by three separate sequential assignments inside
 * one publish — a reader on another thread (`onFix`) could observe a torn
 * combination: a new route paired with an old matcher/calculator, or any
 * other partial mix, because nothing tied the three together as one unit.
 *
 * A single `@Volatile` reference to one of these can never be torn — a
 * reader either sees the whole previous snapshot or the whole new one,
 * never a mixture — and [generation] travels with the data it belongs to,
 * so a caller holding a snapshot never needs a second lookup to know which
 * route generation it is looking at.
 *
 * Not a second source of truth: [NavigationRepository]'s public
 * `route`/`progress`/`navState` StateFlows remain what the UI observes: this
 * is the private, internal grouping that makes deriving further state from
 * a route (matching, progress, arrival) safe to do outside a lock and
 * publish back atomically.
 */
data class ActiveRouteSnapshot(
    val generation: Long,
    val route: Route,
    val matcher: MapMatcher,
    val progressCalculator: RouteProgressCalculator,
)
