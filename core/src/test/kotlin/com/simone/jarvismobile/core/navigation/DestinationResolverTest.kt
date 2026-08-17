package com.simone.jarvismobile.core.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DestinationResolverTest {

    private val here = LatLng(41.9000, 12.5000)

    private val venezia = Place("venezia", "Piazza Venezia", PlaceCategory.TOURISM, LatLng(41.8955, 12.4823), 0.9, "Roma")
    private val colosseo = Place("colosseo", "Colosseo", PlaceCategory.TOURISM, LatLng(41.8902, 12.4922), 0.95, "Roma")
    private val viaRomaFrascati = Place("vr-fr", "Via Roma", PlaceCategory.STREET, LatLng(41.8, 12.68), 0.3, "Frascati")
    private val viaRomaRoma = Place("vr-rm", "Via Roma", PlaceCategory.STREET, LatLng(41.901, 12.501), 0.3, "Roma")
    private val farmacia = Place("farm1", "Farmacia Centrale", PlaceCategory.PHARMACY, LatLng(41.902, 12.503), 0.4, "Via Nazionale, 12, Roma")

    private val candidates = listOf(venezia, colosseo, viaRomaFrascati, viaRomaRoma, farmacia)

    // --- exact / partial query -------------------------------------------------

    @Test fun exactNameQueryResolvesDirectly() {
        val result = DestinationResolver.resolve("Piazza Venezia", emptyList(), candidates, here)
        val found = assertIs<ResolvedDestination.Found>(result)
        assertEquals("venezia", found.place.id)
        assertEquals(DestinationSource.OFFLINE_SEARCH, found.source)
    }

    @Test fun partialQueryStillResolves() {
        val result = DestinationResolver.resolve("colosseo", emptyList(), candidates, here)
        val found = assertIs<ResolvedDestination.Found>(result)
        assertEquals("colosseo", found.place.id)
    }

    @Test fun accentedAndCasedQueryMatches() {
        val result = DestinationResolver.resolve("PIAZZA VÉNÉZIA", emptyList(), candidates, here)
        assertIs<ResolvedDestination.Found>(result)
    }

    // --- POI category word -------------------------------------------------------

    @Test fun categoryWordResolvesToTheMatchingPoi() {
        // "farmacia" alone (spec §12 nearby-category example) reaches the same
        // resolve() search path as a name/address query — no separate mechanism.
        val result = DestinationResolver.resolve("farmacia", emptyList(), candidates, here)
        val found = assertIs<ResolvedDestination.Found>(result)
        assertEquals("farm1", found.place.id)
    }

    // --- address ----------------------------------------------------------------

    @Test fun addressQueryResolves() {
        val result = DestinationResolver.resolve("farmacia via nazionale", emptyList(), candidates, here)
        val found = assertIs<ResolvedDestination.Found>(result)
        assertEquals("farm1", found.place.id)
    }

    // --- explicit coordinate -----------------------------------------------------

    @Test fun explicitCoordinateNeverHitsSearch() {
        val result = DestinationResolver.resolve("41.9028, 12.4964", emptyList(), emptyList(), here)
        val found = assertIs<ResolvedDestination.Found>(result)
        assertEquals(DestinationSource.EXPLICIT_COORDINATE, found.source)
        assertEquals(41.9028, found.place.location.lat, 1e-6)
        assertEquals(12.4964, found.place.location.lon, 1e-6)
    }

    // --- saved place --------------------------------------------------------------

    @Test fun savedPlaceWinsOverSearch() {
        val favorites = listOf(FavoritePlace(FavoriteKind.HOME, "Casa", LatLng(41.95, 12.6)))
        val result = DestinationResolver.resolve("portami a casa", favorites, candidates, here)
        val found = assertIs<ResolvedDestination.Found>(result)
        assertEquals(DestinationSource.SAVED_PLACE, found.source)
        assertEquals(LatLng(41.95, 12.6), found.place.location)
    }

    // --- ambiguity ------------------------------------------------------------------

    @Test fun ambiguousStreetInTwoTownsAsksWhichOne() {
        // Both "Via Roma" hits share the same name/importance and neither is close
        // enough to the user to dominate on proximity alone.
        val far = LatLng(42.5, 13.0)
        val result = DestinationResolver.resolve("via roma", emptyList(), candidates, far)
        val ambiguous = assertIs<ResolvedDestination.Ambiguous>(result)
        assertTrue(ambiguous.candidates.map { it.place.id }.containsAll(listOf("vr-fr", "vr-rm")))
    }

    @Test fun sameNameFarAwayDoesNotManufactureAmbiguity() {
        // A same-named street clear across the country (not a neighbouring town)
        // must not block resolving the one right next to the user — mirrors the
        // spec's own "Piazza Venezia" example: proximity alone should be enough,
        // no city/province required.
        val nearby = Place("vr-rm", "Via Roma", PlaceCategory.STREET, LatLng(41.901, 12.501), 0.3, "Roma")
        val distant = Place("vr-to", "Via Roma", PlaceCategory.STREET, LatLng(45.07, 7.68), 0.3, "Torino")
        val result = DestinationResolver.resolve("via roma", emptyList(), listOf(nearby, distant), nearby.location)
        val found = assertIs<ResolvedDestination.Found>(result)
        assertEquals("vr-rm", found.place.id)
    }

    // --- not found / out of coverage -------------------------------------------------

    @Test fun unknownQueryIsNotFoundWhenRegionIsInstalled() {
        val result = DestinationResolver.resolve("aeroporto fantasma iperspazio", emptyList(), candidates, here, regionInstalled = true)
        assertIs<ResolvedDestination.NotFound>(result)
    }

    @Test fun unknownQueryIsOutOfCoverageWhenNoRegionInstalled() {
        val result = DestinationResolver.resolve("aeroporto fantasma iperspazio", emptyList(), emptyList(), here, regionInstalled = false)
        assertIs<ResolvedDestination.OutOfCoverage>(result)
    }

    @Test fun blankQueryIsNotFound() {
        assertIs<ResolvedDestination.NotFound>(DestinationResolver.resolve("   ", emptyList(), candidates, here))
    }

    // --- decide() re-driven after an ambiguity answer -----------------------------

    @Test fun decideOnEmptyHitsIsNotFound() {
        assertIs<ResolvedDestination.NotFound>(DestinationResolver.decide(emptyList()))
    }

    @Test fun decideOnSingleHitIsFound() {
        val hit = PlaceHit(venezia, 0.8, 100.0)
        val result = DestinationResolver.decide(listOf(hit))
        val found = assertIs<ResolvedDestination.Found>(result)
        assertEquals("venezia", found.place.id)
    }
}
