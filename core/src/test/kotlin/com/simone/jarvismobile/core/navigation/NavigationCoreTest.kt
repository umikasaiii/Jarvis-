package com.simone.jarvismobile.core.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NavigationCoreTest {

    // A simple route: east ~415 m, then a left turn north ~555 m (near Rome).
    private val p0 = LatLng(41.9000, 12.5000)
    private val p1 = LatLng(41.9000, 12.5050)
    private val p2 = LatLng(41.9050, 12.5050)

    private fun sampleRoute(): Route = Route.fromGeometry(
        geometry = listOf(p0, p1, p2),
        maneuvers = listOf(
            Maneuver(ManeuverType.DEPART, p0, 0),
            Maneuver(ManeuverType.TURN_LEFT, p1, 1, roadName = "Via Roma"),
            Maneuver(ManeuverType.ARRIVE, p2, 2),
        ),
        destination = p2,
    )

    private fun fix(p: LatLng, bearing: Float? = null, speed: Float? = 12f, acc: Float = 5f) =
        GpsFix(p, acc, speed, bearing, 0L)

    // --- geo -----------------------------------------------------------------

    @Test fun haversineAndBearingAreReasonable() {
        val d = Geo.distanceMeters(p0, p1)
        assertTrue(d in 380.0..460.0, "east leg ~415 m, was $d")
        val b = Geo.bearingDegrees(p0, p1)
        assertTrue(Geo.bearingDelta(b, 90.0) < 2.0, "should head east, was $b")
    }

    @Test fun projectionClampsToSegment() {
        val proj = Geo.projectPointToSegment(LatLng(41.9010, 12.5025), p0, p1)
        assertTrue(proj.t in 0.0..1.0)
        assertTrue(proj.distanceMeters in 90.0..130.0, "≈111 m north of the leg, was ${proj.distanceMeters}")
    }

    // --- route wrapper -------------------------------------------------------

    @Test fun routeWrapperComputesTotalsAndSegments() {
        val r = sampleRoute()
        assertTrue(r.isValid)
        assertTrue(r.distanceMeters in 900.0..1050.0, "total ≈967 m, was ${r.distanceMeters}")
        assertEquals(2, r.segments.size)
        assertTrue(r.estimatedDurationSeconds > 0)
    }

    // --- map region selection ------------------------------------------------

    private fun region(id: String, b: GeoBounds, state: InstallState) = RegionMetadata(
        id = id, name = id, bounds = b,
        versions = RegionVersions(1, 1, 1, 1),
        osmDate = "2026-01-01", sizeBytes = 1000, checksumSha256 = "abc",
        files = RegionFiles("m.pmtiles", "r.brf", "s.db", "style.json"),
        state = state,
    )

    @Test fun regionSelectionPrefersSmallestContaining() {
        val italy = region("italia", GeoBounds(35.0, 6.0, 47.0, 19.0), InstallState.INSTALLED)
        val lazio = region("lazio", GeoBounds(41.0, 11.4, 42.7, 13.9), InstallState.INSTALLED)
        val picked = RegionSelector.regionFor(p0, listOf(italy, lazio))
        assertEquals("lazio", picked?.id)
    }

    @Test fun uncoveredPositionReturnsNull() {
        val lazio = region("lazio", GeoBounds(41.0, 11.4, 42.7, 13.9), InstallState.INSTALLED)
        assertNull(RegionSelector.regionFor(LatLng(45.4, 9.2), listOf(lazio)))
        assertTrue(!RegionSelector.isCovered(LatLng(45.4, 9.2), listOf(lazio)))
    }

    @Test fun corruptRegionIsNotUsable() {
        val lazio = region("lazio", GeoBounds(41.0, 11.4, 42.7, 13.9), InstallState.CORRUPT)
        assertNull(RegionSelector.regionFor(p0, listOf(lazio)))
        assertTrue(!lazio.isUsable)
    }

    // --- map matching --------------------------------------------------------

    @Test fun matchSnapsOntoTheRoute() {
        val m = MapMatcher(sampleRoute())
        val res = m.match(fix(LatLng(41.9004, 12.5025), bearing = 90f))
        assertEquals(0, res.segmentIndex)
        assertTrue(res.offRouteMeters < 60.0)
        assertTrue(res.distanceAlongMeters in 150.0..320.0)
    }

    // --- distance to maneuver + ETA -----------------------------------------

    @Test fun progressReportsNextManeuverAndEta() {
        val route = sampleRoute()
        val matcher = MapMatcher(route)
        val calc = RouteProgressCalculator(route, matcher)
        val match = matcher.match(fix(LatLng(41.9000, 12.5010), bearing = 90f, speed = 10f))
        val prog = calc.progress(match, fix(LatLng(41.9000, 12.5010), speed = 10f))
        assertEquals(ManeuverType.TURN_LEFT, prog.nextManeuver?.type)
        assertTrue(prog.distanceToManeuverMeters in 250.0..380.0, "to turn, was ${prog.distanceToManeuverMeters}")
        assertTrue(prog.remainingDistanceMeters > prog.distanceToManeuverMeters)
        assertTrue(prog.etaSeconds > 0)
    }

    // --- off-route detection -------------------------------------------------

    @Test fun singleWobbleDoesNotRecalc() {
        val route = sampleRoute()
        val matcher = MapMatcher(route)
        val det = OffRouteDetector()
        // On-route sample, then one wobble: must NOT trigger.
        det.update(matcher.match(fix(LatLng(41.9000, 12.5010))), fix(LatLng(41.9000, 12.5010)))
        val wobble = LatLng(41.9010, 12.5010)
        assertTrue(!det.update(matcher.match(fix(wobble)), fix(wobble)))
    }

    @Test fun confirmedDepartureTriggersRecalc() {
        val route = sampleRoute()
        val matcher = MapMatcher(route)
        val det = OffRouteDetector(confirmSamples = 3)
        val off = LatLng(41.9100, 12.5200) // clearly off route, moving
        var triggered = false
        repeat(3) { triggered = det.update(matcher.match(fix(off, speed = 12f)), fix(off, speed = 12f)) }
        assertTrue(triggered)
    }

    // --- state machine -------------------------------------------------------

    @Test fun stateMachineHappyPath() {
        val sm = NavigationStateMachine()
        assertEquals(NavState.SEARCHING, sm.dispatch(NavEvent.SearchStarted))
        assertEquals(NavState.ROUTE_READY, sm.dispatch(NavEvent.RouteFound))
        assertEquals(NavState.NAVIGATING, sm.dispatch(NavEvent.StartNavigation))
        assertEquals(NavState.RECALCULATING, sm.dispatch(NavEvent.OffRouteConfirmed))
        assertEquals(NavState.NAVIGATING, sm.dispatch(NavEvent.RecalculationDone))
        assertEquals(NavState.ARRIVED, sm.dispatch(NavEvent.Arrived))
    }

    @Test fun gpsLossReturnsToPreviousState() {
        val sm = NavigationStateMachine()
        sm.dispatch(NavEvent.SearchStarted); sm.dispatch(NavEvent.RouteFound); sm.dispatch(NavEvent.StartNavigation)
        assertEquals(NavState.GPS_WEAK, sm.dispatch(NavEvent.GpsLost))
        assertEquals(NavState.NAVIGATING, sm.dispatch(NavEvent.GpsRestored))
    }

    // --- search ranking ------------------------------------------------------

    @Test fun searchFindsPiazzaNavonaOffline() {
        val places = listOf(
            Place("1", "Piazza Navona", PlaceCategory.TOURISM, LatLng(41.899, 12.473), 0.9, "Roma"),
            Place("2", "Piazza di Spagna", PlaceCategory.TOURISM, LatLng(41.905, 12.482), 0.9, "Roma"),
            Place("3", "Via Navona", PlaceCategory.STREET, LatLng(45.07, 7.68), 0.2, "Torino"),
        )
        val hits = PlaceSearchRanker.rank("piazza navona roma", places, origin = LatLng(41.9, 12.5))
        assertEquals("1", hits.first().place.id)
    }

    @Test fun nearestFuelPicksClosest() {
        val places = listOf(
            Place("far", "Distributore A", PlaceCategory.FUEL, LatLng(41.95, 12.55)),
            Place("near", "Distributore B", PlaceCategory.FUEL, LatLng(41.901, 12.501)),
        )
        val hit = PlaceSearchRanker.nearest(PlaceCategory.FUEL, places, origin = p0)
        assertEquals("near", hit?.place?.id)
    }

    // --- favourites ----------------------------------------------------------

    @Test fun favoriteResolutionHome() {
        val favs = listOf(FavoritePlace(FavoriteKind.HOME, "Casa", LatLng(41.9, 12.5)))
        assertNotNull(FavoriteResolver.resolve("portami a casa", favs))
        assertEquals(FavoriteKind.HOME, FavoriteResolver.referencesFavorite("naviga verso casa"))
    }

    @Test fun favoriteUnsetReturnsNullNotAGuess() {
        assertNull(FavoriteResolver.resolve("portami al lavoro", emptyList()))
    }

    // --- intent mapping ------------------------------------------------------

    @Test fun intentNavigateWithDestination() {
        val i = NavIntentParser.parse("Portami a Piazza Navona")
        assertTrue(i is NavIntent.Navigate)
        assertEquals("piazza navona", (i as NavIntent.Navigate).query)
    }

    @Test fun intentFavoriteHome() {
        assertEquals(NavIntent.NavigateFavorite(FavoriteKind.HOME), NavIntentParser.parse("naviga verso casa"))
    }

    @Test fun intentNearestFuel() {
        assertEquals(
            NavIntent.NavigateNearest(PlaceCategory.FUEL),
            NavIntentParser.parse("portami al distributore più vicino"),
        )
    }

    @Test fun intentSearchNearbyParking() {
        assertEquals(
            NavIntent.SearchNearby(PlaceCategory.PARKING),
            NavIntentParser.parse("trova un parcheggio"),
        )
    }

    @Test fun intentAvoidMotorway() {
        val i = NavIntentParser.parse("evita l'autostrada")
        assertTrue(i is NavIntent.SetAvoid)
        assertTrue((i as NavIntent.SetAvoid).options.avoidMotorways)
    }

    @Test fun intentStatusQueries() {
        assertEquals(NavIntent.Status(NavIntent.StatusKind.REMAINING_DISTANCE), NavIntentParser.parse("quanto manca?"))
        assertEquals(NavIntent.Status(NavIntent.StatusKind.ETA), NavIntentParser.parse("a che ora arrivo?"))
    }

    @Test fun intentStopAndResume() {
        assertEquals(NavIntent.Stop, NavIntentParser.parse("ferma navigazione"))
        assertEquals(NavIntent.Resume, NavIntentParser.parse("riprendi il percorso"))
    }

    @Test fun nonNavigationUtteranceIsNull() {
        assertNull(NavIntentParser.parse("che ore sono"))
    }

    // --- voice announcer -----------------------------------------------------

    @Test fun voiceAnnouncesTurnOnceAndNotAgain() {
        val v = VoiceAnnouncer()
        val turn = Maneuver(ManeuverType.TURN_RIGHT, p1, 1, roadName = "Via Roma")
        val near = v.onProgress(1, turn, distanceToManeuverMeters = 200.0, speedMps = 14.0)
        assertNotNull(near)
        assertTrue("destra" in near!!)
        // Same phase again → no repeat.
        assertNull(v.onProgress(1, turn, distanceToManeuverMeters = 190.0, speedMps = 14.0))
    }

    @Test fun voiceRoundaboutUsesExit() {
        val v = VoiceAnnouncer()
        val rb = Maneuver(ManeuverType.ROUNDABOUT, p1, 1, roundaboutExit = 2)
        val phrase = v.onProgress(5, rb, distanceToManeuverMeters = 30.0, speedMps = 8.0)
        assertNotNull(phrase)
        assertTrue("seconda uscita" in phrase!!, "was $phrase")
    }

    // --- no-network invariant (the whole chain is pure) ----------------------

    @Test fun fullChainRunsOfflineWithNoIo() {
        // search → route → match → progress → off-route → recalc, all in-process.
        val places = listOf(Place("d", "Piazza Navona", PlaceCategory.TOURISM, p2, 0.9, "Roma"))
        val dest = PlaceSearchRanker.rank("piazza navona", places, origin = p0).first().place
        val route = Route.fromGeometry(listOf(p0, p1, dest.location), listOf(
            Maneuver(ManeuverType.DEPART, p0, 0),
            Maneuver(ManeuverType.TURN_LEFT, p1, 1),
            Maneuver(ManeuverType.ARRIVE, dest.location, 2),
        ), dest.location)
        val matcher = MapMatcher(route)
        val calc = RouteProgressCalculator(route, matcher)
        val det = OffRouteDetector()
        val onRoute = fix(LatLng(41.9000, 12.5010), speed = 12f)
        val prog = calc.progress(matcher.match(onRoute), onRoute)
        assertTrue(prog.remainingDistanceMeters > 0)
        // Depart the route and confirm a recalc is requested — still all offline.
        val off = LatLng(41.9100, 12.5200)
        var recalc = false
        repeat(3) { recalc = det.update(matcher.match(fix(off, speed = 12f)), fix(off, speed = 12f)) }
        assertTrue(recalc)
    }
}
